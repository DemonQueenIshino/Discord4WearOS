package com.zaffox.discordwear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class TokenBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.zaffox.discordwear.SET_TOKEN") {
            val rawToken = intent.getStringExtra("token")
            if (!rawToken.isNullOrBlank()) {
                Log.d("TokenReceiver", "Received token via ADB broadcast")
                SetupPreferences.saveToken(context, rawToken)
                context.discordApp.initRepository(rawToken)

                // Launch or bring MainActivity to front
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
