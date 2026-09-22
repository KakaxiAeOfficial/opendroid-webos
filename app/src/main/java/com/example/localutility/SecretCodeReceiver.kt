package com.example.localutility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * SecretCodeReceiver
 * Listens for phone dialer secret code (*#*#1234#*#*) to bring up OpenDroid MainActivity
 * even when the launcher app icon is completely hidden in Stealth Mode.
 */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            Log.d("OpenDroid", "Secret code (*#*#1234#*#*) dialed! Launching OpenDroid MainActivity...")
            try {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e("OpenDroid", "Failed to launch MainActivity from secret code", e)
            }
        }
    }
}