package com.zapret2.app.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zapret2.app.AppDebugLog
import com.zapret2.app.R
import com.zapret2.app.data.RootCommandExecutor
import com.zapret2.app.data.RootCommandPolicy
import com.zapret2.app.vpn.NetworkMode
import com.zapret2.app.vpn.NetworkSnapshot
import com.zapret2.app.vpn.NetworkTransport
import com.zapret2.app.vpn.VpnRepository
import com.zapret2.app.vpn.ZapretVpnService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the device on the radio the user asked for.
 *
 * With root access the radios themselves are switched, which is the only way to make both the
 * bypass service and the tunnel use the same interface: the bypass module applies its rules to
 * every interface, so whichever radio is up is the one that carries the traffic.
 *
 * In automatic mode the device falls back to mobile data when Wi-Fi stops working and returns
 * to Wi-Fi as soon as it recovers.
 */
@AndroidEntryPoint
class NetworkGuardService : android.app.Service() {

    @Inject
    lateinit var repository: VpnRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var state = NetworkPolicy.State()
    private var appliedWifi: Boolean? = null
    private var appliedMobileData: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        scope.launch { loop() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        while (scope.isActive) {
            applyPolicy()
            delay(TICK_MILLIS)
        }
    }

    private fun applyPolicy() {
        val mode = repository.mode.value
        val snapshot = currentSnapshot()
        val (decision, nextState) = NetworkPolicy.decide(mode, snapshot, System.currentTimeMillis(), state)
        state = nextState
        if (decision.wifi != null && decision.wifi != appliedWifi) {
            setWifi(decision.wifi)
            appliedWifi = decision.wifi
        }
        if (decision.mobileData != null && decision.mobileData != appliedMobileData) {
            setMobileData(decision.mobileData)
            appliedMobileData = decision.mobileData
        }
        if (decision.reconnectTunnel) {
            requestTunnelReconnect()
        }
        repository.reportNetworkState(snapshot, decision.reason)
    }

    private fun currentSnapshot(): NetworkSnapshot {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return NetworkSnapshot()
        val network = manager.activeNetwork ?: return NetworkSnapshot()
        val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkSnapshot()
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            else -> NetworkTransport.OTHER
        }
        return NetworkSnapshot(
            transport = transport,
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        )
    }

    private fun setWifi(enabled: Boolean) {
        val command = if (enabled) "svc wifi enable" else "svc wifi disable"
        val result = RootCommandExecutor.execute(command, RootCommandPolicy.MUTATION)
        if (!result.isSuccess) {
            AppDebugLog.warn(TAG, "Could not switch Wi-Fi ${if (enabled) "on" else "off"}: ${result.detail}")
        }
    }

    private fun setMobileData(enabled: Boolean) {
        val command = if (enabled) "svc data enable" else "svc data disable"
        val result = RootCommandExecutor.execute(command, RootCommandPolicy.MUTATION)
        if (!result.isSuccess) {
            AppDebugLog.warn(TAG, "Could not switch mobile data ${if (enabled) "on" else "off"}: ${result.detail}")
        }
    }

    private fun requestTunnelReconnect() {
        val intent = Intent(this, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_RECONNECT)
        runCatching { startService(intent) }
            .onFailure { AppDebugLog.warn(TAG, "The tunnel could not be asked to redial") }
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
        val mode = repository.mode.value
        val title = getString(R.string.network_guard_notification_title)
        val text = getString(
            when (mode) {
                NetworkMode.AUTO -> R.string.network_guard_notification_auto
                NetworkMode.WIFI -> R.string.network_guard_notification_wifi
                NetworkMode.LTE -> R.string.network_guard_notification_lte
            },
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_zapret)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.network_guard_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.zapret2.plus.network.START"
        const val ACTION_STOP = "com.zapret2.plus.network.STOP"
        private const val TAG = "Zapret2Network"
        private const val CHANNEL_ID = "zapret2_network"
        private const val NOTIFICATION_ID = 4712
        private const val TICK_MILLIS = 5_000L
    }
}
