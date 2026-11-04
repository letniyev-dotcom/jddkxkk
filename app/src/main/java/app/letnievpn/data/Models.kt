package app.letnievpn.data

import org.json.JSONArray
import org.json.JSONObject

enum class VpnStatus { OFF, CONNECTING, ON }

data class Server(
    val id: String,
    val name: String,
    val cc: String,
    val host: String,
    val port: Int,
    val link: String,
)

data class Sub(
    val id: String,
    val name: String,
    val url: String?,
    val info: String,
    val servers: List<Server>,
    /** Имя файла аватарки в filesDir/avatars; null — аватарки нет, показывается «+». */
    val avatar: String? = null,
)

object SubJson {
    fun toJson(list: List<Sub>): String {
        val arr = JSONArray()
        list.forEach { s ->
            val servers = JSONArray()
            s.servers.forEach { v ->
                servers.put(
                    JSONObject()
                        .put("id", v.id).put("name", v.name).put("cc", v.cc)
                        .put("host", v.host).put("port", v.port).put("link", v.link)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", s.id).put("name", s.name).put("url", s.url ?: JSONObject.NULL)
                    .put("info", s.info).put("servers", servers)
                    .put("avatar", s.avatar ?: JSONObject.NULL)
            )
        }
        return arr.toString()
    }

    fun fromJson(text: String): List<Sub> = try {
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val sa = o.getJSONArray("servers")
            Sub(
                id = o.getString("id"),
                name = o.getString("name"),
                url = if (o.isNull("url")) null else o.getString("url"),
                info = o.optString("info", ""),
                avatar = if (o.isNull("avatar")) null else o.optString("avatar").ifBlank { null },
                servers = (0 until sa.length()).map { j ->
                    val v = sa.getJSONObject(j)
                    Server(
                        id = v.getString("id"),
                        name = v.getString("name"),
                        cc = v.optString("cc", ""),
                        host = v.getString("host"),
                        port = v.getInt("port"),
                        link = v.getString("link"),
                    )
                },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
