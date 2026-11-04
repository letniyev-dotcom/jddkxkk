package app.letnievpn.data

import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

/** Разобранная ссылка на прокси (vless / vmess / trojan / ss). */
class Proxy(
    val protocol: String,
    val host: String,
    val port: Int,
    val remark: String,
    /** uuid для vless/vmess, пароль для trojan/ss */
    val secret: String,
    /** способ шифрования: vmess security / ss method */
    val method: String?,
    val q: Map<String, String>,
)

object LinkParser {
    private val schemes = listOf("vless://", "vmess://", "trojan://", "ss://")

    fun isLink(s: String): Boolean = schemes.any { s.startsWith(it, ignoreCase = true) }

    fun parse(link: String): Proxy? = try {
        val l = link.trim()
        val p = when {
            l.startsWith("vless://", true) -> parseUri(l, "vless")
            l.startsWith("trojan://", true) -> parseUri(l, "trojan")
            l.startsWith("vmess://", true) -> parseVmess(l)
            l.startsWith("ss://", true) -> parseSs(l)
            else -> null
        }
        // Неподдерживаемые транспорты не отбрасываем: сервер остаётся в списке (его просто не выберет умный режим по пингу)
        p
    } catch (e: Exception) {
        null
    }

    fun toServer(link: String): Server? {
        val p = parse(link) ?: return null
        val (cc, name) = splitFlag(p.remark.ifBlank { p.host })
        return Server(
            id = UUID.nameUUIDFromBytes(link.trim().toByteArray()).toString().take(8),
            name = name.ifBlank { p.host },
            cc = cc,
            host = p.host,
            port = p.port,
            link = link.trim(),
        )
    }

    // ── vless / trojan ─────────────────────────────────────────────

    private class Parts(val user: String?, val host: String, val port: Int, val q: Map<String, String>, val frag: String)

    private fun dec(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    private fun splitUri(rest: String): Parts {
        var s = rest
        var frag = ""
        val h = s.indexOf('#')
        if (h >= 0) {
            frag = dec(s.substring(h + 1))
            s = s.substring(0, h)
        }
        var query = ""
        val qi = s.indexOf('?')
        if (qi >= 0) {
            query = s.substring(qi + 1)
            s = s.substring(0, qi)
        }
        s = s.trimEnd('/')
        val at = s.lastIndexOf('@')
        val user = if (at >= 0) s.substring(0, at) else null
        val hp = if (at >= 0) s.substring(at + 1) else s
        val host: String
        val port: Int
        if (hp.startsWith("[")) {
            val end = hp.indexOf(']')
            host = hp.substring(1, end)
            port = hp.substring(end + 2).toInt()
        } else {
            val c = hp.lastIndexOf(':')
            host = hp.substring(0, c)
            port = hp.substring(c + 1).toInt()
        }
        val q = HashMap<String, String>()
        query.split('&').forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) q[kv.substring(0, i)] = dec(kv.substring(i + 1))
        }
        return Parts(user, host, port, q, frag)
    }

    private fun parseUri(link: String, protocol: String): Proxy {
        val u = splitUri(link.substring(link.indexOf("://") + 3))
        val q = HashMap(u.q)
        if (protocol == "trojan" && q["security"].isNullOrBlank()) q["security"] = "tls"
        return Proxy(
            protocol = protocol,
            host = u.host,
            port = u.port,
            remark = u.frag,
            secret = dec(u.user.orEmpty()),
            method = q["encryption"],
            q = q,
        )
    }

    // ── vmess ──────────────────────────────────────────────────────

    private fun parseVmess(link: String): Proxy {
        val body = link.substring("vmess://".length).trim()
        val o = JSONObject(String(b64(body), Charsets.UTF_8))
        val q = HashMap<String, String>()
        val net = o.optString("net", "tcp").ifBlank { "tcp" }
        q["type"] = net
        val typ = o.optString("type", "")
        if (net == "grpc") q["mode"] = typ else q["headerType"] = typ
        q["host"] = o.optString("host", "")
        val path = o.optString("path", "")
        if (net == "grpc") q["serviceName"] = path else q["path"] = path
        q["security"] = if (o.optString("tls", "") == "tls") "tls" else ""
        q["sni"] = o.optString("sni", "")
        q["alpn"] = o.optString("alpn", "")
        q["fp"] = o.optString("fp", "")
        return Proxy(
            protocol = "vmess",
            host = o.getString("add"),
            port = o.get("port").toString().toInt(),
            remark = o.optString("ps", ""),
            secret = o.getString("id"),
            method = o.optString("scy", "auto").ifBlank { "auto" },
            q = q,
        )
    }

    // ── shadowsocks ────────────────────────────────────────────────

    private fun parseSs(link: String): Proxy {
        var s = link.substring("ss://".length)
        var frag = ""
        val h = s.indexOf('#')
        if (h >= 0) {
            frag = dec(s.substring(h + 1))
            s = s.substring(0, h)
        }
        val qi = s.indexOf('?')
        if (qi >= 0) s = s.substring(0, qi)
        s = s.trimEnd('/')
        val userInfo: String
        val hostPort: String
        if (s.contains('@')) {
            userInfo = s.substringBeforeLast('@')
            hostPort = s.substringAfterLast('@')
        } else {
            val whole = String(b64(s), Charsets.UTF_8)
            userInfo = whole.substringBeforeLast('@')
            hostPort = whole.substringAfterLast('@')
        }
        val ui = dec(userInfo).let { if (it.contains(':')) it else String(b64(it), Charsets.UTF_8) }
        val method = ui.substringBefore(':')
        val password = ui.substringAfter(':')
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            host = hostPort.substring(1, end)
            port = hostPort.substring(end + 2).toInt()
        } else {
            val c = hostPort.lastIndexOf(':')
            host = hostPort.substring(0, c)
            port = hostPort.substring(c + 1).toInt()
        }
        return Proxy("shadowsocks", host, port, frag, password, method, emptyMap())
    }

    private fun b64(s: String): ByteArray {
        val n = s.trim().replace('+', '-').replace('/', '_').trimEnd('=')
        return Base64.decode(n, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    /** Достаёт эмодзи-флаг из названия: «🇫🇮 Финляндия» → ("FI", "Финляндия"). */
    private fun splitFlag(remark: String): Pair<String, String> {
        val cps = remark.codePoints().toArray()
        var i = 0
        while (i < cps.size - 1) {
            val a = cps[i]
            val b = cps[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                val cc = "" + (a - 0x1F1E6 + 'A'.code).toChar() + (b - 0x1F1E6 + 'A'.code).toChar()
                val rest = StringBuilder()
                cps.forEachIndexed { k, cp -> if (k != i && k != i + 1) rest.appendCodePoint(cp) }
                return cc to rest.toString().trim().trimStart('-', '|', '·', ' ').trim()
            }
            i++
        }
        return "" to remark.trim()
    }
}
