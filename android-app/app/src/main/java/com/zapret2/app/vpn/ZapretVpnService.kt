package com.zapret2.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zapret2.app.AppDebugLog
import com.zapret2.app.MainActivity
import com.zapret2.app.R
import dagger.hilt.android.AndroidEntryPoint
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import javax.inject.Inject

/**
 * Runs the sing-box tunnel.
 *
 * The service owns the TUN interface and the core instance. The core is told about the current
 * routing mode when it starts, and the network guard asks it to redial when the device moves
 * between Wi-Fi and mobile data.
 */
@AndroidEntryPoint
class ZapretVpnService : VpnService(), CommandServerHandler {

    @Inject
    lateinit var repository: VpnRepository

    private var commandServer: CommandServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                redial()
                return START_STICKY
            }
            else -> startTunnel()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        commandServer?.close()
        commandServer = null
        repository.setStatus(VpnStatus.STOPPED)
        super.onDestroy()
    }

    private fun startTunnel() {
        val node = repository.selectedNode
        if (node == null) {
            repository.reportMessage(getString(R.string.vpn_no_server_selected))
            stopSelf()
            return
        }
        startForegroundNotification()
        repository.setStatus(VpnStatus.STARTING)
        val config = VpnConfigFactory.build(node, repository.nodes.value, repository.mode.value)
        try {
            Libbox.checkConfig(config)
        } catch (error: Exception) {
            AppDebugLog.error(TAG, "The tunnel configuration was rejected", error)
            repository.setStatus(VpnStatus.ERROR)
            repository.reportMessage(error.readableMessage())
            stopSelf()
            return
        }
        try {
            val server = commandServer ?: run {
                CommandServer(this, VpnPlatformInterface(this, repository.underlyingNetworkFor(repository.mode.value)))
                    .also {
                        it.start()
                        commandServer = it
                    }
            }
            server.startOrReloadService(config, OverrideOptions())
            repository.setStatus(VpnStatus.RUNNING)
        } catch (error: Exception) {
            AppDebugLog.error(TAG, "The tunnel could not be started", error)
            repository.setStatus(VpnStatus.ERROR)
            repository.reportMessage(error.readableMessage())
            stopSelf()
        }
    }

    private fun redial() {
        val server = commandServer ?: return
        runCatching { server.resetNetwork() }
            .onFailure { AppDebugLog.warn(TAG, "The core did not accept the network reset") }
    }

    private fun stopTunnel() {
        runCatching { commandServer?.closeService() }
        commandServer?.close()
        commandServer = null
        repository.setStatus(VpnStatus.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_zapret)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    // CommandServerHandler: the core asks the app about things it cannot answer alone.

    override fun serviceStop() {
        stopTunnel()
    }

    override fun serviceReload() {
        runCatching { startTunnel() }
    }

    override fun writeDebugMessage(message: String) {
        AppDebugLog.debug(TAG, message)
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        setAvailable(false)
        setEnabled(false)
    }

    override fun setSystemProxyEnabled(enabled: Boolean) = Unit

    override fun connectSSHAgent(): Int = -1

    override fun triggerNativeCrash(): Unit = Unit

    companion object {
        const val ACTION_START = "com.zapret2.plus.vpn.START"
        const val ACTION_STOP = "com.zapret2.plus.vpn.STOP"
        const val ACTION_RECONNECT = "com.zapret2.plus.vpn.RECONNECT"
        private const val TAG = "Zapret2Vpn"
        private const val CHANNEL_ID = "zapret2_vpn"
        private const val NOTIFICATION_ID = 4711
    }
}
