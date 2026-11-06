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

        val head = TextView(me)
        head.text = "PhoneHost crash hua. Is screen ka screenshot bhejo."
        head.textSize = 16f
        head.setPadding(24, 32, 24, 8)

        val copy = Button(me)
        copy.text = "Copy error"
        copy.setOnClickListener {
            val cm = me.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("crash", trace))
            Toast.makeText(me, "Copied", Toast.LENGTH_SHORT).show()
        }

        val body = TextView(me)
        body.text = trace
        body.textSize = 11f
        body.setTextIsSelectable(true)
        body.setPadding(24, 24, 24, 24)

        val scroll = ScrollView(me)
        scroll.addView(body)

        val col = LinearLayout(me)
        col.orientation = LinearLayout.VERTICAL
        col.addView(head)
        col.addView(copy)
        col.addView(scroll)
        setContentView(col)
    }
}
