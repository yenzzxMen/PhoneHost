package com.hostpot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Cfg.app = c.applicationContext
        if (Cfg.autoBoot && Cfg.wantRun) {
            try { HostService.start(c) } catch (_: Exception) {}
        }
    }
}
