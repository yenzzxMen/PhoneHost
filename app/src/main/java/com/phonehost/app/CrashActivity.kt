package com.phonehost.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra("trace") ?: "No trace"
        val me = this

        val head = TextView(me).apply {
            text = "PhoneHost crash hua. Is screen ka screenshot bhejo."
            textSize = 16f
            setPadding(24, 32, 24, 8)
        }
        val copy = Button(me).apply {
            text = "Copy error"
            setOnClickListener {
                val cm = me.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", trace))
                Toast.makeText(me, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        val body = TextView(me).apply {
            text = trace
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
        }
        val col = LinearLayout(me).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(copy)
            addView(ScrollView(me).apply { addView(body) })
        }
        setContentView(col)
    }
}￼Enter
