package app.letnievpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.letnievpn.data.Repo
import app.letnievpn.data.VpnStatus
import app.letnievpn.ui.MainScreen
import app.letnievpn.vpn.LetnieVpnService

class MainActivity : ComponentActivity() {

    /** Плитка шторки просит включить VPN, но нужно разрешение — просим его уже здесь. */
    private val connectRequest = mutableStateOf(false)

    override fun onStart() {
        super.onStart()
        Repo.startLivePing()
    }

    override fun onStop() {
        Repo.stopLivePing()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_CONNECT) connectRequest.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // статус-бар и панель навигации полностью прозрачные, без серой подложки
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        // тихо обновляем подписки и пинги при открытии
        Repo.refreshSubscriptions()
        if (intent?.action == ACTION_CONNECT) connectRequest.value = true

        setContent {
            // 0 — ничего не ждём; иначе после выдачи разрешений включаем VPN
            var pendingStart by remember { mutableStateOf(false) }

            val vpnPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { r ->
                if (r.resultCode == RESULT_OK && pendingStart) LetnieVpnService.start(this)
                pendingStart = false
            }
            val notifPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {
                // уведомление нужно только для красоты: VPN работает и без него
                val intent: Intent? = VpnService.prepare(this)
                if (intent == null) {
                    LetnieVpnService.start(this)
                    pendingStart = false
                } else {
                    vpnPermission.launch(intent)
                }
            }

            // аватарка подписки: системный выбор фото, разрешения не нужны
            var avatarFor by remember { mutableStateOf<String?>(null) }
            val gallery = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                val id = avatarFor
                avatarFor = null
                if (uri != null && id != null) Repo.scope.launch { Repo.setAvatar(id, uri) }
            }

            fun startVpn() {
                pendingStart = true
                val needNotif = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (needNotif) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    val intent: Intent? = VpnService.prepare(this)
                    if (intent == null) {
                        LetnieVpnService.start(this)
                        pendingStart = false
                    } else {
                        vpnPermission.launch(intent)
                    }
                }
            }

            LaunchedEffect(connectRequest.value) {
                if (connectRequest.value) {
                    connectRequest.value = false
                    if (Repo.vpn.value == VpnStatus.OFF) startVpn()
                }
            }

            MainScreen(
                onPower = {
                    if (Repo.vpn.value == VpnStatus.OFF) startVpn() else LetnieVpnService.stop(this)
                },
                onReconnect = {
                    // сменили сервер или режим во время работы — переподключаем
                    if (Repo.vpn.value != VpnStatus.OFF) LetnieVpnService.start(this)
                },
                onPickAvatar = { id ->
                    avatarFor = id
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
        }
    }

    companion object {
        const val ACTION_CONNECT = "app.letnievpn.CONNECT"
    }
}
