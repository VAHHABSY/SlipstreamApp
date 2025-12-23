package com.example.commandexecutor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    private val TAG = "CommandExecutor"

    // UI configuration elements
    private lateinit var resolversContainer: LinearLayout
        private lateinit var addResolverButton: Button
            private lateinit var domainInput: EditText
                private lateinit var tunnelSwitch: Switch

                    // UI status elements
                    private lateinit var slipstreamStatusIndicator: TextView
                        private lateinit var slipstreamStatusText: TextView
                            private lateinit var sshStatusIndicator: TextView
                                private lateinit var sshStatusText: TextView

                                    private lateinit var sharedPreferences: SharedPreferences

                                        private var isUpdatingSwitch = false

                                        private val PREF_IP_ADDRESSES_LIST = "pref_ip_addresses_list"
                                        private val PREF_DOMAIN_NAME = "pref_domain_name"
                                        private val IP_SEPARATOR = "|"

                                        private val DEFAULT_IP_LIST = listOf("1.1.1.1")
                                        private val DEFAULT_DOMAIN = "example.com"

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
                                                        val output = intent.getStringExtra(CommandService.EXTRA_ERROR_OUTPUT) ?: ""

                                                        Toast.makeText(this@MainActivity, "ERROR: $message", Toast.LENGTH_LONG).show()
                                                        updateStatusUI(slipstreamStatus = "Failed: $message", sshStatus = "Stopped")
                                                    }
                                                }
                                            }
                                        }

                                        private val requestPermissionLauncher = registerForActivityResult(
                                            ActivityResultContracts.RequestPermission()
                                        ) { isGranted: Boolean ->
                                            if (isGranted) {
                                                if (tunnelSwitch.isChecked) startCommandService()
                                            } else {
                                                Toast.makeText(this, "Notification permission is required.", Toast.LENGTH_LONG).show()
                                                syncSwitchState(false)
                                            }
                                        }

                                        override fun onCreate(savedInstanceState: Bundle?) {
                                            super.onCreate(savedInstanceState)
                                            setContentView(R.layout.activity_main)

                                            sharedPreferences = getSharedPreferences("SlipstreamPrefs", Context.MODE_PRIVATE)

                                            // Initialize UI
                                            resolversContainer = findViewById(R.id.resolvers_container)
                                            addResolverButton = findViewById(R.id.add_resolver_button)
                                            domainInput = findViewById(R.id.domain_input)
                                            tunnelSwitch = findViewById(R.id.tunnel_switch)

                                            slipstreamStatusIndicator = findViewById(R.id.slipstream_status_indicator)
                                            slipstreamStatusText = findViewById(R.id.slipstream_status_text)
                                            sshStatusIndicator = findViewById(R.id.ssh_status_indicator)
                                            sshStatusText = findViewById(R.id.ssh_status_text)

                                            loadResolvers()
                                            domainInput.setText(sharedPreferences.getString(PREF_DOMAIN_NAME, DEFAULT_DOMAIN))

                                            updateStatusUI("Stopped", "Stopped")

                                            addResolverButton.setOnClickListener {
                                                addResolverInput("", canDelete = true)
                                            }

                                            tunnelSwitch.setOnCheckedChangeListener { _, isChecked ->
                                                if (isUpdatingSwitch) return@setOnCheckedChangeListener

                                                    saveInputs()
                                                    if (isChecked) {
                                                        checkPermissionsAndStartService()
                                                    } else {
                                                        stopService(Intent(this, CommandService::class.java))
                                                        updateStatusUI("Stopping...", "Stopping...")
                                                    }
                                            }

                                            val filter = IntentFilter().apply {
                                                addAction(CommandService.ACTION_STATUS_UPDATE)
                                                addAction(CommandService.ACTION_ERROR)
                                            }
                                            LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter)
                                        }

                                        override fun onDestroy() {
                                            LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
                                            super.onDestroy()
                                        }

                                        private fun loadResolvers() {
                                            resolversContainer.removeAllViews()
                                            val storedIpsString = sharedPreferences.getString(PREF_IP_ADDRESSES_LIST, null)
                                            val storedIps = storedIpsString?.split(IP_SEPARATOR)?.filter { it.isNotBlank() } ?: DEFAULT_IP_LIST

                                            if (storedIps.isEmpty()) {
                                                addResolverInput(DEFAULT_IP_LIST.first(), canDelete = false)
                                            } else {
                                                storedIps.forEachIndexed { index, ip ->
                                                    addResolverInput(ip, canDelete = index > 0)
                                                }
                                            }
                                        }

                                        private fun addResolverInput(ip: String, canDelete: Boolean) {
                                            val rowLayout = LinearLayout(this).apply {
                                                orientation = LinearLayout.HORIZONTAL
                                                val params = LinearLayout.LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                                )
                                                params.setMargins(0, 0, 0, 16)
                                                layoutParams = params
                                            }

                                            val editText = EditText(this).apply {
                                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                                                hint = "Resolver IP (e.g., 1.1.1.1)"
                                                inputType = InputType.TYPE_CLASS_TEXT
                                                setText(ip)
                                            }
                                            rowLayout.addView(editText)

                                            if (canDelete) {
                                                val deleteButton = Button(this).apply {
                                                    layoutParams = LinearLayout.LayoutParams(
                                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                                    ).apply { marginStart = 8 }
                                                    text = "–"
                                                    setTextColor(Color.WHITE)
                                                    setBackgroundColor(Color.RED)
                                                    setOnClickListener {
                                                        resolversContainer.removeView(rowLayout)
                                                        saveInputs()
                                                    }
                                                }
                                                rowLayout.addView(deleteButton)
                                            }

                                            resolversContainer.addView(rowLayout)
                                        }

                                        private fun getIpListFromUI(): ArrayList<String> {
                                            val ipList = ArrayList<String>()
                                            for (i in 0 until resolversContainer.childCount) {
                                                val row = resolversContainer.getChildAt(i) as? ViewGroup ?: continue
                                                // Find the EditText within the row
                                                for (j in 0 until row.childCount) {
                                                    val child = row.getChildAt(j)
                                                    if (child is EditText) {
                                                        val text = child.text.toString().trim()
                                                        if (text.isNotBlank()) ipList.add(text)
                                                    }
                                                }
                                            }
                                            return ipList
                                        }

                                        private fun saveInputs() {
                                            val ipList = getIpListFromUI()
                                            sharedPreferences.edit().apply {
                                                putString(PREF_IP_ADDRESSES_LIST, ipList.joinToString(IP_SEPARATOR))
                                                putString(PREF_DOMAIN_NAME, domainInput.text.toString().trim())
                                                apply()
                                            }
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
                                            val ipList = getIpListFromUI()
                                            val domainName = domainInput.text.toString().trim()

                                            if (ipList.isEmpty() || domainName.isBlank()) {
                                                Toast.makeText(this, "At least one IP and Domain are required.", Toast.LENGTH_LONG).show()
                                                syncSwitchState(false)
                                                return
                                            }

                                            val serviceIntent = Intent(this, CommandService::class.java).apply {
                                                putStringArrayListExtra(CommandService.EXTRA_RESOLVERS, ipList)
                                                putExtra(CommandService.EXTRA_DOMAIN, domainName)
                                            }

                                            ContextCompat.startForegroundService(this, serviceIntent)
                                            updateStatusUI("Starting...", "Waiting...")
                                        }

                                        private fun updateStatusUI(slipstreamStatus: String? = null, sshStatus: String? = null) {
                                            slipstreamStatus?.let { status ->
                                                slipstreamStatusText.text = status
                                                when {
                                                    status.contains("Running", true) -> {
                                                        slipstreamStatusIndicator.text = "✔"
                                                        slipstreamStatusIndicator.setTextColor(Color.GREEN)
                                                    }
                                                    status.contains("Stopped", true) || status.contains("Failed", true) -> {
                                                        slipstreamStatusIndicator.text = "❌"
                                                        slipstreamStatusIndicator.setTextColor(Color.RED)
                                                    }
                                                    else -> {
                                                        slipstreamStatusIndicator.text = "🟡"
                                                        slipstreamStatusIndicator.setTextColor(Color.YELLOW)
                                                    }
                                                }

                                                // Sync the switch based on the reported service status
                                                val isActuallyRunning = status.contains("Running", true)
                                                val isActuallyStopped = status.contains("Stopped", true) || status.contains("Failed", true)

                                                if (isActuallyRunning) syncSwitchState(true)
                                                    else if (isActuallyStopped) syncSwitchState(false)
                                            }

                                            sshStatus?.let { status ->
                                                sshStatusText.text = status
                                                when {
                                                    status.contains("Running", true) -> {
                                                        sshStatusIndicator.text = "✔"
                                                        sshStatusIndicator.setTextColor(Color.GREEN)
                                                    }
                                                    status.contains("Stopped", true) || status.contains("Failed", true) -> {
                                                        sshStatusIndicator.text = "❌"
                                                        sshStatusIndicator.setTextColor(Color.RED)
                                                    }
                                                    else -> {
                                                        sshStatusIndicator.text = "🟡"
                                                        sshStatusIndicator.setTextColor(Color.YELLOW)
                                                    }
                                                }
                                            }
                                        }

                                        private fun syncSwitchState(checked: Boolean) {
                                            if (tunnelSwitch.isChecked != checked) {
                                                isUpdatingSwitch = true
                                                tunnelSwitch.isChecked = checked
                                                isUpdatingSwitch = false
                                            }
                                        }
}
