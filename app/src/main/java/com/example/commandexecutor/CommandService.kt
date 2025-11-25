package com.example.commandexecutor

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

// Data class to hold the execution result (for clarity in complex logic)
data class CommandResult(val exitCode: Int, val output: String)

// CommandService now implements CoroutineScope to correctly handle 'launch' and 'isActive'
class CommandService : LifecycleService(), CoroutineScope {

    // Define the CoroutineScope for the service lifecycle (SupervisorJob to prevent child failures from killing others)
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO

    private val TAG = "CommandService"
    private val NOTIFICATION_CHANNEL_ID = "CommandServiceChannel"
    private val NOTIFICATION_ID = 101

    // Global variables for tunnel management
    private var slipstreamProcess: Process? = null
        private var sshProcess: Process? = null
            private var processOutputReaderJob: Job? = null

                // Mutex to prevent simultaneous starting/stopping/restarting
                private val tunnelMutex = Mutex()

                // Configuration variables passed from Activity (kept for completeness, though unused in logic)
                private var monitoringEnabled = false
                private var monitoringThreshold = 3000L
                private var ipAddressConfig: String = ""
                    private var domainNameConfig: String = ""

                        companion object {
                            // Inputs & Config
                            const val EXTRA_IP_ADDRESS = "ip_address"
                            const val EXTRA_DOMAIN = "domain_name"
                            const val EXTRA_MONITORING_ENABLED = "monitoring_enabled"
                            const val EXTRA_MONITORING_THRESHOLD = "monitoring_threshold"

                            // Corrected binary name
                            const val BINARY_NAME = "slipstream-client"

                            // Broadcast actions
                            const val ACTION_STATUS_UPDATE = "com.example.commandexecutor.STATUS_UPDATE"
                            const val ACTION_ERROR = "com.example.commandexecutor.ERROR"

                            // Broadcast extras
                            const val EXTRA_STATUS_SLIPSTREAM = "status_slipstream"
                            const val EXTRA_STATUS_SSH = "status_ssh"
                            const val EXTRA_ERROR_MESSAGE = "error_message"
                            const val EXTRA_ERROR_OUTPUT = "error_output"
                        }

                        override fun onCreate() {
                            super.onCreate()
                            createNotificationChannel()
                        }

                        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                            super.onStartCommand(intent, flags, startId)

                            // Retrieve and SAVE configuration into member variables
                            ipAddressConfig = intent?.getStringExtra(EXTRA_IP_ADDRESS) ?: ""
                            domainNameConfig = intent?.getStringExtra(EXTRA_DOMAIN) ?: ""
                            monitoringEnabled = intent?.getBooleanExtra(EXTRA_MONITORING_ENABLED, false) ?: false
                            monitoringThreshold = intent?.getLongExtra(EXTRA_MONITORING_THRESHOLD, 3000L) ?: 3000L

                            Log.d(TAG, "Service started. IP: $ipAddressConfig, Domain: $domainNameConfig, Monitor: $monitoringEnabled, Threshold: $monitoringThreshold ms")

                            // Start foreground service with clickable notification
                            startForeground(NOTIFICATION_ID, buildForegroundNotification())

                            // Use the service's CoroutineScope (Dispatchers.IO) via 'launch'
                            launch {
                                // Start the tunnel sequence
                                startTunnelSequence(ipAddressConfig, domainNameConfig)
                            }

