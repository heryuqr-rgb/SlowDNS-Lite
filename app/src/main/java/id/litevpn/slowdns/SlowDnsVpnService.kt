package id.litevpn.slowdns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * VPN consent + active TUN lifecycle.
 * The TUN intentionally has no routes until packet forwarding is implemented.
 * This activates the Android VPN interface/indicator without black-holing traffic.
 */
class SlowDnsVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (tun == null) {
            try {
                tun = Builder()
                    .setSession("SlowDNS Lite")
                    .setMtu(1280)
                    .addAddress("10.77.0.1", 32)
                    // No addRoute() yet: do not redirect/drop user traffic before
                    // a real packet-forwarding engine is connected.
                    .establish()
                if (tun == null) {
                    Log.e(TAG, "TUN establish returned null")
                    stopSelf()
                    return START_NOT_STICKY
                }
                Log.i(TAG, "TUN interface established (no routes configured).")
            } catch (e: Exception) {
                Log.e(TAG, "Unable to establish TUN", e)
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VPN connection", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "SlowDNS Lite VPN status" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("SlowDNS Lite")
            .setContentText("VPN interface active · routing not enabled yet")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun stopVpn() {
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "id.litevpn.slowdns.START_VPN"
        const val ACTION_STOP = "id.litevpn.slowdns.STOP_VPN"
        private const val CHANNEL_ID = "slowdns_vpn"
        private const val NOTIFICATION_ID = 7402
        private const val TAG = "SlowDnsVpnService"
    }
}
