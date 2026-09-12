package com.example.localutility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = context.getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
            val code = prefs.getString("pairing_code", "123456") ?: "123456"

            val serviceIntent = Intent(context, LocalFileServerService::class.java).apply {
                putExtra("PAIRING_CODE", code)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.d("BootReceiver", "OpenDroid background service auto-started on phone reboot successfully!")
        }
    }
}