package app.letnievpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.letnievpn.data.Repo

/**
 * Умное подключение: после перезагрузки телефона само поднимает VPN,
 * если он был включён и умное подключение не отключали.
 * Работает только когда разрешение VPN уже выдано — без него система потребовала бы окно подтверждения.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON") return
        Repo.init(context)
        if (!Repo.desired() || !Repo.smartStored()) return
        if (VpnService.prepare(context) != null) return
        LetnieVpnService.start(context)
    }
}
