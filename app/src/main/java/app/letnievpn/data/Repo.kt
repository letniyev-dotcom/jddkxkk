package app.letnievpn.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** Общее состояние приложения: UI и VpnService живут в одном процессе и читают его отсюда. */
object Repo {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val subs = MutableStateFlow<List<Sub>>(emptyList())
    val smart = MutableStateFlow(true)

    /** Тема: -1 — как в системе, 0 — светлая, 1 — тёмная. */
    val themeMode = MutableStateFlow(-1)

    /** Приложения-исключения: их трафик идёт мимо VPN (применяется при следующем подключении). */
    val excluded = MutableStateFlow<Set<String>>(emptySet())

    /** Развёрнутые подписки (по id); всё остальное свёрнуто. Хранится на диске. */
    val opened = MutableStateFlow<Set<String>>(emptySet())
    val selected = MutableStateFlow<String?>(null)

    /** пинг в мс по id сервера; -1 — недоступен */
    val pings = MutableStateFlow<Map<String, Int>>(emptyMap())
    val vpn = MutableStateFlow(VpnStatus.OFF)

    /** реальная задержка через туннель, мс (пока VPN включён и приложение открыто); -1 — нет данных */
    val tunnelDelay = MutableStateFlow(-1)

    /** Экран приложения сейчас на виду: фоновые замеры идут только тогда, чтобы не тратить батарею. */
    @Volatile
    var uiVisible = false
        private set

    private var liveJob: Job? = null

    /** Когда последний раз закончился замер пингов (SystemClock.elapsedRealtime). */
    @Volatile
    var lastPingAt = 0L
        private set
    private val fails = ConcurrentHashMap<String, Int>()

    /** сервер, к которому сейчас подключены */
    val vpnServer = MutableStateFlow<String?>(null)

