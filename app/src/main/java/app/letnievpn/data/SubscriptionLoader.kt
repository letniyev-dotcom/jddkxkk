package app.letnievpn.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Загружает подписку по ссылке (список ссылок vless/vmess/trojan/ss, обычно в base64). */
object SubscriptionLoader {

    /** Причина последней неудачи (без ссылок и ключей) — показывается в пустом списке. */
    @Volatile
    var lastError: String = ""

    // Панели отдают разный формат в зависимости от User-Agent: пробуем несколько привычных клиентов
    private val agents = listOf("Happ/4.9.0", "v2rayNG/1.10.0", "v2rayN/7.0.0")

    private class Raw(val code: Int, val body: String, val title: String?, val userInfo: String?)

    private fun get(url: String, agent: String): Raw {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", agent)
            c.setRequestProperty("Accept", "*/*")
            // Те же заголовки устройства, что отправляет Happ: панели с лимитом устройств без них отказывают
            c.setRequestProperty("x-hwid", Repo.hwid())
            c.setRequestProperty("x-device-os", "Android")
            c.setRequestProperty("x-ver-os", android.os.Build.VERSION.RELEASE ?: "")
            c.setRequestProperty("x-device-model", android.os.Build.MODEL ?: "")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Raw(code, body, c.getHeaderField("profile-title"), c.getHeaderField("subscription-userinfo"))
        } finally {
            c.disconnect()
        }
    }

    suspend fun fetch(url: String, keepId: String? = null): Sub? = withContext(Dispatchers.IO) {
        var reason = ""
        // JSON-конфиги xray — запасной вариант: если другой User-Agent отдаёт обычные ссылки, берём их
        var jsonRaw: Raw? = null
        var jsonLinks: List<String> = emptyList()

        fun build(r: Raw, links: List<String>): Sub? {
            val servers = links.mapNotNull { LinkParser.toServer(it) }.distinctBy { it.id }
            if (servers.isEmpty()) {
                reason = "ссылки есть (${links.size}), но ни одна не разобралась: ${links.map { it.substringBefore("://") }.distinct().joinToString()}"
                return null
            }
            val name = decodeTitle(r.title)
                ?: runCatching { URL(url).host.removePrefix("www.") }.getOrNull()
                ?: "Подписка"
            lastError = ""
            return Sub(
                id = keepId ?: UUID.nameUUIDFromBytes(url.toByteArray()).toString().take(8),
                name = name,
                url = url,
                info = formatInfo(r.userInfo),
                servers = servers,
            )
        }

        for (agent in agents) {
            try {
                val r = get(url, agent)
                Logger.info("sub", "запрос как $agent: код ${r.code}, ответ ${r.body.length} симв.")
                if (r.code !in 200..299) {
                    reason = "сервер ответил кодом ${r.code}"
                    continue
                }
                val links = extractLinks(r.body)
                if (links.isEmpty()) {
                    val fromJson = JsonSub.toLinks(r.body)
                    if (fromJson.isNotEmpty()) {
                        Logger.info("sub", "ответ — JSON-конфиги xray, серверов: ${fromJson.size}")
                        if (jsonLinks.isEmpty()) {
                            jsonRaw = r
                            jsonLinks = fromJson
                        }
                    } else {
                        reason = describe(r.body)
                        Logger.info("sub", "в ответе нет ссылок: $reason")
                    }
                    continue
                }
                build(r, links)?.let { return@withContext it }
            } catch (e: Exception) {
                reason = "ошибка сети: ${e.javaClass.simpleName} ${scrub(e.message)}"
                Logger.error("sub", "запрос как $agent: $reason")
            }
        }
        val jr = jsonRaw
        if (jr != null) {
            build(jr, jsonLinks)?.let { return@withContext it }
        }
        lastError = reason.ifBlank { "не удалось загрузить подписку" }
        null
    }

    /** Ссылки, вставленные из буфера прямо списком (без адреса подписки). */
    fun fromLinks(text: String): Sub? {
        val servers = extractLinks(text).mapNotNull { LinkParser.toServer(it) }.distinctBy { it.id }
        if (servers.isEmpty()) {
            lastError = "в буфере нет ни одной рабочей ссылки"
            return null
        }
        lastError = ""
        return Sub(
            id = "clip-" + UUID.nameUUIDFromBytes(text.trim().toByteArray()).toString().take(8),
            name = "Из буфера",
            url = null,
            info = "",
            servers = servers,
        )
    }

    /** Что за ответ пришёл, если в нём нет ссылок. Содержимое не показываем — там ключи. */
    private fun describe(body: String): String {
        val t = body.trimStart()
        return when {
            t.isEmpty() -> "сервер вернул пустой ответ"
            t.startsWith("<") -> "сервер вернул веб-страницу, а не подписку"
            t.startsWith("{") || t.startsWith("[") -> "JSON-конфиг без поддерживаемых серверов (vless/vmess/trojan/ss)"
            t.contains("proxies:") -> "формат Clash/YAML (пока не поддерживается)"
            else -> {
                val first = t.lineSequence().first().trim()
                val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]{0,15})://").find(first)?.groupValues?.get(1)
                if (scheme != null) "ссылки неизвестного типа «$scheme://» (длина ${t.length})"
                else "в ответе нет ссылок vless/vmess/trojan/ss (длина ${t.length})"
            }
        }
    }

    private fun scrub(m: String?): String =
        (m ?: "").replace(Regex("https?://\\S+"), "<url>").take(80)

    private fun extractLinks(body: String): List<String> {
        fun lines(t: String) = t.lineSequence().map { it.trim() }.filter { LinkParser.isLink(it) }.toList()
        val direct = lines(body)
        if (direct.isNotEmpty()) return direct
        return try {
            val clean = body.trim().replace("\\s".toRegex(), "").replace('+', '-').replace('/', '_').trimEnd('=')
            lines(String(Base64.decode(clean, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decodeTitle(h: String?): String? {
        if (h.isNullOrBlank()) return null
        if (h.startsWith("base64:")) {
            return try {
                String(Base64.decode(h.removePrefix("base64:"), Base64.DEFAULT), Charsets.UTF_8).ifBlank { null }
            } catch (e: Exception) {
                null
            }
        }
        return h
    }

    /** «upload=..; download=..; total=..; expire=..» → «12 ГБ из 50 ГБ · до 14 дек.» */
    private fun formatInfo(h: String?): String {
        if (h.isNullOrBlank()) return ""
        val m = h.split(';').mapNotNull {
            val kv = it.trim().split('=')
            if (kv.size == 2) kv[0].trim() to (kv[1].trim().toLongOrNull() ?: 0L) else null
        }.toMap()
        val parts = ArrayList<String>()
        val total = m["total"] ?: 0L
        if (total > 0) {
            val used = (m["upload"] ?: 0L) + (m["download"] ?: 0L)
            parts.add("${gb(used)} из ${gb(total)}")
        }
        val expire = m["expire"] ?: 0L
        if (expire > 0) {
            val f = SimpleDateFormat("d MMM", Locale("ru"))
            parts.add("до " + f.format(Date(expire * 1000)))
        }
        return parts.joinToString(" · ")
    }

    private fun gb(bytes: Long): String {
        val g = bytes / 1073741824.0
        return (if (g >= 10) String.format(Locale.ROOT, "%.0f", g) else String.format(Locale.ROOT, "%.1f", g)) + " ГБ"
    }
}
