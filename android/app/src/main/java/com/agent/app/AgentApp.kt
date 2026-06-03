package com.agent.app

import android.app.Application
import android.util.Log

class AgentApp : Application() {
    override fun onCreate() {
        try {
            super.onCreate()
            Log.d(TAG, "AgentApp started")
        } catch (e: Exception) {
            Log.e(TAG, "AgentApp init failed", e)
        }
    }

    companion object {
        private const val TAG = "AgentApp"
    }
}
