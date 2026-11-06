package com.hostpot

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

class HostService : Service() {
    companion object {
        @Volatile var server: HttpServer? = null
        fun start(c: Context) { c.startForegroundService(Intent(c, HostService::class.java).setAction("start")) }
        fun stop(c: Context) { c.startService(Intent(c, HostService::class.java).setAction("stop")) }
    }
    private var wl: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    @Volatile private var dog = false
    @Volatile private var gen = 0
    private var launched = false
    private var lastLaunch = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "stop") {
            Cfg.wantRun = false
            shutdown()
            stopForeground(true); stopSelf()
            return START_NOT_STICKY
        }
        Cfg.wantRun = true
        notif()
        startServer()
        startDog()
        launchExtras(true)
        return START_STICKY
    }

    private fun notif() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("ph", "HostPot", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "ph").setContentTitle("HostPot")
            .setContentText("Server ON - port ${Cfg.port} (24x7)").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi).setOngoing(true).build()
        startForeground(1, n)
    }

    @Synchronized private fun startServer() {
        if (server?.alive == true) return
        server?.stop()
        try {
            val s = HttpServer(Cfg.port); s.start(); server = s
            if (Stats.start == 0L) Stats.start = System.currentTimeMillis()
        } catch (e: Exception) { Logx.add("START FAIL: " + e.message) }
        if (wl == null) {
            wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ph:srv").apply { acquire() }
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ph:wifi").apply { acquire() }
        }
    }

    // Termux backend (php/node/python) + tunnel auto-start; relaunch if down
    private fun launchExtras(first: Boolean) {
        if (first && launched) return
        launched = true
        val kind = Cfg.backend
        if (kind != "none" && (first || !Termux.portOpen(Cfg.backendPort))) {
            Logx.add("backend $kind: starting")
            Termux.startBackend(this, kind, Cfg.active)
        }
        if (first && Cfg.tunnel != "off") Termux.startTunnel(this, Cfg.tunnel)
        lastLaunch = System.currentTimeMillis()
    }

    private fun startDog() {
        if (dog) return
        dog = true
        val my = ++gen
        Thread({
            var tick = 0
            while (dog && gen == my) {
                try { Thread.sleep(10000) } catch (_: Exception) {}
                if (!dog || gen != my) break
                if (Cfg.wantRun && server?.alive != true) {
                    Logx.add("watchdog: server down, restarting")
                    Stats.restarts++
                    startServer()
                }
                tick++
                if (tick % 6 == 0 && Cfg.backend != "none" && System.currentTimeMillis() - lastLaunch > 90000 && !Termux.portOpen(Cfg.backendPort)) {
                    Logx.add("watchdog: backend down, relaunching")
                    launchExtras(false)
                }
            }
        }, "watchdog").start()
    }

    private fun shutdown() {
        dog = false; launched = false
        server?.stop(); server = null
        Stats.start = 0L
        try { wl?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        wl = null; wifi = null
    }

    override fun onDestroy() { dog = false; super.onDestroy() }
}
