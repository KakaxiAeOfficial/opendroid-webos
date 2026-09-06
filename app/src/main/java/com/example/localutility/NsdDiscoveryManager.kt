package com.example.localutility

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class NsdDiscoveryManager(context: Context) {

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serviceName: String = "OpenDroidWebOS"

    companion object {
        private const val TAG = "NsdDiscoveryManager"
        private const val SERVICE_TYPE = "_http._tcp."
        private const val PORT = 8888
    }

    fun registerService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = this@NsdDiscoveryManager.serviceName
            serviceType = SERVICE_TYPE
            port = PORT
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                serviceName = serviceInfo.serviceName
                Log.d(TAG, "mDNS Service registered: $serviceName")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
        }
    }

    fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister NSD service", e)
            } finally {
                registrationListener = null
            }
        }
    }
}