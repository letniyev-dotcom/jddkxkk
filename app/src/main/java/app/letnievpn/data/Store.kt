package app.letnievpn.data

import android.content.Context
import java.util.UUID

/** Простое хранилище настроек и подписок (приватные SharedPreferences приложения). */
class Store(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("letnie", Context.MODE_PRIVATE)

    var subsJson: String
        get() = sp.getString("subs", "[]") ?: "[]"
        set(v) = sp.edit().putString("subs", v).apply()

    /** Умное подключение: само выбирает самый быстрый сервер и поднимается после перезагрузки. */
    var smart: Boolean
        get() = sp.getBoolean("smart", true)
        set(v) = sp.edit().putBoolean("smart", v).apply()

    /** Тема: -1 — как в системе, 0 — светлая, 1 — тёмная. */
    var theme: Int
        get() = sp.getInt("theme", -1)
        set(v) = sp.edit().putInt("theme", v).apply()

    /** Пакеты приложений, которые ходят мимо VPN. */
    var excluded: Set<String>
        get() = sp.getStringSet("excluded", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("excluded", HashSet(v)).apply()

    /** Развёрнутые подписки в списке. По умолчанию все свёрнуты. */
    var opened: Set<String>
        get() = sp.getStringSet("opened", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("opened", HashSet(v)).apply()

    var selected: String?
        get() = sp.getString("selected", null)
        set(v) = sp.edit().putString("selected", v).apply()

    /** Пользователь включил VPN и не выключал вручную. Нужно для автозапуска после перезагрузки. */
    var desired: Boolean
        get() = sp.getBoolean("desired", false)
        set(v) = sp.edit().putBoolean("desired", v).apply()

    /** Постоянный идентификатор устройства (HWID): панели подписок используют его для лимита устройств. */
    val hwid: String
        get() {
            val k = sp.getString("hwid", null)
            if (k != null) return k
            val n = UUID.randomUUID().toString().replace("-", "").take(16)
            sp.edit().putString("hwid", n).apply()
            return n
        }

    /**
     * Ключ xudp для ядра xray: ровно 32 байта в base64url без «=» (43 символа).
     * Раньше сюда попадал UUID — ядро его отвергало и не запускалось, поэтому ключ хранится под новым именем.
     */
    val xudpKey: String
        get() {
            val k = sp.getString("xudp32", null)
            if (k != null && isValidXudpKey(k)) return k
            val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val n = android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            sp.edit().putString("xudp32", n).remove("xudp").apply()
            return n
        }

    private fun isValidXudpKey(k: String): Boolean = try {
        android.util.Base64.decode(k, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP).size == 32
    } catch (e: IllegalArgumentException) {
        false
    }
}