                            return START_STICKY
                        }

                        /**
                         * The main execution sequence: cleans up, copies binary, and starts the tunnel.
                         * Monitoring logic has been removed entirely.
                         */
                        private suspend fun startTunnelSequence(ipAddress: String, domainName: String) {
                            tunnelMutex.withLock {

                                sendStatusUpdate(slipstreamStatus = "Cleaning up...", sshStatus = "Waiting...")
                                cleanUpLingeringProcesses()

                                val binaryPath = copyBinaryToFilesDir(BINARY_NAME)
                                if (binaryPath != null) {
                                    // Simply execute the commands. executeCommands handles errors and stopSelf.
                                    executeCommands(binaryPath, ipAddress, domainName)
                                } else {
                                    Log.e(TAG, "Failed to prepare '$BINARY_NAME' binary for execution.")
                                    sendError("Binary Error", "Failed to prepare '$BINARY_NAME' binary for execution.")
                                }
                            }
                        }

                        /**
                         * Executes the two shell commands: slipstream-client and ssh.
                         * Returns true if successful, false otherwise.
                         */
                        private suspend fun executeCommands(
                            slipstreamClientPath: String,
                            ipAddress: String,
                            domainName: String
                        ): Boolean {
                            // Stop any running jobs before starting new processes
                            processOutputReaderJob?.cancel()
                            processOutputReaderJob = null

                            Log.i(TAG, "Starting command execution with IP: '$ipAddress' and Domain: '$domainName'")

                            sendStatusUpdate(slipstreamStatus = "Starting...", sshStatus = "Waiting...")

                            // Command 1: slipstream-client
                            val command1 = listOf(
                                slipstreamClientPath,
                                "--congestion-control=bbr",
                                "--resolver=$ipAddress:53",
                                "--domain=$domainName"
                            )

                            // 1. Start slipstream-client and wait for connection confirmation (2000ms timeout)
                            val confirmationMessage = "Connection confirmed."
                            val slipstreamStartResult = startProcessWithOutputCheck(command1, BINARY_NAME, 2000L, confirmationMessage)
                            slipstreamProcess = slipstreamStartResult.second

                            if (slipstreamStartResult.first.contains(confirmationMessage, ignoreCase = false)) {
                                Log.i(TAG, "$BINARY_NAME output confirmed successful connection. Proceeding with ssh.")

                                // Start job to monitor slipstream's ongoing output (only for live logging)
                                processOutputReaderJob = launch { readProcessOutput(slipstreamProcess!!, BINARY_NAME) }

                                sendStatusUpdate(slipstreamStatus = "Running", sshStatus = "Starting...")

                                delay(500L)

                                // Command 2: ssh tunnel
                                val sshArgs = "-p 5201 -ND 3080 root@localhost"
                                val shellCommand = "ssh $sshArgs"
                                val command2 = listOf("su", "-c", shellCommand)

                                // 2. Start ssh (no read timeout, we just start and let it run)
                                val sshStartResult = startProcessWithOutputCheck(command2, "ssh", null, null)
                                sshProcess = sshStartResult.second

                                if (sshProcess?.isAlive == true) {
                                    sendStatusUpdate(sshStatus = "Running")
                                    return true
                                } else {
                                    sendError("SSH Start Error", "SSH tunnel failed to start. Output:\n${sshStartResult.first}")
                                    killProcess(slipstreamProcess, BINARY_NAME) // Kill slipstream if ssh failed
                                    stopSelf()
                                    return false
                                }
                            } else {
                                val errorMessage = "$BINARY_NAME failed to confirm connection or timed out."
                                Log.w(TAG, errorMessage)
                                sendError(errorMessage, slipstreamStartResult.first)
                                killProcess(slipstreamProcess, BINARY_NAME)
                                stopSelf()
                                return false
                            }
                        }

                        /**
                         * Starts a shell command, reads initial output with an optional timeout, and returns the Process object.
                         */
                        private suspend fun startProcessWithOutputCheck(
                            command: List<String>,
                            logTag: String,
                            readTimeoutMillis: Long?,
                            successMessage: String?
                        ): Pair<String, Process> {
                            // Implementation remains similar, focusing on initial confirmation.
                            // The process output stream is handled by readProcessOutput for ongoing monitoring.
                            try {
                                Log.i(TAG, "Starting $logTag execution: ${command.joinToString(" ")}")

                                val process = ProcessBuilder(command)
                                .redirectErrorStream(true)
                                .start()

                                val output = StringBuilder()
                                val reader = BufferedReader(InputStreamReader(process.inputStream))

                                if (readTimeoutMillis != null) {
                                    withTimeoutOrNull(readTimeoutMillis) {
                                        var line: String?
                                        while (reader.readLine().also { line = it } != null) {
                                            output.append(line).append('\n')
                                            if (line?.isNotBlank() == true) Log.d(TAG, "$logTag Startup Output: $line")

                                                if (successMessage != null && line?.contains(successMessage, ignoreCase = false) == true) {
                                                    Log.d(TAG, "$logTag Success message found, confirming startup.")
                                                    break
                                                }
                                        }
                                        true
                                    }
                                } else {
                                    if (process.isAlive) {
                                        Log.d(TAG, "$logTag is running in the background.")
                                    } else {
                                        var line: String?
                                        while (reader.readLine().also { line = it } != null) {
                                            output.append(line).append('\n')
                                            if (line?.isNotBlank() == true) Log.d(TAG, "$logTag Initial Output: $line")
                                        }
                                    }
                                }

                                return Pair(output.toString().trim(), process)

                            } catch (e: Exception) {
                                Log.e(TAG, "Error executing $logTag command: ${e.message}", e)
                                return Pair("Execution Error: ${e.message}", ProcessBuilder("echo", "error").start())
                            }
                        }

                        /**
                         * Coroutine job to read the slipstream process output and log it (the "Live Output" feature).
                         */
                        private suspend fun readProcessOutput(process: Process, logTag: String) {
                            val reader = BufferedReader(InputStreamReader(process.inputStream))
                            try {
                                var line: String?
                                while (isActive && process.isAlive) {
                                    if (reader.ready()) {
                                        line = reader.readLine()
                                        if (line != null) {
                                            Log.d(TAG, "$logTag Live Output: $line") // Log all output
                                        }
                                    } else {
                                        // Short delay to prevent busy-waiting if nothing is ready
                                        delay(10)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error reading $logTag output: ${e.message}", e)
                            } finally {
                                Log.i(TAG, "$logTag output reader job finished.")
                                reader.close()
                            }
                        }

                        // --- Cleanup & Helpers ---

                        private fun createNotificationChannel() {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                val channel = NotificationChannel(
                                    NOTIFICATION_CHANNEL_ID,
                                    "Command Executor Status",
                                    NotificationManager.IMPORTANCE_DEFAULT
                                )
                                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                manager.createNotificationChannel(channel)
                            }
                        }

                        /**
                         * Builds the foreground notification, including a PendingIntent to open MainActivity.
                         */
                        private fun buildForegroundNotification(): Notification {
                            // Intent to launch MainActivity when the notification is tapped
                            val notificationIntent = Intent(this, MainActivity::class.java)

                            // Define PendingIntent flags based on Android version
                            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            } else {
                                PendingIntent.FLAG_UPDATE_CURRENT
                            }

                            val pendingIntent = PendingIntent.getActivity(
                                this,
                                0,
                                notificationIntent,
                                pendingIntentFlags
                            )

                            return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                            .setContentTitle("Slipstream Tunnel Running")
                            .setContentText("Tap to view configuration or stop the service.")
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentIntent(pendingIntent) // Set the intent to be triggered on click
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setOnlyAlertOnce(true)
                            .build()
                        }

                        private fun sendStatusUpdate(slipstreamStatus: String? = null, sshStatus: String? = null) {
                            val intent = Intent(ACTION_STATUS_UPDATE)
                            slipstreamStatus?.let { intent.putExtra(EXTRA_STATUS_SLIPSTREAM, it) }
                            sshStatus?.let { intent.putExtra(EXTRA_STATUS_SSH, it) }
                            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                        }

                        private fun sendError(message: String, output: String) {
                            val intent = Intent(ACTION_ERROR).apply {
                                putExtra(EXTRA_ERROR_MESSAGE, message)
                                putExtra(EXTRA_ERROR_OUTPUT, output)
                            }
                            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                            sendStatusUpdate(slipstreamStatus = "Failed", sshStatus = "Stopped")
                        }

                        private fun copyBinaryToFilesDir(binaryName: String): String? {
                            val destFile = File(filesDir, binaryName)
                            if (destFile.exists()) {
                                return destFile.absolutePath
                            }

                            try {
                                assets.open(binaryName).use { input ->
                                    destFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                destFile.setExecutable(true, false)
                                Log.d(TAG, "Copied and set executable permission for: ${destFile.absolutePath}")
                                return destFile.absolutePath
                            } catch (e: Exception) {
                                Log.e(TAG, "Error copying binary '$binaryName': ${e.message}", e)
                                return null
                            }
                        }

                        private fun cleanUpLingeringProcesses() {
                            Log.w(TAG, "Attempting to clean up lingering processes...")
                            val pkillSlipstream = listOf("su", "-c", "pkill -9 ${BINARY_NAME}")
                            executeCleanupCommand(pkillSlipstream, "pkill ${BINARY_NAME}")
                        }

                        private fun executeCleanupCommand(command: List<String>, logTag: String) {
                            try {
                                val process = ProcessBuilder(command)
                                .redirectErrorStream(true)
                                .start()

                                val exited = process.waitFor(1, TimeUnit.SECONDS)
                                if (exited) {
                                    if (process.exitValue() != 0) {
                                        // pkill returns 1 if no process was found, which is OK.
                                    }
                                } else {
                                    process.destroyForcibly()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error executing cleanup command $logTag: ${e.message}", e)
                            }
                        }

                        private fun killProcess(process: Process?, tag: String) {
                            if (process != null && process.isAlive) {
                                process.destroy()
                                try {
                                    if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) {
                                        process.destroyForcibly()
                                    }
                                    Log.i(TAG, "$tag process successfully terminated.")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error waiting/forcing termination for $tag: ${e.message}")
                                    if (process.isAlive) {
                                        process.destroyForcibly()
                                    }
                                }
                            }
                        }

                        override fun onBind(intent: Intent): IBinder? {
                            super.onBind(intent)
                            return null
                        }

                        override fun onDestroy() {
                            // Cancel the job to stop all coroutines launched in this scope
                            job.cancel()
                            stopBackgroundProcesses()
                            super.onDestroy()
                            Log.d(TAG, "Command Service destroyed.")
                        }

                        /**
                         * Stops all running processes and coroutines.
                         */
                        private fun stopBackgroundProcesses() {
                            Log.i(TAG, "Stopping foreground processes and coroutines...")

                            // 1. Cancel jobs
                            processOutputReaderJob?.cancel()

                            // 2. Kill processes
                            killProcess(sshProcess, "ssh")
                            killProcess(slipstreamProcess, BINARY_NAME)

                            // 3. Update UI
                            sendStatusUpdate(slipstreamStatus = "Stopped", sshStatus = "Stopped")

                            slipstreamProcess = null
                            sshProcess = null
                            processOutputReaderJob = null
                        }
}
