package app.letnievpn.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал работы приложения. Пишется в файл во внутреннем хранилище и по кнопке слева вверху
 * выгружается в «Загрузки». Ключи, пароли и адреса серверов в журнал не попадают.
 */
object Logger {
    private var appLog: File? = null
    private var xrayLog: File? = null
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    /** Путь, куда ядро xray пишет свой журнал ошибок. */
    val xrayPath: String get() = xrayLog?.absolutePath.orEmpty()

    @Synchronized
    fun init(context: Context) {
        if (appLog != null) return
        val dir = context.applicationContext.filesDir
        appLog = File(dir, "app.log").also { trim(it) }
        xrayLog = File(dir, "xray.log").also { trim(it) }
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            error("crash", android.util.Log.getStackTraceString(e))
            old?.uncaughtException(t, e)
        }
        info("app", "запуск, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun info(tag: String, msg: String) = write("I", tag, msg)

    fun error(tag: String, msg: String) = write("E", tag, msg)

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        android.util.Log.println(if (level == "E") android.util.Log.ERROR else android.util.Log.INFO, "letnievpn/$tag", msg)
        val f = appLog ?: return
        try {
            f.appendText("${time.format(Date())} $level/$tag: $msg\n")
        } catch (e: Exception) {
            // журнал не должен ломать работу приложения
        }
    }

    private fun trim(f: File) {
        try {
            if (f.exists() && f.length() > 300_000) f.writeText(f.readText().takeLast(150_000))
        } catch (e: Exception) {
            // ничего
        }
    }

    /** Заменяет секреты в конфиге на *** — для журнала. */
    fun redact(json: String): String = try {
        fun walk(v: Any?): Any? = when (v) {
            is JSONObject -> {
                val o = JSONObject()
                v.keys().forEach { k ->
                    o.put(k, if (k in SECRET_KEYS) "***" else walk(v.get(k)))
                }
                o
            }
            is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(walk(v.get(i))) }
            else -> v
        }
        walk(JSONObject(json)).toString()
    } catch (e: Exception) {
        "<конфиг не удалось скрыть, не выводится>"
    }

    private val SECRET_KEYS = setOf("id", "password", "address", "publicKey", "shortId", "spiderX", "mldsa65Verify")

    /** Сохраняет журнал в «Загрузки». Возвращает, куда сохранено, или null при неудаче. */
    fun export(context: Context): String? {
        return try {
            val name = "letnievpn-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt"
            val text = buildDump(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: return null
                "Загрузки/$name"
            } else {
                // Android 9 и старше: запись в общую папку требует отдельного разрешения, пишем в папку приложения
                val dir = context.getExternalFilesDir(null) ?: return null
                File(dir, name).writeText(text)
                "${dir.absolutePath}/$name"
            }
        } catch (e: Exception) {
            error("log", "не удалось сохранить журнал: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun buildDump(context: Context): String {
        val sb = StringBuilder()
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val servers = Repo.allServers()
        val pg = Repo.pings.value
        sb.appendLine("letnievpn $version")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine(
            "Состояние: vpn=${Repo.vpn.value}, умное=${Repo.smart.value}, подписок=${Repo.subs.value.size}, " +
                "серверов=${servers.size}, пинг ok=${pg.values.count { it > 0 }}/${servers.size}"
        )
        if (Repo.connError.value.isNotBlank()) sb.appendLine("Последняя ошибка подключения: ${Repo.connError.value}")
        sb.appendLine()
        sb.appendLine("---- журнал приложения ----")
        sb.appendLine(appLog?.takeIf { it.exists() }?.readText().orEmpty())
        sb.appendLine("---- журнал ядра xray (хвост) ----")
        sb.appendLine(xrayLog?.takeIf { it.exists() }?.readText()?.takeLast(60_000).orEmpty())
        return sb.toString()
    }
}
