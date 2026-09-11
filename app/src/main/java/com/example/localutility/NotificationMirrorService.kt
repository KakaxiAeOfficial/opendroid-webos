package com.example.localutility

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

class NotificationMirrorService : NotificationListenerService() {

    data class CachedReply(val pendingIntent: PendingIntent, val remoteInput: RemoteInput)

    companion object {
        var instance: NotificationMirrorService? = null
        val replyCache = HashMap<String, CachedReply>()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d("NotificationMirror", "Notification Listener Connected & Active!")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.d("NotificationMirror", "Notification Listener Disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            // Ignore ongoing system notifications (like our own foreground services or hotspot)
            if (sbn.packageName == packageName || sbn.isOngoing) return

            val extras = sbn.notification.extras
            
            // Safe extraction for all formats (CharSequence handles SpannableString from WhatsApp/Telecom)
            val titleCharSeq = extras.getCharSequence(Notification.EXTRA_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
                ?: sbn.notification.tickerText
            val title = titleCharSeq?.toString() ?: ""

            val textCharSeq = extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            val text = textCharSeq?.toString() ?: ""

            if (title.isEmpty() && text.isEmpty()) return

            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(sbn.packageName, 0)
                ).toString()
            } catch (e: Exception) {
                sbn.packageName
            }

            val key = sbn.key
            var canReply = false

            // Search for direct reply action (WhatsApp, Telegram, Messages)
            sbn.notification.actions?.forEach { action ->
                val remoteInputs = action.remoteInputs
                if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                    canReply = true
                    replyCache[key] = CachedReply(action.actionIntent, remoteInputs[0])
                }
            }

            val json = JSONObject().apply {
                put("type", "NOTIFICATION")
                put("id", key)
                put("app", appName)
                put("title", if (title.isNotEmpty()) title else appName)
                put("text", text)
                put("canReply", canReply)
            }

            LocalFileServerService.instance?.broadcastMessage(json.toString())
            Log.d("NotificationMirror", "Successfully mirrored notification from: $appName ($title)")
        } catch (e: Exception) {
            Log.e("NotificationMirror", "Error processing notification", e)
        }
    }

    fun sendQuickReply(key: String, replyText: String): Boolean {
        val cachedReply = replyCache[key] ?: return false
        return try {
            val intent = Intent()
            val bundle = Bundle()
            bundle.putCharSequence(cachedReply.remoteInput.resultKey, replyText)
            RemoteInput.addResultsToIntent(arrayOf(cachedReply.remoteInput), intent, bundle)

            cachedReply.pendingIntent.send(this, 0, intent)
            replyCache.remove(key)
            Log.d("NotificationMirror", "Quick reply sent for key: $key")
            true
        } catch (e: Exception) {
            Log.e("NotificationMirror", "Failed to send quick reply", e)
            false
        }
    }
}