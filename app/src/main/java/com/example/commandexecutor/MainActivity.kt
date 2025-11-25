package com.example.commandexecutor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    private val TAG = "CommandExecutor"
    private lateinit var ipInput: EditText
        private lateinit var domainInput: EditText
            private lateinit var tunnelSwitch: Switch
                // Removed monitoringCheckbox and thresholdInput references

                // UI status elements
                private lateinit var slipstreamStatusIndicator: TextView
                    private lateinit var slipstreamStatusText: TextView
                        private lateinit var sshStatusIndicator: TextView
                            private lateinit var sshStatusText: TextView

                                private lateinit var sharedPreferences: SharedPreferences

                                    // Flag to prevent infinite loop when programmatically changing the switch state
                                    private var isUpdatingSwitch = false

                                    // Keys for SharedPreferences (Monitoring keys removed)
                                    private val PREF_IP_ADDRESS = "pref_ip_address"
                                    private val PREF_DOMAIN_NAME = "pref_domain_name"

                                    private val DEFAULT_IP = "1.1.1.1"
                                    private val DEFAULT_DOMAIN = "example.com"
                                    // DEFAULT_THRESHOLD removed as it's no longer configured by the user

                                    // Broadcast Receiver to handle status updates from the service
                                    private val statusReceiver = object : BroadcastReceiver() {
                                        override fun onReceive(context: Context?, intent: Intent?) {
                                            when (intent?.action) {
                                                CommandService.ACTION_STATUS_UPDATE -> {
                                                    val slipstreamStatus = intent.getStringExtra(CommandService.EXTRA_STATUS_SLIPSTREAM)
                                                    val sshStatus = intent.getStringExtra(CommandService.EXTRA_STATUS_SSH)
                                                    updateStatusUI(slipstreamStatus, sshStatus)
                                                }
                                                CommandService.ACTION_ERROR -> {
                                                    val message = intent.getStringExtra(CommandService.EXTRA_ERROR_MESSAGE) ?: "Unknown Error"
                                                    val output = intent.getStringExtra(CommandService.EXTRA_ERROR_OUTPUT) ?: "No output provided."

                                                    // Show error as Toast, including the output from slipstream
                                                    Toast.makeText(this@MainActivity, "ERROR: $message\n\nOutput:\n$output", Toast.LENGTH_LONG).show()

                                                    // Update UI to reflect failure
                                                    updateStatusUI(slipstreamStatus = "Failed: $message", sshStatus = "Stopped")
                                                }
                                            }
                                        }
                                    }


                                    // Request permission launcher for Android 13+ notifications
                                    private val requestPermissionLauncher = registerForActivityResult(
                                        ActivityResultContracts.RequestPermission()
                                    ) { isGranted: Boolean ->
                                        if (isGranted) {
                                            Log.d(TAG, "Notification permission granted.")
                                            // Retry starting the service after permission is granted
                                            if (tunnelSwitch.isChecked) {
                                                startCommandService()
                                            }
                                        } else {
                                            Toast.makeText(this, "Notification permission is required to run the background service.", Toast.LENGTH_LONG).show()
                                            // Turn off the switch if permission is denied
                                            if (!isUpdatingSwitch) {
                                                tunnelSwitch.isChecked = false
                                            }
                                        }
                                    }

                                    override fun onCreate(savedInstanceState: Bundle?) {
                                        super.onCreate(savedInstanceState)
                                        setContentView(R.layout.activity_main)

                                        // Initialize SharedPreferences
                                        sharedPreferences = getSharedPreferences("SlipstreamPrefs", Context.MODE_PRIVATE)

                                        // Initialize UI components from the layout
                                        ipInput = findViewById(R.id.ip_input)
                                        domainInput = findViewById(R.id.domain_input)
                                        tunnelSwitch = findViewById(R.id.tunnel_switch)
                                        // Monitoring UI elements were removed from layout

                                        // Initialize UI status components
                                        slipstreamStatusIndicator = findViewById(R.id.slipstream_status_indicator)
                                        slipstreamStatusText = findViewById(R.id.slipstream_status_text)
                                        sshStatusIndicator = findViewById(R.id.ssh_status_indicator)
                                        sshStatusText = findViewById(R.id.ssh_status_text)

                                        // --- Load stored values or use defaults ---
                                        ipInput.setText(sharedPreferences.getString(PREF_IP_ADDRESS, DEFAULT_IP))
                                        domainInput.setText(sharedPreferences.getString(PREF_DOMAIN_NAME, DEFAULT_DOMAIN))

                                        // --- Set initial status ---
                                        updateStatusUI("Stopped", "Stopped")

                                        // --- Tunnel Switch Logic ---
                                        tunnelSwitch.setOnCheckedChangeListener { _, isChecked ->
                                            // Ignore programmatic changes to the switch that happen during status updates
                                            if (isUpdatingSwitch) return@setOnCheckedChangeListener

                                                // Save inputs upon interaction
                                                saveInputs()

                                                if (isChecked) {
                                                    // Tunnel ON: Check permissions and start service
                                                    checkPermissionsAndStartService()
                                                } else {
                                                    // Tunnel OFF: Stop service
                                                    stopService(Intent(this, CommandService::class.java))
                                                    Toast.makeText(this, "Command Service Stop Signal Sent", Toast.LENGTH_SHORT).show()
                                                    // Update UI immediately to reflect pending stop
                                                    updateStatusUI("Stopping...", "Stopping...")
                                                }
                                        }

                                        // --- Register Broadcast Receiver ---
                                        LocalBroadcastManager.getInstance(this).registerReceiver(
                                            statusReceiver,
                                            IntentFilter(CommandService.ACTION_STATUS_UPDATE)
                                        )
                                        LocalBroadcastManager.getInstance(this).registerReceiver(
                                            statusReceiver,
                                            IntentFilter(CommandService.ACTION_ERROR)
                                        )
                                    }

                                    override fun onDestroy() {
                                        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
                                        super.onDestroy()
                                    }

                                    private fun saveInputs() {
                                        val editor = sharedPreferences.edit()
                                        editor.putString(PREF_IP_ADDRESS, ipInput.text.toString().trim())
                                        editor.putString(PREF_DOMAIN_NAME, domainInput.text.toString().trim())
                                        // Monitoring preferences removal confirmed
                                        editor.apply()
                                        Log.d(TAG, "Saved configuration to SharedPreferences.")
                                    }

                                    private fun checkPermissionsAndStartService() {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                                startCommandService()
                                            } else {
                                                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        } else {
                                            startCommandService()
                                        }
                                    }

                                    private fun startCommandService() {
                                        // Retrieve and validate inputs
                                        val ipAddress = ipInput.text.toString().trim()
                                        val domainName = domainInput.text.toString().trim()

                                        // Hardcode monitoring settings since they are no longer user-configurable
                                        val monitoringEnabled = false
                                        val monitoringThreshold = 0L


                                        if (ipAddress.isBlank() || domainName.isBlank()) {
                                            Toast.makeText(this, "IP Address and Domain are required for slipstream-client.", Toast.LENGTH_LONG).show()

                                            // Turn off the switch since we can't start due to missing inputs
                                            if (!isUpdatingSwitch) {
                                                tunnelSwitch.isChecked = false
                                            }
                                            return
                                        }

                                        Log.i(TAG, "Starting service. Monitor: $monitoringEnabled, Threshold: $monitoringThreshold ms (Hardcoded)")

                                        val serviceIntent = Intent(this, CommandService::class.java).apply {
                                            putExtra(CommandService.EXTRA_IP_ADDRESS, ipAddress)
                                            putExtra(CommandService.EXTRA_DOMAIN, domainName)
                                            // Pass hardcoded values for Service compatibility
                                            putExtra(CommandService.EXTRA_MONITORING_ENABLED, monitoringEnabled)
                                            putExtra(CommandService.EXTRA_MONITORING_THRESHOLD, monitoringThreshold)
                                        }

                                        ContextCompat.startForegroundService(this, serviceIntent)
                                        Toast.makeText(this, "Service Start Signal Sent", Toast.LENGTH_SHORT).show()

                                        updateStatusUI("Starting...", "Waiting...")
                                    }

                                    /**
                                     * Updates the status indicators and switch state based on broadcasts from the service.
                                     */
                                    private fun updateStatusUI(slipstreamStatus: String? = null, sshStatus: String? = null) {

                                        // 1. Update Slipstream Status Indicators
                                        slipstreamStatus?.let { status ->
                                            slipstreamStatusText.text = status
                                            when {
                                                status.contains("Running", ignoreCase = true) -> {
                                                    slipstreamStatusIndicator.text = "✔" // Green Checkmark
                                                    slipstreamStatusIndicator.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                                                }
                                                status.contains("Stopped", ignoreCase = true) || status.contains("Failed", ignoreCase = true) -> {
                                                    slipstreamStatusIndicator.text = "❌" // Red X
                                                    slipstreamStatusIndicator.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                                                }
                                                else -> { // Starting, Waiting, Cleaning up...
                                                    slipstreamStatusIndicator.text = "🟡" // Yellow Circle
                                                    slipstreamStatusIndicator.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                                                }
                                            }
                                        }

                                        // 2. Update SSH Status Indicators
                                        sshStatus?.let { status ->
                                            sshStatusText.text = status
                                            when {
                                                status.contains("Running", ignoreCase = true) -> {
                                                    sshStatusIndicator.text = "✔" // Green Checkmark
                                                    sshStatusIndicator.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                                                }
                                                status.contains("Stopped", ignoreCase = true) || status.contains("Failed", ignoreCase = true) -> {
                                                    sshStatusIndicator.text = "❌" // Red X
                                                    sshStatusIndicator.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                                                }
                                                else -> { // Starting, Waiting, Cleaning up...
                                                    sshStatusIndicator.text = "🟡" // Yellow Circle
                                                    sshStatusIndicator.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                                                }
                                            }
                                        }

                                        // 3. Update Switch State based on Slipstream status (which is the primary process)
                                        slipstreamStatus?.let { status ->
                                            if (isUpdatingSwitch) return@let // Prevent re-triggering the listener

                                                val isRunning = status.contains("Running", ignoreCase = true)
                                                val isStopped = status.contains("Stopped", ignoreCase = true) || status.contains("Failed", ignoreCase = true)

                                                try {
                                                    isUpdatingSwitch = true
                                                    if (isRunning && !tunnelSwitch.isChecked) {
                                                        tunnelSwitch.isChecked = true
                                                    } else if (isStopped && tunnelSwitch.isChecked) {
                                                        // Turn off the switch if the service stopped or failed
                                                        tunnelSwitch.isChecked = false
                                                    }
                                                } finally {
                                                    isUpdatingSwitch = false
                                                }
                                        }
                                    }
}
