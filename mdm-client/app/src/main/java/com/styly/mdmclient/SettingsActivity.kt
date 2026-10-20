package com.styly.mdmclient

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings screen for configuring the STYLY-MDM server address
 * and viewing the current connection status.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var saveButton: Button
    private lateinit var autoDiscoveryButton: Button
    private lateinit var storagePermissionStatus: TextView
    private lateinit var grantStorageButton: Button
    private lateinit var deviceIdentityStatus: TextView
    private lateinit var journalText: TextView
    private lateinit var journalRefreshButton: Button
    private lateinit var journalClearButton: Button
    private lateinit var wifiTlsEnabled: CheckBox
    private lateinit var exactAlarmStatus: TextView
    private lateinit var exactAlarmButton: Button
    private lateinit var wifiLocationStatus: TextView
    private lateinit var wifiLocationButton: Button

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.getBooleanExtra(MdmClientService.EXTRA_CONNECTED, false)
            val message = intent.getStringExtra(MdmClientService.EXTRA_MESSAGE) ?: ""
            updateStatusDisplay(connected, message)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var permissionRecoveryPending = false

    private val identityListener: (DeviceIdentityState) -> Unit = { state ->
        runOnUiThread {
            updateDeviceIdentityUi(state)
            if (state is DeviceIdentityState.Unavailable &&
                state.status == DeviceIdentityStatus.ACCESS_DENIED
            ) {
                // Defer until the current result has reached every listener.
                mainHandler.post {
                    if (permissionRecoveryPending && hasAllFilesAccess()) {
                        permissionRecoveryPending = false
                        MdmClientApplication.deviceIdentityResolver().retryAfterPermissionGranted()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // This is the LAUNCHER activity, so the PICO keep-alive may relaunch the app *through
        // it* rather than starting the service directly. Recorded before the service is
        // started below, so the journal shows which route brought the client back.
        UpdateJournal.record(this, UpdateJournal.EVENT_ACTIVITY_ONCREATE)

        // Surface the installed build on the settings screen (VERSION_NAME already carries
        // the "-dev" suffix for the dev flavor, so no special-casing is needed here).
        findViewById<TextView>(R.id.app_version).text = "v${BuildConfig.VERSION_NAME}"

        serverUrlInput = findViewById(R.id.server_url_input)
        statusText = findViewById(R.id.status_text)
        saveButton = findViewById(R.id.save_button)
        autoDiscoveryButton = findViewById(R.id.use_auto_discovery_button)
        storagePermissionStatus = findViewById(R.id.storage_permission_status)
        grantStorageButton = findViewById(R.id.grant_storage_button)
        deviceIdentityStatus = findViewById(R.id.device_identity_status)
        journalText = findViewById(R.id.journal_text)
        journalRefreshButton = findViewById(R.id.journal_refresh_button)
        journalClearButton = findViewById(R.id.journal_clear_button)
        wifiTlsEnabled = findViewById(R.id.wifi_tls_enabled)
        exactAlarmStatus = findViewById(R.id.wifi_exact_alarm_status)
        exactAlarmButton = findViewById(R.id.wifi_exact_alarm_button)
        wifiLocationStatus = findViewById(R.id.wifi_location_permission_status)
        wifiLocationButton = findViewById(R.id.wifi_location_permission_button)

        val connectionSettings = MdmClientApplication.wifiSettingsController().connectionSettings()
        wifiTlsEnabled.isChecked = connectionSettings.tlsEnabled

        serverUrlInput.setText(WebSocketManager.getManualServerAddress(this).orEmpty())

        saveButton.setOnClickListener {
            saveAndRestart()
        }

        autoDiscoveryButton.setOnClickListener {
            useAutoDiscovery()
        }

        grantStorageButton.setOnClickListener {
            requestAllFilesAccess()
        }

        journalRefreshButton.setOnClickListener {
            refreshJournal()
        }

        journalClearButton.setOnClickListener {
            UpdateJournal.clear(this)
            refreshJournal()
        }

        exactAlarmButton.setOnClickListener { requestExactAlarmAccess() }
        wifiLocationButton.setOnClickListener { requestWifiLocationPermission() }

        // Start the service if not already running
        startForegroundService(
            Intent(this, MdmClientService::class.java)
                .putExtra(MdmClientService.EXTRA_START_REASON, MdmClientService.REASON_SETTINGS)
        )
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MdmClientService.ACTION_STATUS_UPDATE)
        registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        // Re-check here so returning from the system settings screen refreshes the UI.
        updateStoragePermissionUi()
        updateExactAlarmUi()
        updateWifiLocationUi()
        permissionRecoveryPending = hasAllFilesAccess()
        MdmClientApplication.deviceIdentityResolver().addListener(identityListener)
        refreshJournal()
    }

    private fun refreshJournal() {
        journalText.text = UpdateJournal.format(this)
    }

    override fun onPause() {
        super.onPause()
        permissionRecoveryPending = false
        unregisterReceiver(statusReceiver)
        MdmClientApplication.deviceIdentityResolver().removeListener(identityListener)
    }

    private fun saveAndRestart() {
        val url = serverUrlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(
                this,
                "Enter a manual server address or use Auto-Discovery",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        if (!WebSocketManager.saveManualServerUrl(this, url)) {
            Toast.makeText(
                this,
                "Enter a valid server address such as 192.168.1.100:7070",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        if (!MdmClientApplication.wifiSettingsController().saveConnectionSettings(
                WifiConnectionSettings(tlsEnabled = wifiTlsEnabled.isChecked),
            )
        ) {
            Toast.makeText(this, R.string.wifi_settings_save_failed, Toast.LENGTH_LONG).show()
            return
        }
        restartService("Reconnecting...")
    }

    private fun useAutoDiscovery() {
        WebSocketManager.clearServerUrlsForAutoDiscovery(this)
        serverUrlInput.text.clear()
        restartService("Discovering...")
    }

    private fun restartService(status: String) {
        stopService(Intent(this, MdmClientService::class.java))
        startForegroundService(
            Intent(this, MdmClientService::class.java)
                .putExtra(MdmClientService.EXTRA_START_REASON, MdmClientService.REASON_SETTINGS)
        )
        statusText.text = status
    }

    /**
     * MANAGE_EXTERNAL_STORAGE ("All files access") is required so the PICO ToBService can read
     * downloaded APKs from shared storage. It is an appop, not a runtime permission, so it cannot
     * be requested with a standard permission dialog — the user is sent to the system settings.
     */
    private fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    private fun updateStoragePermissionUi() {
        if (hasAllFilesAccess()) {
            storagePermissionStatus.setText(R.string.storage_permission_granted)
            storagePermissionStatus.setTextColor(0xFF4CAF50.toInt())
            grantStorageButton.visibility = View.GONE
        } else {
            storagePermissionStatus.setText(R.string.storage_permission_not_granted)
            storagePermissionStatus.setTextColor(0xFFFF5722.toInt())
            grantStorageButton.visibility = View.VISIBLE
        }
    }

    private fun updateDeviceIdentityUi(state: DeviceIdentityState) {
        when (state) {
            DeviceIdentityState.Resolving -> {
                deviceIdentityStatus.setText(R.string.device_identity_resolving)
                deviceIdentityStatus.setTextColor(0xFFFFA000.toInt())
            }
            is DeviceIdentityState.Ready -> {
                deviceIdentityStatus.text = getString(
                    R.string.device_identity_ready,
                    state.deviceId,
                )
                deviceIdentityStatus.setTextColor(0xFF4CAF50.toInt())
            }
            is DeviceIdentityState.Unavailable -> {
                deviceIdentityStatus.text = getString(
                    R.string.device_identity_unavailable,
                    state.status.protocolValue,
                    state.diagnostic,
                )
                deviceIdentityStatus.setTextColor(0xFFFF5722.toInt())
            }
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val packageUri = Uri.fromParts("package", packageName, null)
        // Prefer the per-app screen; fall back to the global list for OEMs that lack it.
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next fallback.
            }
        }
        Toast.makeText(this, R.string.storage_permission_settings_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun hasExactAlarmAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = getSystemService(ALARM_SERVICE) as? android.app.AlarmManager ?: return false
        return manager.canScheduleExactAlarms()
    }

    private fun updateExactAlarmUi() {
        val granted = hasExactAlarmAccess()
        exactAlarmStatus.setText(if (granted) R.string.wifi_exact_alarm_granted else R.string.wifi_exact_alarm_missing)
        exactAlarmStatus.setTextColor(if (granted) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt())
        exactAlarmButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun hasWifiLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun updateWifiLocationUi() {
        val granted = hasWifiLocationPermission()
        val locationEnabled = (getSystemService(LOCATION_SERVICE) as? android.location.LocationManager)
            ?.isLocationEnabled == true
        val ready = granted && locationEnabled
        wifiLocationStatus.setText(
            if (ready) R.string.wifi_location_permission_granted
            else if (granted) R.string.wifi_location_services_disabled
            else R.string.wifi_location_permission_missing,
        )
        wifiLocationStatus.setTextColor(if (ready) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt())
        wifiLocationButton.visibility = if (ready) View.GONE else View.VISIBLE
    }

    private fun requestWifiLocationPermission() {
        if (!hasWifiLocationPermission()) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_WIFI_LOCATION_PERMISSION,
            )
        } else {
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.wifi_location_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Use the activity result API when migrating the settings screen")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WIFI_LOCATION_PERMISSION) {
            updateWifiLocationUi()
            if (hasWifiLocationPermission()) {
                startForegroundService(Intent(this, MdmClientService::class.java)
                    .putExtra(MdmClientService.EXTRA_START_REASON, MdmClientService.REASON_SETTINGS))
            }
        }
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", packageName, null)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.wifi_exact_alarm_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQUEST_WIFI_LOCATION_PERMISSION = 8702
    }

    private fun updateStatusDisplay(connected: Boolean, message: String) {
        statusText.text = if (connected && message.isEmpty()) "Connected" else message
        statusText.setTextColor(
            if (connected) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt()
        )
    }
}
