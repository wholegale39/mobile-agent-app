package com.agent.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "手机助手",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "后台服务运行通知"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "agent_foreground"
    }
}
