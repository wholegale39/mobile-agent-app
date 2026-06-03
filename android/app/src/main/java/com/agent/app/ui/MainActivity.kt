package com.agent.app.ui

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "手机助手"
        tv.textSize = 24f
        tv.setPadding(40, 40, 40, 40)
        setContentView(tv)
    }
}
