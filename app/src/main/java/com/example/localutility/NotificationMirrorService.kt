package com.example.localutility

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class NotificationMirrorService : NotificationListenerService() {

    data class CachedReply(val pendingIntent: PendingIntent, val remoteInput: RemoteInput)

    companion object {
        var instance: NotificationMirrorService? = null
        val replyCache = HashMap<String, CachedReply>()
    }

    var onNotificationPosted: ((JSONObject) -> Unit)? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val key = sbn.key
        var canReply = false

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
            put("title", title)
            put("text", text)
            put("canReply", canReply)
        }

        onNotificationPosted?.invoke(json)
    }

    fun sendQuickReply(key: String, replyText: String) {
        val cachedReply = replyCache[key] ?: return
        val intent = Intent()
        val bundle = Bundle()
        bundle.putCharSequence(cachedReply.remoteInput.resultKey, replyText)
        RemoteInput.addResultsToIntent(arrayOf(cachedReply.remoteInput), intent, bundle)
        try {
            cachedReply.pendingIntent.send(this, 0, intent)
            replyCache.remove(key)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}