package app.letnievpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.letnievpn.MainActivity
import app.letnievpn.R
import app.letnievpn.data.LinkParser
import app.letnievpn.data.Logger
import app.letnievpn.data.Repo
import app.letnievpn.data.Server
import app.letnievpn.data.VpnStatus
import app.letnievpn.data.XrayConfig
import go.Seq
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Настоящий VPN: создаёт TUN-интерфейс и отдаёт его дескриптор ядру xray (libv2ray),
 * которое само читает пакеты из туннеля и гонит их через выбранный сервер.
 */
class LetnieVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var job: Job? = null
    /** Следит за трафиком и при сбое переключает сервер (см. startMonitor). */
    private var monitor: Job? = null

    /** Ядро одно, а запускать его могут из разных корутин: все запуски и остановки идут под замком. */
    private val coreLock = Any()

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, msg: String?): Long = 0
    }

    private val controller: CoreController by lazy { Libv2ray.newCoreController(callback) }

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Seq.setContext(applicationContext)
        Libv2ray.initCoreEnv(filesDir.absolutePath, Repo.xudpKey())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground(null)
        Logger.info("vpn", "команда: ${intent?.action ?: "перезапуск системой"}, умное=${Repo.smart.value}, серверов=${Repo.allServers().size}")
        if (intent?.action == ACTION_STOP) {
            Repo.setDesired(false)
            shutdown()
            return START_NOT_STICKY
        }
        // ACTION_START, а также перезапуск системой (intent == null) — подключаемся
        Repo.setDesired(true)
        connect()
        return START_STICKY
    }

    override fun onRevoke() {
        // пользователь отозвал VPN в системных настройках
        Repo.setDesired(false)
        shutdown()
    }

    override fun onDestroy() {
        job?.cancel()
        monitor?.cancel()
        stopCore()
        closeTun()
        Repo.tunnelDelay.value = -1
        Repo.vpn.value = VpnStatus.OFF
        Repo.vpnServer.value = null
        super.onDestroy()
    }

    // ── подключение ────────────────────────────────────────────────

    private fun connect() {
        job?.cancel()
        monitor?.cancel()
        Repo.vpn.value = VpnStatus.CONNECTING
        Repo.connError.value = ""
        job = Repo.scope.launch {
            try {
                val order = candidates()
                if (order.isEmpty()) {
                    fail("нет серверов — добавьте подписку")
                    return@launch
                }
                Logger.info("vpn", "кандидаты: " + order.take(4).joinToString { "${it.name} ${Repo.pings.value[it.id]?.takeIf { p -> p > 0 } ?: "—"} мс" })
                ensureTun()
                val smart = Repo.smart.value
                // Ручной режим — только выбранный сервер. Умный — до пяти самых быстрых; каждый проверяется
                // настоящими запросами к YouTube, Telegram, Google и др. (см. SERVICES). Берём первый, где работают
                // все важные сервисы; если такого нет — тот, где работает больше всего.
                val tries = if (smart) order.take(5) else order.take(1)
                var connected: Server? = null
                var firstStarted: Server? = null
                var bestPartial: Pair<Server, ProbeResult>? = null
                var err = ""
                for (s in tries) {
                    val e = startCore(s)
                    ensureActive()
                    if (e != null) {
                        Logger.info("vpn", "${s.name}: не запустился — $e")
                        err = e
                        continue
                    }
                    if (firstStarted == null) firstStarted = s
                    if (!smart) {
                        connected = s
                        break
                    }
                    delay(400) // даём туннелю подняться
                    val r = probeServices()
                    ensureActive()
                    markHealth(s, r)
                    Logger.info("vpn", "${s.name}: ${r.summary()}")
                    if (r.ok.isEmpty()) {
                        err = "через ${s.name} нет трафика"
                        continue
                    }
                    if (r.allCritical) {
                        Repo.tunnelDelay.value = r.ms
                        connected = s
                        break
                    }
                    if (bestPartial == null || r.score > bestPartial.second.score) bestPartial = s to r
                }
                if (connected == null && bestPartial != null) {
                    val (ps, pr) = bestPartial
                    Logger.info("vpn", "ни на одном сервере не работают все сервисы, беру лучший: ${ps.name} (${pr.summary()})")
                    if (startCore(ps) == null) {
                        Repo.tunnelDelay.value = pr.ms
                        connected = ps
                    }
                    ensureActive()
                }
                if (connected == null && firstStarted != null) {
                    // Проверочные адреса могли быть просто недоступны: остаёмся на самом быстром,
                    // а монитор продолжит проверять и при необходимости переключит.
                    Logger.info("vpn", "ни один сервер не подтвердил трафик — остаюсь на ${firstStarted.name}")
                    if (startCore(firstStarted) == null) connected = firstStarted
                    ensureActive()
                }
                if (connected == null) {
                    fail(err.ifBlank { "не удалось запустить ни один сервер" })
                    return@launch
                }
                Logger.info("vpn", "подключено: ${connected.name}")
                Repo.vpnServer.value = connected.id
                Repo.vpn.value = VpnStatus.ON
                enterForeground(connected.name)
                Repo.refreshPings()
                startMonitor()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("${e.javaClass.simpleName}: ${scrub(e.message)}")
            }
        }
    }

    /**
     * Постоянный контроль, пока VPN включён. Раз в несколько секунд через туннель идёт настоящий запрос.
     * В умном режиме два сбоя подряд запускают восстановление (см. recover). В ручном сервер не меняется,
     * задержка только показывается на экране.
     */
    private fun startMonitor() {
        monitor?.cancel()
        monitor = Repo.scope.launch {
            var bad = 0
            var svcBad = 0
            var lastSearch = android.os.SystemClock.elapsedRealtime()
            var lastHealth = lastSearch
            delay(2000)
            while (isActive) {
                val smart = Repo.smart.value
                if (smart || Repo.uiVisible) {
                    val ms = measure()
                    if (ms >= 0) {
                        bad = 0
                        Repo.tunnelDelay.value = ms
                    } else {
                        bad++
                        Repo.tunnelDelay.value = -1
                        Logger.info("vpn", "проверка трафика: нет ответа ($bad)")
                        if (smart && bad >= 2) {
                            bad = 0
                            recover()
                        }
                    }
                }
                val now = android.os.SystemClock.elapsedRealtime()
                // Умный режим: сервисы (YouTube, Telegram…) проверяются отдельно. Один сбой — перепроверка через 5 с,
                // второй подряд — переезд на сервер, где всё работает.
                val healthEvery = if (Repo.uiVisible) HEALTH_EVERY_MS else HEALTH_EVERY_BG_MS
                if (smart && bad == 0 && (svcBad > 0 || now - lastHealth >= healthEvery)) {
                    lastHealth = now
                    val cur = Repo.allServers().firstOrNull { it.id == Repo.vpnServer.value }
                    val r = probeServices()
                    if (cur != null) markHealth(cur, r)
                    if (r.allCritical || r.ok.isEmpty()) {
                        // r.ok.isEmpty() — сети нет совсем, это дело базовой проверки трафика выше
                        svcBad = 0
                    } else {
                        svcBad++
                        Logger.info("vpn", "сервисы: ${r.summary()} ($svcBad)")
                        if (svcBad >= 2) {
                            svcBad = 0
                            switchForServices(cur, r)
                        }
                    }
                }
                // Умный режим: раз в минуту перемеряем все серверы всех подписок и уходим на самый быстрый
                if (smart && bad == 0 && svcBad == 0 && now - lastSearch >= SEARCH_EVERY_MS) {
                    lastSearch = now
                    if (!Repo.uiVisible) withTimeoutOrNull(6000) { Repo.pingAll(1500, quiet = true) }
                    upgrade()
                }
                delay(if (bad > 0) 1500L else if (svcBad > 0) 5000L else if (Repo.uiVisible) 2500L else 15000L)
            }
        }
    }

    /**
     * Сервисы не работают на текущем сервере. Пробуем самые быстрые из остальных и берём первый, где работают
     * все важные сервисы. Если такого нет — тот, где работает больше всего, но только если он лучше текущего.
     */
    private suspend fun switchForServices(cur: Server?, curResult: ProbeResult) {
        withTimeoutOrNull(6000) { Repo.pingAll(1500, quiet = true) }
        val order = ranked().filter { it.id != cur?.id }.take(4)
        Logger.info("vpn", "сервисы не работают (${curResult.summary()}), ищу другой сервер: " + order.joinToString { it.name })
        var best: Pair<Server, ProbeResult>? = null
        for (s in order) {
            currentCoroutineContext().ensureActive()
            if (startCore(s) != null) continue
            currentCoroutineContext().ensureActive()
            delay(500)
            val r = probeServices()
            currentCoroutineContext().ensureActive()
            markHealth(s, r)
            Logger.info("vpn", "${s.name}: ${r.summary()}")
            if (r.allCritical) {
                Repo.vpnServer.value = s.id
                enterForeground(s.name)
                Repo.tunnelDelay.value = r.ms
                Logger.info("vpn", "переключено на ${s.name}: все сервисы работают")
                return
            }
            if (r.ok.isNotEmpty() && (best == null || r.score > best.second.score)) best = s to r
        }
        val target = best?.takeIf { it.second.score > curResult.score }
        val back = target?.first ?: cur
        if (back != null) {
            startCore(back)
            currentCoroutineContext().ensureActive()
            Repo.vpnServer.value = back.id
            enterForeground(back.name)
            if (target != null) {
                Repo.tunnelDelay.value = target.second.ms
                Logger.info("vpn", "полностью рабочего сервера нет, перешёл на лучший: ${back.name}")
            } else {
                Logger.info("vpn", "лучше текущего ничего нет, остаюсь на ${back.name}")
            }
        }
    }

    /**
     * Ищет сервер быстрее текущего среди всех подписок. Переключение рвёт открытые соединения,
     * поэтому уходим только при заметной выгоде: пинг ниже примерно на треть и минимум на 15 мс.
     * Новый сервер должен подтвердить трафик, иначе возвращаемся на прежний.
     */
    private suspend fun upgrade() {
        val curId = Repo.vpnServer.value ?: return
        val pg = Repo.pings.value
        val curPing = pg[curId]?.takeIf { it > 0 } ?: return
        val cand = ranked().firstOrNull() ?: return
        if (cand.id == curId) return
        val candPing = pg[cand.id]?.takeIf { it > 0 } ?: return
        if (candPing * 10 > curPing * 7 || curPing - candPing < 15) return
        val prev = Repo.allServers().firstOrNull { it.id == curId } ?: return
        Logger.info("vpn", "нашёлся быстрее: ${cand.name} $candPing мс вместо ${prev.name} $curPing мс, переключаюсь")
        if (startCore(cand) == null) {
            currentCoroutineContext().ensureActive()
            delay(600)
            val r = probeServices()
            currentCoroutineContext().ensureActive()
            markHealth(cand, r)
            if (r.allCritical) {
                Repo.vpnServer.value = cand.id
                enterForeground(cand.name)
                Repo.tunnelDelay.value = r.ms
                Logger.info("vpn", "переключено на ${cand.name}, ${r.ms} мс, сервисы: ${r.summary()}")
                return
            }
            Logger.info("vpn", "${cand.name} быстрее, но сервисы не все работают (${r.summary()})")
        }
        currentCoroutineContext().ensureActive()
        Logger.info("vpn", "${cand.name} не подошёл, возвращаюсь на ${prev.name}")
        startCore(prev)
    }

    /**
     * Трафик пропал. Сначала перезапускаем ядро на том же сервере (после смены Wi-Fi/LTE этого достаточно),
     * если не помогло — перемеряем пинги и по очереди пробуем самые быстрые из остальных.
     */
    private suspend fun recover() {
        val curId = Repo.vpnServer.value
        val cur = Repo.allServers().firstOrNull { it.id == curId }
        if (cur != null) {
            Logger.info("vpn", "трафика нет — перезапускаю ядро на ${cur.name}")
            if (startCore(cur) == null) {
                currentCoroutineContext().ensureActive()
                delay(1500)
                val ms = measure()
                if (ms >= 0) {
                    Logger.info("vpn", "после перезапуска трафик идёт, $ms мс")
                    Repo.tunnelDelay.value = ms
                    return
                }
            }
        }
        withTimeoutOrNull(6000) { Repo.pingAll(1500, quiet = true) }
        val order = ranked().filter { it.id != curId }.take(4)
        Logger.info("vpn", "ищу другой сервер: " + order.joinToString { "${it.name} ${Repo.pings.value[it.id]?.takeIf { p -> p > 0 } ?: "—"} мс" })
        for (s in order) {
            currentCoroutineContext().ensureActive()
            if (startCore(s) != null) continue
            currentCoroutineContext().ensureActive()
            Repo.vpnServer.value = s.id
            enterForeground(s.name)
            delay(600)
            val ms = measure()
            if (ms >= 0) {
                Logger.info("vpn", "переключено на ${s.name}, $ms мс")
                Repo.tunnelDelay.value = ms
                return
            }
            Logger.info("vpn", "${s.name}: трафик не идёт")
        }
        // Ничего не помогло (например, пропала сеть). Пауза, чтобы не дёргать ядро без конца.
        Logger.error("vpn", "ни один сервер не отвечает, повторю через 30 с")
        delay(30000)
    }

    /**
     * Серверы по возрастанию пинга. Если есть хотя бы один отвечающий — недоступные не берём,
     * иначе время уйдёт на заведомо мёртвые.
     */
    private fun ranked(): List<Server> {
        val pg = Repo.pings.value
        val all = Repo.allServers()
        val alive = all.filter { (pg[it.id] ?: -1) > 0 }
        // серверы, на которых недавно не работали важные сервисы, идут в конец — их пробуем в последнюю очередь
        return (alive.ifEmpty { all }).sortedWith(
            compareBy<Server>({ if (isBad(it)) 1 else 0 }, { pg[it.id]?.takeIf { p -> p > 0 } ?: Int.MAX_VALUE })
        )
    }

    // ── проверка сервисов ──────────────────────────────────────────

    private class Service(val name: String, val url: String, val critical: Boolean)

    private class ProbeResult(
        val ok: List<String>,
        val failed: List<String>,
        val failedCritical: List<String>,
        val ms: Int,
    ) {
        val allCritical get() = ok.isNotEmpty() && failedCritical.isEmpty()
        /** Важные сервисы весят больше обычных. */
        val score: Int get() = SERVICES.filter { it.name in ok }.sumOf { if (it.critical) 10 else 1 }
        fun summary(): String =
            if (ok.isEmpty()) "ничего не отвечает"
            else "работают: ${ok.joinToString()}" + if (failed.isEmpty()) "" else "; не работают: ${failed.joinToString()}"
    }

    /** Когда и с каким итогом сервер проверяли в последний раз. */
    private val health = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    private fun markHealth(s: Server, r: ProbeResult) {
        if (r.ok.isEmpty()) return // сети нет совсем — о сервере это ничего не говорит
        health[s.id] = android.os.SystemClock.elapsedRealtime() to r.allCritical
    }

    private fun isBad(s: Server): Boolean {
        val h = health[s.id] ?: return false
        return !h.second && android.os.SystemClock.elapsedRealtime() - h.first < BAD_MEMORY_MS
    }

    /** Один запрос к адресу через туннель, с одной повторной попыткой. Задержка в мс или -1. */
    private suspend fun probeOne(url: String): Int {
        repeat(2) { attempt ->
            val test = Repo.scope.async {
                try {
                    if (controller.isRunning) controller.measureDelay(url).toInt() else -1
                } catch (e: Exception) {
                    -1
                }
            }
            val ms = withTimeoutOrNull(4500) { test.await() } ?: -1
            if (ms >= 0) return ms.coerceAtLeast(1)
            if (attempt == 0) delay(250)
        }
        return -1
    }

    /** Все сервисы из SERVICES проверяются одновременно настоящими запросами через текущий сервер. */
    private suspend fun probeServices(): ProbeResult = coroutineScope {
        val res = SERVICES.map { svc -> async { svc to probeOne(svc.url) } }.awaitAll()
        val okList = res.filter { it.second >= 0 }
        ProbeResult(
            ok = okList.map { it.first.name },
            failed = res.filter { it.second < 0 }.map { it.first.name },
            failedCritical = res.filter { it.second < 0 && it.first.critical }.map { it.first.name },
            ms = okList.map { it.second }.sorted().let { if (it.isEmpty()) -1 else it[it.size / 2] },
        )
    }

    /** Один настоящий запрос через туннель. Возвращает задержку в мс или -1, если ответа нет. */
    private suspend fun measure(): Int {
        for (url in CHECK_URLS) {
            val test = Repo.scope.async {
                try {
                    if (controller.isRunning) controller.measureDelay(url).toInt() else -1
                } catch (e: Exception) {
                    -1
                }
            }
            val ms = withTimeoutOrNull(5000) { test.await() } ?: -1
            if (ms >= 0) return ms.coerceAtLeast(1)
        }
        return -1
    }

    private fun fail(reason: String) {
        Logger.error("vpn", "не подключилось: $reason")
        Repo.connError.value = reason
        shutdown()
    }

    private fun scrub(m: String?): String =
        (m ?: "").replace(Regex("https?://\\S+"), "<url>").replace('\n', ' ').take(140)

    /** Упорядоченный список серверов: сначала выбранный (или самый быстрый), дальше по пингу. */
    private suspend fun candidates(): List<Server> {
        val all = Repo.allServers()
        if (all.isEmpty()) return emptyList()
        if (Repo.smart.value) {
            // пинги свежее трёх секунд берём как есть, иначе перемеряем
            val age = android.os.SystemClock.elapsedRealtime() - Repo.lastPingAt
            if (age > 3000) withTimeoutOrNull(6000) { Repo.pingAll(1500, quiet = true) }
            return ranked()
        }
        val chosen = Repo.current() ?: return ranked()
        return listOf(chosen) + ranked().filter { it.id != chosen.id }
    }

    /** Запускает ядро с сервером. null — успех, иначе причина неудачи. */
    private fun startCore(server: Server): String? {
        val proxy = LinkParser.parse(server.link) ?: return "ссылка сервера не разобралась"
        val ip = try {
            resolve(proxy.host)
        } catch (e: Exception) {
            return "не удалось определить адрес сервера (${e.javaClass.simpleName})"
        }
        return try {
            val config = XrayConfig.build(proxy, ip, Logger.xrayPath)
            Logger.info("core", "запуск: ${proxy.protocol}, транспорт ${proxy.q["type"] ?: "tcp"}, защита ${proxy.q["security"] ?: "нет"}, порт ${proxy.port}")
            Logger.info("core", "конфиг: ${Logger.redact(config)}")
            synchronized(coreLock) {
                stopCore()
                Repo.tunnelDelay.value = -1
                controller.startLoop(config, tun!!.fd)
                if (controller.isRunning) null else "ядро не запустилось"
            }
        } catch (e: Exception) {
            "ядро: ${scrub(e.message).ifBlank { e.javaClass.simpleName }}"
        }
    }

    private fun stopCore() {
        synchronized(coreLock) {
            try {
                if (controller.isRunning) controller.stopLoop()
            } catch (e: Exception) {
                // уже остановлено
            }
        }
    }

    private fun resolve(host: String): String {
        if (XrayConfig.isIp(host)) return host
        val all = InetAddress.getAllByName(host)
        return (all.firstOrNull { it is Inet4Address } ?: all.first()).hostAddress!!
    }

    private fun ensureTun() {
        if (tun != null) return
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("10.10.14.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
        // Сам клиент ходит к серверу мимо туннеля, иначе соединение с сервером зациклится
        try {
            b.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            // пакет всегда существует
        }
        // Приложения-исключения ходят напрямую, мимо VPN
        Repo.excluded.value.forEach { pkg ->
            try {
                b.addDisallowedApplication(pkg)
            } catch (e: Exception) {
                // приложение удалили — пропускаем
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        tun = b.establish() ?: error("не удалось создать VPN-интерфейс")
    }

    private fun closeTun() {
        try {
            tun?.close()
        } catch (e: Exception) {
            // уже закрыт
        }
        tun = null
    }

    private fun shutdown() {
        Logger.info("vpn", "отключение")
        job?.cancel()
        monitor?.cancel()
        Repo.scope.launch {
            stopCore()
            closeTun()
            Repo.tunnelDelay.value = -1
            Repo.vpn.value = VpnStatus.OFF
            Repo.vpnServer.value = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ── уведомление ────────────────────────────────────────────────

    private fun enterForeground(serverName: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_vpn), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = notification(serverName)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notification(serverName: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LetnieVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(serverName ?: getString(R.string.status_connecting))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "app.letnievpn.START"
        const val ACTION_STOP = "app.letnievpn.STOP"

        /** Проверка трафика: если не ответил первый адрес, пробуем второй (один из них мог быть недоступен). */
        private val CHECK_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        )
        /**
         * Какие сервисы должны работать через сервер. critical — без них сервер не годится (если есть лучше).
         * Список можно менять: достаточно адреса, который отвечает 200 или 204.
         */
        private val SERVICES = listOf(
            Service("YouTube", "https://www.youtube.com/generate_204", true),
            Service("Telegram", "https://web.telegram.org/", true),
            Service("Google", "https://www.gstatic.com/generate_204", true),
            Service("Instagram", "https://www.instagram.com/", false),
            Service("Discord", "https://discord.com/api/v9/gateway", false),
            Service("Cloudflare", "https://cp.cloudflare.com/generate_204", false),
        )
        /** Как часто проверять сервисы на текущем сервере: при открытом приложении и в фоне. */
        private const val HEALTH_EVERY_MS = 45_000L
        private const val HEALTH_EVERY_BG_MS = 120_000L
        /** Сколько помнить, что на сервере не работали важные сервисы. */
        private const val BAD_MEMORY_MS = 10 * 60_000L
        /** Как часто в умном режиме искать сервер быстрее текущего. */
        private const val SEARCH_EVERY_MS = 60_000L
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, LetnieVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            // доставляем команду уже работающему сервису, новый не поднимаем
            context.startService(Intent(context, LetnieVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
