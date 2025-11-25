package com.example.commandexecutor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val TAG = "CommandExecutor"
    private lateinit var ipInput: EditText       // Field for IP
        private lateinit var domainInput: EditText   // Field for Domain
            private lateinit var startButton: Button
                private lateinit var stopButton: Button

                    private lateinit var sharedPreferences: SharedPreferences

                        // Keys for SharedPreferences
                        private val PREF_IP_ADDRESS = "pref_ip_address"
                        private val PREF_DOMAIN_NAME = "pref_domain_name"
                        private val DEFAULT_IP = "1.1.1.1"
                        private val DEFAULT_DOMAIN = "example.com"


                        // Request permission launcher for Android 13+ notifications
                        private val requestPermissionLauncher = registerForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { isGranted: Boolean ->
                            if (isGranted) {
                                Log.d(TAG, "Notification permission granted.")
                                startCommandService()
                            } else {
                                Toast.makeText(this, "Notification permission is required to run the background service.", Toast.LENGTH_LONG).show()
                            }
                        }

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            setContentView(R.layout.activity_main)

                            // Initialize SharedPreferences
                            sharedPreferences = getSharedPreferences("SlipstreamPrefs", Context.MODE_PRIVATE)

                            // Initialize UI components using IDs from the layout
                            ipInput = findViewById(R.id.ip_input)
                            domainInput = findViewById(R.id.domain_input)
                            startButton = findViewById(R.id.start_button)
                            stopButton = findViewById(R.id.stop_button)

                            // --- Load stored values or use defaults ---
                            val storedIp = sharedPreferences.getString(PREF_IP_ADDRESS, DEFAULT_IP)
                            val storedDomain = sharedPreferences.getString(PREF_DOMAIN_NAME, DEFAULT_DOMAIN)

                            ipInput.setText(storedIp)
                            domainInput.setText(storedDomain)

                            // --- Start Service Logic ---
                            startButton.setOnClickListener {
                                // Save inputs before starting the service
                                saveInputs()
                                checkPermissionsAndStartService()
                            }

                            // --- Stop Service Logic ---
                            stopButton.setOnClickListener {
                                stopService(Intent(this, CommandService::class.java))
                                Toast.makeText(this, "Command Service Stopped", Toast.LENGTH_SHORT).show()
                            }
                        }

                        private fun saveInputs() {
                            val editor = sharedPreferences.edit()
                            editor.putString(PREF_IP_ADDRESS, ipInput.text.toString().trim())
                            editor.putString(PREF_DOMAIN_NAME, domainInput.text.toString().trim())
                            editor.apply()
                            Log.d(TAG, "Saved IP and Domain to SharedPreferences.")
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

                            if (ipAddress.isBlank() || domainName.isBlank()) {
                                Toast.makeText(this, "IP Address and Domain are required for slipstream-client.", Toast.LENGTH_LONG).show()
                                return
                            }

                            val serviceIntent = Intent(this, CommandService::class.java).apply {
                                // Pass IP and Domain using the constants defined in CommandService
                                putExtra(CommandService.EXTRA_IP_ADDRESS, ipAddress)
                                putExtra(CommandService.EXTRA_DOMAIN, domainName)
                            }

                            // Start the service as a foreground service
                            ContextCompat.startForegroundService(this, serviceIntent)
                            Toast.makeText(this, "Service running: IP=$ipAddress, Domain=$domainName", Toast.LENGTH_LONG).show()
                        }
}
