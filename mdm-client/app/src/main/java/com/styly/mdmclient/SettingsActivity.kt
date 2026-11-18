package com.styly.mdmclient

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import java.text.DateFormat
import java.util.Date

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
    private lateinit var wifiPermissionHint: TextView
    private lateinit var wifiPermissionButton: Button
    private var hadWifiLocationPermissions = false
    private lateinit var configurationGroup: TextView
    private lateinit var configurationDeployment: TextView
    private lateinit var configurationApplyStatus: TextView
    private lateinit var reapplyButton: Button

    private val configurationController get() = MdmClientApplication.configurationPackageController()
    private var configurationVisible = false
    private var reapplyPending = false
    private val configurationListener: () -> Unit = {
        runOnUiThread { if (configurationVisible) updateConfigurationUi() }
    }

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
        wifiPermissionHint = findViewById(R.id.wifi_permission_hint)
        wifiPermissionButton = findViewById(R.id.wifi_location_permission_button)
        configurationGroup = findViewById(R.id.configuration_group)
        configurationDeployment = findViewById(R.id.configuration_deployment)
        configurationApplyStatus = findViewById(R.id.configuration_apply_status)
        reapplyButton = findViewById(R.id.configuration_reapply)

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

        wifiPermissionButton.setOnClickListener { requestWifiPermission() }

        hadWifiLocationPermissions = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        startMdmService()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MdmClientService.ACTION_STATUS_UPDATE)
        registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        // Re-check here so returning from the system settings screen refreshes the UI.
        updateStoragePermissionUi()
        refreshWifiPermissions()
        configurationVisible = true
        configurationController.addScreenListener(configurationListener)
        updateConfigurationUi()
        permissionRecoveryPending = hasAllFilesAccess()
        MdmClientApplication.deviceIdentityResolver().addListener(identityListener)
        refreshJournal()
    }

    private fun refreshJournal() {
        journalText.text = UpdateJournal.format(this)
    }

    override fun onPause() {
        super.onPause()
        configurationVisible = false
        configurationController.removeScreenListener(configurationListener)
        permissionRecoveryPending = false
        unregisterReceiver(statusReceiver)
        MdmClientApplication.deviceIdentityResolver().removeListener(identityListener)
    }

    private fun updateConfigurationUi() {
        val state = configurationController.screenSnapshot()
        val version = state?.version
        configurationGroup.text = when {
            state?.groupName != null -> getString(R.string.configuration_group, state.groupName)
            state?.groupKnown == true -> getString(R.string.configuration_group_none)
            else -> getString(R.string.configuration_group_unknown)
        }
        configurationDeployment.visibility =
            if (version != null || state?.groupName != null) View.VISIBLE else View.GONE
        configurationDeployment.text = when {
            version == null -> getString(R.string.configuration_not_deployed)
            state.deployedAtMillis == null -> getString(R.string.configuration_deployment_unknown)
            else -> getString(
                R.string.configuration_deployment,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(state.deployedAtMillis)),
            )
        }
        val status = state?.applyStatus
        val statusText = when {
            version == null -> 0
            status == null -> R.string.configuration_unsupported
            status == ConfigurationApplyStatus.APPLYING -> R.string.configuration_applying
            status == ConfigurationApplyStatus.WAITING -> R.string.configuration_waiting
            status == ConfigurationApplyStatus.APPLIED -> R.string.configuration_applied
            else -> R.string.configuration_incomplete
        }
        configurationApplyStatus.visibility = if (statusText != 0) View.VISIBLE else View.GONE
        if (statusText != 0) configurationApplyStatus.setText(statusText)
        reapplyButton.visibility = if (status != null) View.VISIBLE else View.GONE
        reapplyButton.isEnabled = state?.canReapply == true && !reapplyPending
        reapplyButton.setOnClickListener {
            if (version != null && !reapplyPending) reapplyConfiguration(version)
        }
    }

    private fun reapplyConfiguration(version: ConfigurationVersion) {
        reapplyPending = true
        reapplyButton.isEnabled = false
        configurationController.reapply(version) { started ->
            runOnUiThread {
                reapplyPending = false
                if (configurationVisible) {
                    updateConfigurationUi()
                    if (!started) {
                        Toast.makeText(this, R.string.configuration_reapply_unavailable, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
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

        restartService("Reconnecting...")
    }

    private fun useAutoDiscovery() {
        WebSocketManager.clearServerUrlsForAutoDiscovery(this)
        serverUrlInput.text.clear()
        restartService("Discovering...")
    }

    private fun restartService(status: String) {
        stopService(Intent(this, MdmClientService::class.java))
        startMdmService()
        statusText.text = status
    }

    private fun startMdmService() {
        startForegroundService(
            Intent(this, MdmClientService::class.java)
                .putExtra(MdmClientService.EXTRA_START_REASON, MdmClientService.REASON_SETTINGS)
        )
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
            storagePermissionStatus.setTextColor(getColor(R.color.permission_status_granted))
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

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun locationServicesEnabled(): Boolean =
        getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    private fun refreshWifiPermissions() {
        val preciseLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val backgroundLocation = hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val overlay = hasManagedWifiReadAccess(this)
        val locationEnabled = locationServicesEnabled()
        val diagnostics = hasManagedWifiDiagnosticsAccess(this)
        updateWifiPermissionRow(R.id.wifi_precise_location_status, R.string.wifi_precise_location, preciseLocation)
        updateWifiPermissionRow(R.id.wifi_background_location_status, R.string.wifi_background_location, backgroundLocation)
        updateWifiPermissionRow(R.id.wifi_overlay_status, R.string.wifi_overlay, overlay)
        updateWifiPermissionRow(R.id.wifi_diagnostics_status, R.string.wifi_diagnostics, diagnostics)
        updateWifiStatusRow(
            R.id.wifi_location_services_status,
            R.string.wifi_location_services,
            getText(if (locationEnabled) R.string.wifi_location_services_on else R.string.wifi_location_services_off),
        )

        val settingsHint = when {
            !preciseLocation -> R.string.wifi_precise_location_permission_missing
            !backgroundLocation -> R.string.wifi_background_location_permission_missing
            !overlay -> R.string.wifi_overlay_permission_missing
            !locationEnabled -> R.string.wifi_location_services_disabled
            else -> null
        }
        val hint = settingsHint ?: if (diagnostics) null else R.string.wifi_diagnostics_permission_missing
        wifiPermissionHint.visibility = if (hint == null) View.GONE else View.VISIBLE
        if (hint != null) wifiPermissionHint.setText(hint)
        wifiPermissionButton.visibility = if (settingsHint == null) View.GONE else View.VISIBLE

        val hasLocationPermissions = preciseLocation && backgroundLocation
        val locationPermissionsAcquired = hasLocationPermissions && !hadWifiLocationPermissions
        hadWifiLocationPermissions = hasLocationPermissions
        if (locationPermissionsAcquired) {
            // Returning from system settings does not deliver a runtime permission result.
            startMdmService()
        } else {
            MdmClientApplication.wifiSettingsController().refreshReadiness()
        }
    }

    private fun updateWifiPermissionRow(@IdRes viewId: Int, @StringRes label: Int, granted: Boolean) {
        val status = if (granted) {
            buildSpannedString {
                color(getColor(R.color.permission_status_granted)) {
                    append(getText(R.string.wifi_permission_granted))
                }
            }
        } else {
            getText(R.string.wifi_permission_not_granted)
        }
        updateWifiStatusRow(viewId, label, status)
    }

    private fun updateWifiStatusRow(@IdRes viewId: Int, @StringRes label: Int, status: CharSequence) {
        findViewById<TextView>(viewId).text =
            TextUtils.expandTemplate(getText(R.string.wifi_status_format), getText(label), status)
    }

    private fun requestWifiPermission() {
        val fineLocationMissing = !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val permissions = getPreferences(MODE_PRIVATE)
        if (fineLocationMissing && (!permissions.getBoolean("requested_fine_location", false) ||
                shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION))) {
            permissions.edit().putBoolean("requested_fine_location", true).apply()
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_WIFI_LOCATION_PERMISSION,
            )
            return
        }

        val packageUri = Uri.fromParts("package", packageName, null)
        val intent = when {
            fineLocationMissing || !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            !hasManagedWifiReadAccess(this) -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
            !locationServicesEnabled() -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            else -> return
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val message = if (intent.action == Settings.ACTION_MANAGE_OVERLAY_PERMISSION) {
                R.string.wifi_overlay_settings_unavailable
            } else {
                R.string.wifi_location_settings_unavailable
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Use the activity result API when migrating the settings screen")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WIFI_LOCATION_PERMISSION) refreshWifiPermissions()
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
