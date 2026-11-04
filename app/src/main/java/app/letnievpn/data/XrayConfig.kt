package app.letnievpn.data

import org.json.JSONArray
import org.json.JSONObject

/** Собирает JSON-конфиг xray: входящий TUN (дескриптор отдаёт VpnService) и один исходящий прокси. */
object XrayConfig {

    fun isIp(host: String): Boolean =
        host.contains(':') || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)

    /**
     * @param address адрес для подключения (уже разрешённый в IP, чтобы не зависеть от DNS внутри туннеля)
     */
    fun build(p: Proxy, address: String, logPath: String = ""): String {
        val root = JSONObject()
        val log = JSONObject().put("loglevel", "warning")
        if (logPath.isNotBlank()) log.put("access", "none").put("error", logPath)
        root.put("log", log)

        root.put(
            "inbounds",
            JSONArray().put(
                JSONObject()
                    .put("tag", "tun")
                    .put("protocol", "tun")
                    .put(
                        "settings",
                        JSONObject().put("name", "xray0").put("MTU", 1500).put("userLevel", 8)
                    )
                    .put(
                        "sniffing",
                        JSONObject()
                            .put("enabled", true)
                            .put("destOverride", JSONArray(listOf("http", "tls", "quic")))
                    )
            )
        )

        val outbounds = JSONArray()
        outbounds.put(proxyOutbound(p, address))
        outbounds.put(
            JSONObject()
                .put("tag", "direct")
                .put("protocol", "freedom")
                .put(
                    "streamSettings",
                    JSONObject().put(
                        "sockopt",
                        JSONObject()
                            .put("domainStrategy", "UseIP")
                            .put(
                                "happyEyeballs",
                                JSONObject().put("tryDelayMs", 250).put("interleave", 2)
                            )
                    )
                )
        )
        outbounds.put(
            JSONObject()
                .put("tag", "block")
                .put("protocol", "blackhole")
                .put("settings", JSONObject().put("response", JSONObject().put("type", "http")))
        )
        root.put("outbounds", outbounds)

        // Локальная сеть идёт напрямую, всё остальное — через прокси (первый outbound)
        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "AsIs")
                .put(
                    "rules",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "field")
                            .put(
                                "ip",
                                JSONArray(
                                    listOf(
                                        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                                        "169.254.0.0/16", "224.0.0.0/4"
                                    )
                                )
                            )
                            .put("outboundTag", "direct")
                    )
                )
        )
        root.put("dns", JSONObject().put("hosts", JSONObject()).put("servers", JSONArray()))
        return root.toString()
    }

    private fun proxyOutbound(p: Proxy, address: String): JSONObject {
        val s = JSONObject().put("address", address).put("port", p.port).put("level", 8)
        when (p.protocol) {
            "vless" -> {
                s.put("id", p.secret)
                s.put("encryption", p.method?.ifBlank { null } ?: "none")
                p.q["flow"]?.takeIf { it.isNotBlank() }?.let { s.put("flow", it) }
            }
            "vmess" -> {
                s.put("id", p.secret)
                s.put("security", p.method ?: "auto")
            }
            "trojan" -> s.put("password", p.secret)
            "shadowsocks" -> {
                s.put("password", p.secret)
                s.put("method", p.method.orEmpty())
            }
        }
        val out = JSONObject().put("tag", "proxy").put("protocol", p.protocol).put("settings", s)
        if (p.protocol != "shadowsocks") out.put("streamSettings", stream(p))
        out.put("mux", JSONObject().put("enabled", false))
        return out
    }

    private fun stream(p: Proxy): JSONObject {
        val q = p.q
        val domain = if (isIp(p.host)) null else p.host
        val net = (q["type"] ?: "tcp").lowercase().let { if (it == "http") "h2" else if (it == "splithttp") "xhttp" else it }
        val host = q["host"].orEmpty()
        val path = q["path"]?.ifBlank { null }
        val s = JSONObject().put("network", net)

        when (net) {
            "tcp" -> {
                val header = JSONObject().put("type", "none")
                if (q["headerType"] == "http") {
                    val hosts = host.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    header.put("type", "http").put(
                        "request",
                        JSONObject()
                            .put("version", "1.1")
                            .put("method", "GET")
                            .put("path", JSONArray().put(path ?: "/"))
                            .put("headers", JSONObject().put("Host", JSONArray(hosts)))
                    )
                }
                s.put("tcpSettings", JSONObject().put("header", header))
            }
            "ws" -> s.put(
                "wsSettings",
                JSONObject().put("path", path ?: "/").put("host", host.ifEmpty { domain.orEmpty() })
            )
            "httpupgrade" -> s.put(
                "httpupgradeSettings",
                JSONObject().put("path", path ?: "/").put("host", host.ifEmpty { domain.orEmpty() })
            )
            "xhttp" -> {
                val x = JSONObject().put("path", path ?: "/").put("host", host.ifEmpty { domain.orEmpty() })
                q["mode"]?.takeIf { it.isNotBlank() }?.let { x.put("mode", it) }
                q["extra"]?.takeIf { it.isNotBlank() }?.let {
                    try {
                        x.put("extra", JSONObject(it))
                    } catch (e: Exception) {
                        // битый extra игнорируем
                    }
                }
                s.put("xhttpSettings", x)
            }
            "h2" -> s.put(
                "httpSettings",
                JSONObject()
                    .put("host", JSONArray(host.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                    .put("path", path ?: "/")
            )
            "grpc" -> {
                val g = JSONObject()
                    .put("serviceName", q["serviceName"].orEmpty())
                    .put("multiMode", q["mode"] == "multi")
                    .put("idle_timeout", 60)
                    .put("health_check_timeout", 20)
                q["authority"]?.takeIf { it.isNotBlank() }?.let { g.put("authority", it) }
                s.put("grpcSettings", g)
            }
        }

        val security = q["security"].orEmpty()
        if (security == "tls" || security == "reality") {
            val sni = q["sni"]?.ifBlank { null }
                ?: host.split(",").firstOrNull()?.trim()?.ifBlank { null }
                ?: domain
            val t = JSONObject()
            sni?.let { t.put("serverName", it) }
            if (security == "tls") {
                val insecure = listOf("insecure", "allowInsecure", "allow_insecure").any { q[it] == "1" }
                t.put("allowInsecure", insecure)
                q["fp"]?.takeIf { it.isNotBlank() }?.let { t.put("fingerprint", it) }
                q["alpn"]?.takeIf { it.isNotBlank() }?.let {
                    t.put("alpn", JSONArray(it.split(",").map { a -> a.trim() }.filter { a -> a.isNotEmpty() }))
                }
                s.put("security", "tls").put("tlsSettings", t)
            } else {
                t.put("fingerprint", q["fp"]?.ifBlank { null } ?: "chrome")
                t.put("publicKey", q["pbk"].orEmpty())
                q["sid"]?.takeIf { it.isNotBlank() }?.let { t.put("shortId", it) }
                q["spx"]?.takeIf { it.isNotBlank() }?.let { t.put("spiderX", it) }
                q["pqv"]?.takeIf { it.isNotBlank() }?.let { t.put("mldsa65Verify", it) }
                s.put("security", "reality").put("realitySettings", t)
            }
        }
        return s
    }
}
