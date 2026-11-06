package com.phonehost.app

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class ServerService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var lastError: String? = null
        private const val CHANNEL = "phonehost_server"
    }

    private var server: WebServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @SuppressLint("WakelockTimeout", "Deprecation")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = Prefs.port(this)
        createChannel()
        val notif = buildNotification(port)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, 1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }

        SiteManager.ensureDefault(this)
        try {
            server?.stop()
            server = WebServer(SiteManager.www(this), port).also { it.start() }
            running = true
            lastError = null
        } catch (e: Exception) {
            running = false
            lastError = "Server start failed: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonehost:server").apply { acquire() }
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock == null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "phonehost:wifi").apply { acquire() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Server status", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(port: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("PhoneHost running")
            .setContentText("Serving your site on port $port")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