    private var store: Store? = null
    private var appContext: Context? = null
    private val avatarCache = ConcurrentHashMap<String, ImageBitmap>()
    private val pingMutex = Mutex()

    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        Logger.init(context)
        appContext = context.applicationContext
        val s = Store(context)
        store = s
        subs.value = SubJson.fromJson(s.subsJson)
        smart.value = s.smart
        themeMode.value = s.theme
        excluded.value = s.excluded
        opened.value = s.opened
        selected.value = s.selected
    }

    fun xudpKey(): String = store!!.xudpKey

    fun hwid(): String = store?.hwid ?: ""

    /** Причина неудачи последней загрузки подписки (внутренняя, на экране не показывается). */
    val lastError = MutableStateFlow("")

    /** Причина, по которой не удалось подключиться; показывается под статусом. */
    val connError = MutableStateFlow("")

    fun allServers(): List<Server> = subs.value.flatMap { it.servers }

    /** Сервер с самым низким пингом из всех подписок. */
    fun best(): Server? {
        val pg = pings.value
        return allServers().filter { (pg[it.id] ?: -1) > 0 }.minByOrNull { pg[it.id]!! }
            ?: allServers().firstOrNull()
    }

    fun current(): Server? {
        if (smart.value) return best()
        val id = selected.value
        return allServers().firstOrNull { it.id == id } ?: best()
    }

    fun toggleOpen(id: String) {
        val n = opened.value.toMutableSet()
        if (!n.add(id)) n.remove(id)
        opened.value = n
        store?.opened = n
    }

    fun toggleExcluded(pkg: String) {
        val n = excluded.value.toMutableSet()
        if (!n.add(pkg)) n.remove(pkg)
        excluded.value = n
        store?.excluded = n
    }

    /** Быстрое переключение светлая ↔ тёмная (кнопка слева в шапке). */
    fun setThemeDark(v: Boolean) {
        themeMode.value = if (v) 1 else 0
        store?.theme = if (v) 1 else 0
    }

    fun setSmart(v: Boolean) {
        smart.value = v
        store?.smart = v
    }

    /** Ручной выбор сервера выключает умное подключение. */
    fun select(id: String) {
        selected.value = id
        store?.selected = id
        setSmart(false)
    }

    fun setDesired(v: Boolean) {
        store?.desired = v
    }

    fun desired(): Boolean = store?.desired ?: false

    fun smartStored(): Boolean = store?.smart ?: true

    /** Новый порядок подписок после перетаскивания: [ids] — идентификаторы сверху вниз. */
    fun reorderSubs(ids: List<String>) {
        subs.update { list -> ids.mapNotNull { id -> list.firstOrNull { it.id == id } } + list.filter { it.id !in ids } }
        persist()
    }

    private fun persist() {
        store?.subsJson = SubJson.toJson(subs.value)
    }

    // ── пинг ───────────────────────────────────────────────────────

    fun tcpPing(host: String, port: Int, timeoutMs: Int = 2500): Int = try {
        val addr = InetAddress.getByName(host)
        val t = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(addr, port), timeoutMs) }
        ((System.nanoTime() - t) / 1_000_000).toInt().coerceAtLeast(1)
    } catch (e: Exception) {
        -1
    }

    // ── пакетная публикация пингов ───────────────────────────────────
    // При 100 серверах замеры приходят ~100 раз в секунду. Если выдавать каждый в pings отдельно,
    // экран перерисовывается сотни раз в секунду и дёргается (перетаскивание, плавное сворачивание).
    // Поэтому замеры копим и публикуем пачкой раз в ~0,5 с, а на время перетаскивания — держим.

    private val latestPing = ConcurrentHashMap<String, Int>()

    // отдельный поток на каждый сервер: строка списка пересобирается только при смене её пинга,
    // а не все строки при каждом пакете замеров (со 100 серверами прокрутка лагала)
    private val pingFlows = ConcurrentHashMap<String, MutableStateFlow<Int?>>()
    fun pingFlow(id: String): StateFlow<Int?> = pingFlows.getOrPut(id) { MutableStateFlow(pings.value[id]) }
    private val pendingPings = HashMap<String, Int>()
    private val pingLock = Any()
    private var flushJob: Job? = null

    /** true, пока пользователь перетаскивает подписку: UI не трогаем, замеры копятся. */
    @Volatile
    var holdPings = false

    private fun publishPing(id: String, value: Int) {
        synchronized(pingLock) {
            pendingPings[id] = value
            if (flushJob?.isActive != true) {
                flushJob = scope.launch {
                    delay(500)
                    flushPings()
                }
            }
        }
    }

    /** Выдаёт накопленные замеры в pings одним обновлением. Во время перетаскивания ничего не делает. */
    fun flushPings() {
        if (holdPings) {
            // повторим позже, когда отпустят
            synchronized(pingLock) {
                if (pendingPings.isNotEmpty() && flushJob?.isActive != true) {
                    flushJob = scope.launch {
                        delay(300)
                        flushPings()
                    }
                }
            }
            return
        }
        val batch = synchronized(pingLock) {
            val c = HashMap(pendingPings)
            pendingPings.clear()
            c
        }
        // неизменившиеся пропускаем: без лишних копий карты и рекомпозиций всего экрана
        val changed = batch.filter { (id, v) -> pings.value[id] != v }
        if (changed.isEmpty()) return
        pings.value = pings.value + changed
        changed.forEach { (id, v) -> pingFlows[id]?.value = v }
    }

    /** Один замер сервера с записью в pings. Одиночный сбой не сбрасывает показание: «—» только после двух подряд. */
    private fun measureOne(s: Server, timeoutMs: Int) {
        val ms = tcpPing(s.host, s.port, timeoutMs)
        val value = if (ms < 0) {
            val n = (fails[s.id] ?: 0) + 1
            fails[s.id] = n
            val prev = latestPing[s.id] ?: pings.value[s.id]
            if (prev != null && prev > 0 && n < 2) prev else -1
        } else {
            fails.remove(s.id)
            ms
        }
        latestPing[s.id] = value
        publishPing(s.id, value)
        lastPingAt = android.os.SystemClock.elapsedRealtime()
    }

    suspend fun pingAll(timeoutMs: Int = 2500, quiet: Boolean = false) {
        pingMutex.withLock {
            val sem = Semaphore(12)
            val list = allServers()
            coroutineScope {
                list.map { s ->
                    async {
                        sem.withPermit { measureOne(s, timeoutMs) }
                    }
                }.awaitAll()
            }
        }
        lastPingAt = android.os.SystemClock.elapsedRealtime()
        flushPings()
        if (!quiet) Logger.info("ping", "измерено: рабочих ${pings.value.values.count { it > 0 }} из ${allServers().size}")
    }

    fun refreshPings() {
        scope.launch { pingAll() }
    }

    /**
     * Пинг в реальном времени: пока приложение на экране, у каждого сервера свой независимый цикл замеров.
     * Цифры обновляются по одной примерно раз в секунду и не ждут самый медленный сервер, как при общем проходе.
     * Недоступные серверы перепроверяются реже.
     */
    fun startLivePing() {
        uiVisible = true
        if (liveJob?.isActive == true) return
        liveJob = scope.launch {
            val sem = Semaphore(8)
            val loops = HashMap<String, Job>()
            while (isActive) {
                val servers = allServers()
                val alive = servers.map { it.id }.toSet()
                loops.keys.filter { it !in alive }.forEach { loops.remove(it)?.cancel() }
                servers.forEachIndexed { i, s ->
                    if (loops[s.id]?.isActive != true) {
                        loops[s.id] = launch {
                            delay((i % 10) * 90L) // разводим старт, чтобы замеры не шли пачкой
                            while (isActive) {
                                sem.withPermit { measureOne(s, 1500) }
                                delay(if (pings.value[s.id] == -1) 4000L else 1000L)
                            }
                        }
                    }
                }
                delay(2000)
            }
            loops.values.forEach { it.cancel() }
        }
    }

    fun stopLivePing() {
        uiVisible = false
        liveJob?.cancel()
        liveJob = null
    }

    // ── подписки ───────────────────────────────────────────────────

    /** Вставка из буфера обмена: ссылка на подписку или список ссылок. Ошибки молча игнорируются. */
    suspend fun addFromText(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) {
            lastError.value = "буфер обмена пуст"
            return false
        }
        val first = text.lineSequence().first().trim()
        val sub = when {
            first.startsWith("http://", true) || first.startsWith("https://", true) ->
                SubscriptionLoader.fetch(first)
            text.lineSequence().any { LinkParser.isLink(it.trim()) } ->
                SubscriptionLoader.fromLinks(text)
            else -> null
        }
        if (sub == null) {
            lastError.value = SubscriptionLoader.lastError.ifBlank { "не удалось добавить подписку" }
            Logger.error("sub", "подписка не добавлена: ${lastError.value}")
            return false
        }
        Logger.info("sub", "подписка добавлена: серверов ${sub.servers.size}")
        lastError.value = ""
        val merged = sub.copy(avatar = subs.value.firstOrNull { it.id == sub.id }?.avatar)
        subs.update { list -> listOf(merged) + list.filterNot { it.id == sub.id } }
        persist()
        pingAll()
        return true
    }

    /** Тихо обновляет серверы всех подписок, добавленных по ссылке. */
    fun refreshSubscriptions() {
        scope.launch {
            subs.value.filter { it.url != null }.forEach { old ->
                val fresh = SubscriptionLoader.fetch(old.url!!, old.id)
                if (fresh != null) {
                    // аватарка принадлежит пользователю, а не подписке: при обновлении серверов не теряем её
                    subs.update { list -> list.map { if (it.id == old.id) fresh.copy(avatar = it.avatar) else it } }
                    persist()
                }
            }
            pingAll()
        }
    }

    // ── аватарки подписок ──────────────────────────────────────────

    /** Читает картинку из галереи, обрезает по центру до квадрата 256 px и сохраняет в память приложения. */
    suspend fun setAvatar(subId: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext false
        try {
            val bmp = decodeSquare(ctx, uri, 256) ?: return@withContext false
            val dir = File(ctx.filesDir, "avatars").apply { mkdirs() }
            // новое имя при каждой смене: так кэш и экран не показывают старую картинку
            val name = subId.filter { it.isLetterOrDigit() }.take(24) + "-" + System.currentTimeMillis() + ".jpg"
            File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val old = subs.value.firstOrNull { it.id == subId }?.avatar
            subs.update { list -> list.map { if (it.id == subId) it.copy(avatar = name) else it } }
            persist()
            if (old != null) {
                File(dir, old).delete()
                avatarCache.remove(old)
            }
            true
        } catch (e: Exception) {
            Logger.error("avatar", "не удалось сохранить аватарку: ${e.javaClass.simpleName}")
            false
        }
    }

    fun loadAvatar(name: String): ImageBitmap? {
        avatarCache[name]?.let { return it }
        val ctx = appContext ?: return null
        val bmp = BitmapFactory.decodeFile(File(ctx.filesDir, "avatars/$name").path) ?: return null
        return bmp.asImageBitmap().also { avatarCache[name] = it }
    }

    private fun decodeSquare(ctx: Context, uri: Uri, size: Int): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val raw = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        // фото с камеры часто хранятся «лёжа» и повёрнуты меткой EXIF
        val deg = try {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f
                    3 -> 180f
                    8 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        val side = minOf(raw.width, raw.height)
        val m = Matrix().apply {
            postRotate(deg)
            val k = size.toFloat() / side
            postScale(k, k)
        }
        return Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side, m, true)
    }
}
