package com.example.localutility

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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

    // --- 1. File Explorer & Directory Navigation ---
    fun getDirectoryContents(targetPath: String?): JSONObject {
        val root = Environment.getExternalStorageDirectory()
        val path = if (!targetPath.isNullOrEmpty()) targetPath else root.absolutePath
        val targetDir = File(path)

        val result = JSONObject()
        val filesArray = JSONArray()

        try {
            val dirToRead = if (targetDir.exists() && targetDir.isDirectory) targetDir else root
            result.put("currentPath", dirToRead.absolutePath)
            result.put("parentPath", dirToRead.parent ?: root.absolutePath)

            val rawList = dirToRead.listFiles() ?: emptyArray()
            val sortedList = rawList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.US) }))

            for (file in sortedList) {
                if (file.name.startsWith(".")) continue
                val item = JSONObject().apply {
                    put("name", file.name)
                    put("path", file.absolutePath)
                    put("isDirectory", file.isDirectory)
                    put("size", if (file.isDirectory) 0L else file.length())
                    put("modified", file.lastModified())
                }
                filesArray.put(item)
            }
            result.put("files", filesArray)
        } catch (e: Exception) {
            Log.e("OpenDroid", "Error reading directory", e)
            result.put("error", e.message ?: "Failed to read directory")
        }

        return result
    }

    // --- 2. Chunked File Download (Phone ➜ PC) ---
    fun readFileChunk(filePath: String, offset: Long, chunkSize: Int = 96 * 1024): JSONObject {
        val obj = JSONObject()
        val file = File(filePath)
        if (!file.exists() || file.isDirectory) {
            obj.put("error", "File not found or is directory")
            return obj
        }

        try {
            val totalSize = file.length()
            val stream = FileInputStream(file)
            stream.skip(offset)

            val buffer = ByteArray(chunkSize)
            val bytesRead = stream.read(buffer)
            stream.close()

            if (bytesRead > 0) {
                val actualData = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                val base64 = Base64.encodeToString(actualData, Base64.NO_WRAP)

                obj.put("fileName", file.name)
                obj.put("filePath", filePath)
                obj.put("data", base64)
                obj.put("offset", offset)
                obj.put("totalSize", totalSize)
                obj.put("isLast", (offset + bytesRead) >= totalSize)
            } else {
                obj.put("isLast", true)
            }
        } catch (e: Exception) {
            Log.e("OpenDroid", "Error reading file chunk", e)
            obj.put("error", e.message)
        }
        return obj
    }

    // --- 3. Chunked File Upload (PC ➜ Phone) ---
    fun saveUploadedChunk(fileName: String, base64Data: String, isFirstChunk: Boolean): Boolean {
        return try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) downloadDir.mkdirs()

            val targetFile = File(downloadDir, fileName)
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)

            // Append mode if not first chunk
            val fos = FileOutputStream(targetFile, !isFirstChunk)
            fos.write(bytes)
            fos.flush()
            fos.close()
            true
        } catch (e: Exception) {
            Log.e("OpenDroid", "Error saving uploaded chunk", e)
            false
        }
    }

    // --- 4. Photos Gallery Query ---
    fun getRecentPhotos(): JSONArray {
        val array = JSONArray()
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED
            )
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )
            cursor?.use {
                val idIdx = it.getColumnIndex(MediaStore.Images.Media._ID)
                val nameIdx = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeIdx = it.getColumnIndex(MediaStore.Images.Media.SIZE)
                val dateIdx = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)

                var count = 0
                while (it.moveToNext() && count < 60) {
                    val obj = JSONObject().apply {
                        put("id", if (idIdx >= 0) it.getLong(idIdx) else 0L)
                        put("name", if (nameIdx >= 0) it.getString(nameIdx) else "Photo")
                        put("size", if (sizeIdx >= 0) it.getLong(sizeIdx) else 0L)
                        put("date", if (dateIdx >= 0) it.getLong(dateIdx) * 1000L else 0L)
                    }
                    array.put(obj)
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDroid", "Error querying photos", e)
        }
        return array
    }

    // --- 5. Real Storage Calculations ---
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

    // --- 6. Call Logs Query ---
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
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)

                var count = 0
                while (it.moveToNext() && count < 200) {
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
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDroid", "Error in getCallLogs", e)
        }
        return array
    }

    // --- 7. Installed Apps Query ---
    fun getInstalledApps(): JSONArray {
        val array = JSONArray()
        try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(mainIntent, 0)
            apps.sortWith { a, b ->
                a.loadLabel(pm).toString().compareTo(b.loadLabel(pm).toString(), ignoreCase = true)
            }

            for (app in apps) {
                val appName = app.loadLabel(pm).toString()
                val pkgName = app.activityInfo.packageName
                val obj = JSONObject().apply {
                    put("name", appName)
                    put("package", pkgName)
                }
                array.put(obj)
            }
        } catch (e: Exception) {
            Log.e("OpenDroid", "Error getting installed apps", e)
        }
        return array
    }

    // --- 8. Clipboard Operations ---
    fun setClipboardText(text: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("OpenDroid", text))
            } catch (e: Exception) {
                Log.e("OpenDroid", "Error setting clipboard", e)
            }
        }
    }

    fun getClipboardText(): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString() ?: ""
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    // --- 9. SMS Queries ---
    fun getRecentSms(): JSONArray {
        val array = JSONArray()
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )
            cursor?.use {
                val addrIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                val typeIdx = it.getColumnIndex(Telephony.Sms.TYPE)

                var count = 0
                while (it.moveToNext() && count < 50) {
                    val obj = JSONObject().apply {
                        put("address", if (addrIdx >= 0) it.getString(addrIdx) else "")
                        put("body", if (bodyIdx >= 0) it.getString(bodyIdx) else "")
                        put("date", if (dateIdx >= 0) it.getLong(dateIdx) else 0L)
                        put("type", if (typeIdx >= 0 && it.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_INBOX) "inbox" else "sent")
                    }
                    array.put(obj)
                    count++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

    // --- 10. Contacts Query ---
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

    // --- 11. GPS Location ---
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

    // --- 12. Make Phone Call ---
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