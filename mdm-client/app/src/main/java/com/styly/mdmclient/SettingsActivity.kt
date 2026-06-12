package com.styly.mdmclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
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
    private lateinit var discoverButton: Button

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.getBooleanExtra(MdmClientService.EXTRA_CONNECTED, false)
            val message = intent.getStringExtra(MdmClientService.EXTRA_MESSAGE) ?: ""
            updateStatusDisplay(connected, message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        serverUrlInput = findViewById(R.id.server_url_input)
        statusText = findViewById(R.id.status_text)
        saveButton = findViewById(R.id.save_button)
        discoverButton = findViewById(R.id.discover_button)

        // Load current server URL
        val prefs = getSharedPreferences("stylymdm_prefs", MODE_PRIVATE)
        val currentUrl = prefs.getString("server_url", "ws://192.168.1.100:7070/ws/device") ?: ""
        serverUrlInput.setText(currentUrl)

        saveButton.setOnClickListener {
            saveAndRestart()
        }

        discoverButton.setOnClickListener {
            discoverServer()
        }

        // Start the service if not already running
        startForegroundService(Intent(this, MdmClientService::class.java))
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MdmClientService.ACTION_STATUS_UPDATE)
        registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun discoverServer() {
        discoverButton.isEnabled = false
        discoverButton.text = "Discovering..."
        Thread {
            val url = ServerDiscovery.discover()
            runOnUiThread {
                discoverButton.isEnabled = true
                discoverButton.text = getString(R.string.discover_server)
                if (url != null) {
                    serverUrlInput.setText(url)
                    Toast.makeText(this, "Server found!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No server found on LAN", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun saveAndRestart() {
        val url = serverUrlInput.text.toString().trim()
        if (url.isEmpty()) return

        // Save the URL
        val prefs = getSharedPreferences("stylymdm_prefs", MODE_PRIVATE)
        prefs.edit().putString("server_url", url).apply()

        // Restart the service to apply new URL
        stopService(Intent(this, MdmClientService::class.java))
        startForegroundService(Intent(this, MdmClientService::class.java))

        statusText.text = "Reconnecting..."
    }

    private fun updateStatusDisplay(connected: Boolean, message: String) {
        statusText.text = if (connected) "Connected" else message
        statusText.setTextColor(
            if (connected) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt()
        )
    }
}
