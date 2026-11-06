package com.phonehost.app

import android.app.Application
import android.content.Intent
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val trace = ("Thread: ${t.name}\n$sw").take(8000)
                val i = Intent(this, CrashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("trace", trace)
                startActivity(i)
            } catch (x: Throwable) {
                old?.uncaughtException(t, e)
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}￼Enter
