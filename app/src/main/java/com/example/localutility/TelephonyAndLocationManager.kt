package com.example.localutility

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class TelephonyAndLocationManager(private val context: Context) {

    private var latestLocation: Location? = null

    init {
        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    latestLocation = loc
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1f, listener)
            }
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, listener)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- 1. Real Storage Calculations ---
    fun getStorageStats(): JSONObject {
        val obj = JSONObject()
        try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val totalBytes = totalBlocks * blockSize
            val freeBytes = availableBlocks * blockSize
            val usedBytes = totalBytes - freeBytes

            val totalGB = totalBytes / (1024.0 * 1024.0 * 1024.0)
            val usedGB = usedBytes / (1024.0 * 1024.0 * 1024.0)
            val freeGB = freeBytes / (1024.0 * 1024.0 * 1024.0)
            val usedPercent = if (totalBytes > 0) ((usedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt() else 0

            obj.put("totalGB", String.format(Locale.US, "%.1f GB", totalGB))
            obj.put("usedGB", String.format(Locale.US, "%.1f GB", usedGB))
            obj.put("freeGB", String.format(Locale.US, "%.1f GB", freeGB))
            obj.put("usedPercent", usedPercent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return obj
    }

    // --- 2. Call Logs Query ---
    fun getCallLogs(): JSONArray {
        val array = JSONArray()
        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                null, null, "${CallLog.Calls.DATE} DESC LIMIT 50"
            )
            cursor?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)

                while (it.moveToNext()) {
                    val typeInt = if (typeIdx >= 0) it.getInt(typeIdx) else CallLog.Calls.INCOMING_TYPE
                    val typeStr = when (typeInt) {
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Incoming"
                    }

                    val obj = JSONObject().apply {
                        put("number", if (numIdx >= 0) it.getString(numIdx) else "")
                        put("name", if (nameIdx >= 0 && it.getString(nameIdx) != null) it.getString(nameIdx) else "Unknown")
                        put("type", typeStr)
                        put("date", if (dateIdx >= 0) it.getLong(dateIdx) else 0L)
                        put("duration", if (durIdx >= 0) "${it.getInt(durIdx)}s" else "0s")
                    }
                    array.put(obj)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return array
    }

    // --- 3. SMS Queries ---
    fun getRecentSms(): JSONArray {
        val array = JSONArray()
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC LIMIT 50"
            )
            cursor?.use {
                val addrIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                val typeIdx = it.getColumnIndex(Telephony.Sms.TYPE)

                while (it.moveToNext()) {
                    val obj = JSONObject().apply {
                        put("address", if (addrIdx >= 0) it.getString(addrIdx) else "")
                        put("body", if (bodyIdx >= 0) it.getString(bodyIdx) else "")
                        put("date", if (dateIdx >= 0) it.getLong(dateIdx) else 0L)
                        put("type", if (typeIdx >= 0 && it.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_INBOX) "inbox" else "sent")
                    }
                    array.put(obj)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return array
    }

    fun sendSms(to: String, message: String): Boolean {
        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(to, null, message, null, null)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // --- 4. Contacts Query ---
    fun getContacts(): JSONArray {
        val array = JSONArray()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val obj = JSONObject().apply {
                        put("name", if (nameIdx >= 0) it.getString(nameIdx) else "Unknown")
                        put("number", if (numIdx >= 0) it.getString(numIdx) else "")
                    }
                    array.put(obj)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return array
    }

    // --- 5. GPS Location ---
    @SuppressLint("MissingPermission")
    fun getLocation(): JSONObject {
        val obj = JSONObject()
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = latestLocation 
                ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (location != null) {
                obj.put("lat", location.latitude)
                obj.put("lng", location.longitude)
                obj.put("accuracy", location.accuracy)
                obj.put("timestamp", location.time)
            } else {
                obj.put("error", "Location signal searching...")
            }
        } catch (e: Exception) {
            obj.put("error", e.message)
        }
        return obj
    }

    // --- 6. Make Phone Call ---
    @SuppressLint("MissingPermission")
    fun makeCall(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) { e.printStackTrace() }
    }
}