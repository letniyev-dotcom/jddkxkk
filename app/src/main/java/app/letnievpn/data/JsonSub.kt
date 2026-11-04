package app.letnievpn.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Подписка в формате JSON-конфигов xray (массив конфигов или один конфиг, как отдаёт Happ-совместимая панель).
 * Из каждого конфига берём исходящий прокси и превращаем его в обычную ссылку vless/vmess/trojan/ss,
 * дальше всё работает как с обычной подпиской.
 */
object JsonSub {
    private val protos = setOf("vless", "vmess", "trojan", "shadowsocks")

    fun toLinks(body: String): List<String> {
        val t = body.trim()
        val configs = ArrayList<JSONObject>()
        try {
            if (t.startsWith("[")) {
                val a = JSONArray(t)
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { configs.add(it) }
            } else if (t.startsWith("{")) {
                configs.add(JSONObject(t))
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return configs.mapNotNull { c -> try { link(c) } catch (e: Exception) { null } }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun strOrJoin(o: JSONObject, key: String): String {
        val arr = o.optJSONArray(key)
        if (arr != null) return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.joinToString(",")
        return o.optString(key, "")
    }

    private fun link(cfg: JSONObject): String? {
        var remark = cfg.optString("remarks", "")
        if (remark.isBlank()) remark = cfg.optString("remark", "")
        if (remark.isBlank()) remark = cfg.optString("ps", "")

        val outs = cfg.optJSONArray("outbounds") ?: return null
        var ob: JSONObject? = null
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("protocol") !in protos) continue
            if (o.optString("tag") == "proxy") { ob = o; break }
            if (ob == null) ob = o
        }
        val out = ob ?: return null
        val proto = out.getString("protocol")
        val st = out.optJSONObject("settings") ?: return null

        // старый вид (vnext/servers → users) и новый «плоский»
        val node = st.optJSONArray("vnext")?.optJSONObject(0)
            ?: st.optJSONArray("servers")?.optJSONObject(0)
            ?: st
        val user = node.optJSONArray("users")?.optJSONObject(0) ?: node
        val address = node.optString("address", "")
        if (address.isBlank()) return null
        val port = node.optInt("port", 0)
        if (port <= 0) return null

        val q = LinkedHashMap<String, String>()
        val ss = out.optJSONObject("streamSettings")
        val net = (ss?.optString("network", "tcp") ?: "tcp").ifBlank { "tcp" }.lowercase()
        q["type"] = net
        val security = (ss?.optString("security", "none") ?: "none").ifBlank { "none" }
        q["security"] = security

        if (ss != null) {
            when (security) {
                "tls" -> ss.optJSONObject("tlsSettings")?.let { t ->
                    q["sni"] = t.optString("serverName", "")
                    q["fp"] = t.optString("fingerprint", "")
                    q["alpn"] = strOrJoin(t, "alpn")
                    if (t.optBoolean("allowInsecure", false)) q["allowInsecure"] = "1"
                }
                "reality" -> ss.optJSONObject("realitySettings")?.let { t ->
                    q["sni"] = t.optString("serverName", "")
                    q["fp"] = t.optString("fingerprint", "")
                    q["pbk"] = t.optString("publicKey", "").ifBlank { t.optString("password", "") }
                    q["sid"] = t.optString("shortId", "")
                    q["spx"] = t.optString("spiderX", "")
                    q["pqv"] = t.optString("mldsa65Verify", "")
                }
            }
            when (net) {
                "tcp" -> ss.optJSONObject("tcpSettings")?.optJSONObject("header")?.let { h ->
                    if (h.optString("type") == "http") {
                        q["headerType"] = "http"
                        val req = h.optJSONObject("request")
                        q["host"] = req?.optJSONObject("headers")?.let { strOrJoin(it, "Host") }.orEmpty()
                        val pa = req?.optJSONArray("path")
                        q["path"] = pa?.optString(0, "/") ?: req?.optString("path", "/").orEmpty()
                    }
                }
                "ws" -> ss.optJSONObject("wsSettings")?.let { w ->
                    q["path"] = w.optString("path", "/")
                    q["host"] = w.optString("host", "").ifBlank {
                        w.optJSONObject("headers")?.optString("Host", "").orEmpty()
                    }
                }
                "httpupgrade" -> ss.optJSONObject("httpupgradeSettings")?.let { w ->
                    q["path"] = w.optString("path", "/")
                    q["host"] = w.optString("host", "")
                }
                "xhttp", "splithttp" -> {
                    val x = ss.optJSONObject("xhttpSettings") ?: ss.optJSONObject("splithttpSettings")
                    if (x != null) {
                        q["type"] = "xhttp"
                        q["path"] = x.optString("path", "/")
                        q["host"] = x.optString("host", "")
                        q["mode"] = x.optString("mode", "")
                        // всё остальное (xmux, заголовки, padding…) xray принимает в блоке extra
                        val extra = JSONObject()
                        x.keys().forEach { k ->
                            if (k != "path" && k != "host" && k != "mode" && k != "extra") extra.put(k, x.get(k))
                        }
                        x.optJSONObject("extra")?.let { e -> e.keys().forEach { k -> extra.put(k, e.get(k)) } }
                        if (extra.length() > 0) q["extra"] = extra.toString()
                    }
                }
                "h2", "http" -> ss.optJSONObject("httpSettings")?.let { h ->
                    q["host"] = strOrJoin(h, "host")
                    q["path"] = h.optString("path", "/")
                }
                "grpc" -> ss.optJSONObject("grpcSettings")?.let { g ->
                    q["serviceName"] = g.optString("serviceName", "")
                    if (g.optBoolean("multiMode", false)) q["mode"] = "multi"
                    q["authority"] = g.optString("authority", "")
                }
            }
        }

        val hostPart = if (address.contains(':')) "[$address]" else address
        val tag = if (remark.isBlank()) "" else "#" + enc(remark)
        fun query() = q.filterValues { it.isNotBlank() }.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }

        return when (proto) {
            "vless" -> {
                val id = user.optString("id", "")
                if (id.isBlank()) return null
                val flow = user.optString("flow", "")
                if (flow.isNotBlank()) q["flow"] = flow
                val e = user.optString("encryption", "").ifBlank { node.optString("encryption", "") }
                if (e.isNotBlank()) q["encryption"] = e
                "vless://${enc(id)}@$hostPart:$port?${query()}$tag"
            }
            "trojan" -> {
                val pw = user.optString("password", "")
                if (pw.isBlank()) return null
                "trojan://${enc(pw)}@$hostPart:$port?${query()}$tag"
            }
            "shadowsocks" -> {
                val pw = user.optString("password", "")
                val method = user.optString("method", "")
                if (pw.isBlank() || method.isBlank()) return null
                "ss://${enc("$method:$pw")}@$hostPart:$port$tag"
            }
            "vmess" -> {
                val id = user.optString("id", "")
                if (id.isBlank()) return null
                val grpc = net == "grpc"
                val o = JSONObject()
                    .put("v", "2")
                    .put("ps", remark)
                    .put("add", address)
                    .put("port", port.toString())
                    .put("id", id)
                    .put("scy", user.optString("security", "auto").ifBlank { "auto" })
                    .put("net", net)
                    .put("type", if (grpc) q["mode"].orEmpty() else q["headerType"].orEmpty())
                    .put("host", q["host"].orEmpty())
                    .put("path", if (grpc) q["serviceName"].orEmpty() else q["path"].orEmpty())
                    .put("tls", if (security == "tls") "tls" else "")
                    .put("sni", q["sni"].orEmpty())
                    .put("alpn", q["alpn"].orEmpty())
                    .put("fp", q["fp"].orEmpty())
                "vmess://" + Base64.encodeToString(o.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }
            else -> null
        }
    }
}
