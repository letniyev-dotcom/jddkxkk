package app.letnievpn.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.letnievpn.MainActivity
import app.letnievpn.R
import app.letnievpn.data.Repo
import app.letnievpn.data.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Плитка быстрых настроек (шторка): одно нажатие включает или выключает VPN. */
class LetnieTileService : TileService() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        Repo.init(this)
        watch?.cancel()
        watch = uiScope.launch {
            combine(Repo.vpn, Repo.vpnServer, Repo.subs) { st, id, _ ->
                st to Repo.allServers().firstOrNull { it.id == id }?.name
            }.collect { (st, name) -> render(st, name) }
        }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
        super.onStopListening()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        Repo.init(this)
        if (Repo.vpn.value != VpnStatus.OFF) {
            LetnieVpnService.stop(this)
            return
        }
        unlockAndRun {
            if (VpnService.prepare(this) == null) {
                LetnieVpnService.start(this)
            } else {
                // разрешение VPN ещё не выдано — открываем приложение, оно покажет системное окно
                openApp()
            }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_CONNECT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(st: VpnStatus, server: String?) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.app_name)
        tile.state = if (st == VpnStatus.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (st) {
                VpnStatus.OFF -> getString(R.string.status_off)
                VpnStatus.CONNECTING -> getString(R.string.status_connecting)
                VpnStatus.ON -> server ?: getString(R.string.status_on)
            }
        }
        tile.updateTile()
    }
}
