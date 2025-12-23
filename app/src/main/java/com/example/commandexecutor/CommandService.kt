package com.example.commandexecutor

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

class CommandService : LifecycleService(), CoroutineScope {

    // Define the CoroutineScope for the service lifecycle (used for long-running monitoring/output)
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO

    private val TAG = "CommandService"
    private val NOTIFICATION_CHANNEL_ID = "CommandServiceChannel"
    private val NOTIFICATION_ID = 101

    // FIX: Standard Android Handler for reliable delayed execution on the main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    // Global variables for tunnel management
    private var slipstreamProcess: Process? = null
        private var sshProcess: Process? = null
            private var processOutputReaderJob: Job? = null
                private var tunnelMonitorJob: Job? = null

                    private val tunnelMutex = Mutex()

                    private var resolversConfig: ArrayList<String> = arrayListOf()
                        private var domainNameConfig: String = ""

                            companion object {
                                const val EXTRA_RESOLVERS = "extra_ip_addresses_list"
                                const val EXTRA_DOMAIN = "domain_name"
                                const val BINARY_NAME = "slipstream-client"
                                const val ACTION_STATUS_UPDATE = "com.example.commandexecutor.STATUS_UPDATE"
                                const val ACTION_ERROR = "com.example.commandexecutor.ERROR"
                                const val ACTION_REQUEST_STATUS = "com.example.commandexecutor.REQUEST_STATUS"
                                const val EXTRA_STATUS_SLIPSTREAM = "status_slipstream"
                                const val EXTRA_STATUS_SSH = "status_ssh"
                                const val EXTRA_ERROR_MESSAGE = "error_message"
                                const val EXTRA_ERROR_OUTPUT = "error_output"
                                private const val MONITOR_INTERVAL_MS = 1000L
                            }

                            override fun onCreate() {
                                super.onCreate()
                                createNotificationChannel()
                            }

                            override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                                super.onStartCommand(intent, flags, startId)

                                // --- FIX: Handle the status request using Handler for reliable delay ---
                                if (intent?.action == ACTION_REQUEST_STATUS) {

                                    // 1. Immediate Status Update (First attempt)
                                    sendCurrentStatus(logTag = "Immediate")

                                    // 2. Delayed Status Update (Workaround for UI race condition)
                                    mainHandler.postDelayed({
                                        // This runnable will execute on the main thread after 500ms
                                        sendCurrentStatus(logTag = "Delayed (500ms)")
                                    }, 500L)

                                    return START_STICKY
                                }

                                resolversConfig = intent?.getStringArrayListExtra(EXTRA_RESOLVERS) ?: arrayListOf()
                                domainNameConfig = intent?.getStringExtra(EXTRA_DOMAIN) ?: ""

                                Log.d(TAG, "Service started. Resolvers: ${resolversConfig.joinToString()}, Domain: $domainNameConfig")

                                startForeground(NOTIFICATION_ID, buildForegroundNotification())

                                launch {
                                    startTunnelSequence(resolversConfig, domainNameConfig)
                                }

                                return START_STICKY
                            }

                            /**
                             * Helper function to check process status and broadcast it.
                             */
                            private fun sendCurrentStatus(logTag: String) {
                                // Ensure this is called on a thread where process objects are stable (it's safe here)
                                val slipstreamAlive = slipstreamProcess?.isAlive == true
                                val sshAlive = sshProcess?.isAlive == true

                                Log.e(TAG, "--- STATUS REQUEST $logTag RECEIVED ---")
                                Log.e(TAG, "Process Check: Slipstream is Alive: $slipstreamAlive, SSH is Alive: $sshAlive")

                                val slipstreamStatus = if (slipstreamAlive) "Running" else "Stopped"
                                val sshStatus = if (sshAlive) "Running" else "Stopped"

                                sendStatusUpdate(slipstreamStatus = slipstreamStatus, sshStatus = sshStatus)

                                Log.e(TAG, "Status broadcasted ($logTag): Slipstream=$slipstreamStatus, SSH=$sshStatus")
                            }

                            /**
                             * The main execution sequence: cleans up, copies binary, and starts the tunnel.
                             */
                            private suspend fun startTunnelSequence(resolvers: ArrayList<String>, domainName: String) {
                                tunnelMutex.withLock {

                                    sendStatusUpdate(slipstreamStatus = "Cleaning up...", sshStatus = "Waiting...")

                                    // Cancel existing monitoring and output reading before starting new processes
                                    tunnelMonitorJob?.cancel()
                                    processOutputReaderJob?.cancel()
                                    cleanUpLingeringProcesses()

                                    val binaryPath = copyBinaryToFilesDir(BINARY_NAME)
                                    if (binaryPath != null) {
                                        // Execute the commands.
                                        val success = executeCommands(binaryPath, resolvers, domainName)
                                        if (success) {
                                            // Start periodic health monitoring only if successful
                                            tunnelMonitorJob = launch { startTunnelMonitor() }
                                        } else {
                                            // If tunnel failed to start, stop the service
                                            stopSelf()
                                        }

                                    } else {
                                        Log.e(TAG, "Failed to prepare '$BINARY_NAME' binary for execution.")
                                        sendError("Binary Error", "Failed to prepare '$BINARY_NAME' binary for execution.")
                                        stopSelf()
                                    }
                                }
                            }

                            /**
                             * Checks if both processes are running. If not, it attempts a full restart.
                             */
                            private suspend fun startTunnelMonitor() {
                                //Log.d(TAG, "Starting tunnel monitor job.")
                                while (isActive) {
                                    delay(MONITOR_INTERVAL_MS)

                                    val slipstreamAlive = slipstreamProcess?.isAlive == true
                                    val sshAlive = sshProcess?.isAlive == true

                                    //Log.d(TAG, "Monitor Check: Slipstream is Alive: $slipstreamAlive, SSH is Alive: $sshAlive")

                                    if (slipstreamAlive && sshAlive) {
                                        // All good, update status to reassure UI and trigger UI refresh
                                        sendStatusUpdate(slipstreamStatus = "Running", sshStatus = "Running")
                                    } else if (!slipstreamAlive || !sshAlive) {
                                        // Critical failure: one or both processes died unexpectedly.
                                        Log.w(TAG, "Tunnel failure detected. Attempting full restart.")
                                        sendError(
                                            "Connection Dropped",
                                            "Slipstream status: ${if (slipstreamAlive) "Running" else "Dead"}. SSH status: ${if (sshAlive) "Running" else "Dead"}."
                                        )

                                        // Attempt restart with current configuration
                                        Log.w(TAG, "Tunnel failure detected. Restarting.")
                                        startTunnelSequence(resolversConfig, domainNameConfig)
                                    }
                                }
                            }

                            /**
                             * Executes the two shell commands: slipstream-client and ssh.
                             */
                            private suspend fun executeCommands(slipstreamClientPath: String, resolvers: ArrayList<String>, domainName: String): Boolean {
                                processOutputReaderJob?.cancel()
                                processOutputReaderJob = null

                                Log.i(TAG, "Starting command execution with domain: '$domainName'")
                                sendStatusUpdate(slipstreamStatus = "Starting...", sshStatus = "Waiting...")

                                val command1 = mutableListOf(
                                    slipstreamClientPath,
                                    "--congestion-control=bbr",
                                    "--domain=$domainName"
                                )

                                // Ensure format is --resolver=IP:PORT
                                resolvers.forEach { ip ->
                                    val formattedIp = if (ip.contains(":")) ip else "$ip:53"
                                    command1.add("--resolver=$formattedIp")
                                }

                                val confirmationMessage = "Connection confirmed."
                                val slipstreamStartResult = startProcessWithOutputCheck(command1, BINARY_NAME, 3000L, confirmationMessage)
                                slipstreamProcess = slipstreamStartResult.second

                                if (slipstreamStartResult.first.contains(confirmationMessage)) {
                                    processOutputReaderJob = launch { readProcessOutput(slipstreamProcess!!, BINARY_NAME) }
                                    sendStatusUpdate(slipstreamStatus = "Running", sshStatus = "Starting...")
                                    delay(1000L)

                                    val sshArgs = "-p 5201 -ND 3080 root@localhost"
                                    val shellCommand = "ssh $sshArgs"
                                    val command2 = listOf("su", "-c", shellCommand)

                                    val sshStartResult = startProcessWithOutputCheck(command2, "ssh", null, null)
                                    sshProcess = sshStartResult.second

                                    if (sshProcess?.isAlive == true) {
                                        sendStatusUpdate(slipstreamStatus = "Running", sshStatus = "Running")
                                        return true
                                    } else {
                                        sendError("SSH Start Error", "Output:\n${sshStartResult.first}")
                                        killProcess(slipstreamProcess, BINARY_NAME)
                                        return false
                                    }
                                } else {
                                    sendError("Slipstream Error", slipstreamStartResult.first)
                                    killProcess(slipstreamProcess, BINARY_NAME)
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
                                        if (!process.isAlive) {
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
                             * Coroutine job to read the process output and log it.
                             */
                            private suspend fun readProcessOutput(process: Process, logTag: String) {
                                val reader = BufferedReader(InputStreamReader(process.inputStream))
                                try {
                                    var line: String?
                                    while (isActive && process.isAlive) {
                                        if (reader.ready()) {
                                            line = reader.readLine()
                                            if (line != null) {
                                                Log.d(TAG, "$logTag Live Output: $line")
                                            }
                                        } else {
                                            delay(10)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error reading $logTag output: ${e.message}", e)
                                } finally {
                                    if (!process.isAlive) {
                                        Log.w(TAG, "$logTag died unexpectedly. Monitor job should detect this shortly.")
                                    }
                                    reader.close()
                                }
                            }

                            // --- Cleanup & Status Management ---

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

                            private fun buildForegroundNotification(): Notification {
                                val notificationIntent = Intent(this, Class.forName("com.example.commandexecutor.MainActivity")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }

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
                                .setContentIntent(pendingIntent)
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
                                Log.w(TAG, "Attempting to clean up lingering processes using killall...")
                                val killallSlipstream = listOf("su", "-c", "killall -9 ${BINARY_NAME}")
                                executeCleanupCommand(killallSlipstream, "killall ${BINARY_NAME}")
                            }

                            private fun executeCleanupCommand(command: List<String>, logTag: String) {
                                try {
                                    val process = ProcessBuilder(command)
                                    .redirectErrorStream(true)
                                    .start()

                                    val exited = process.waitFor(1, TimeUnit.SECONDS)
                                    if (exited) {
                                        if (process.exitValue() != 0) {
                                            Log.d(TAG, "Cleanup command $logTag returned non-zero exit code, likely meaning no processes were running.")
                                        }
                                    } else {
                                        process.destroyForcibly()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error executing cleanup command $logTag: ${e.message}", e)
                                }
                            }

                            /**
                             * Terminates a process, waiting up to 1 second for graceful exit, then forcing.
                             */
                            private fun killProcess(process: Process?, tag: String): Boolean {
                                if (process != null && process.isAlive) {
                                    process.destroy()
                                    try {
                                        if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) {
                                            process.destroyForcibly()
                                            if (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                                                Log.e(TAG, "$tag process **FAILED** to terminate forcibly. It may be orphaned.")
                                                return false
                                            }
                                        }
                                        Log.i(TAG, "$tag process successfully terminated.")
                                        return true
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error waiting/forcing termination for $tag: ${e.message}")
                                        if (process.isAlive) {
                                            process.destroyForcibly()
                                        }
                                        return false
                                    }
                                }
                                return true
                            }

                            override fun onBind(intent: Intent): IBinder? {
                                super.onBind(intent)
                                return null
                            }

                            override fun onDestroy() {
                                // Cancel the job to stop all coroutines launched in this scope
                                job.cancel()
                                // Stop any pending handler messages
                                mainHandler.removeCallbacksAndMessages(null)

                                stopBackgroundProcesses()
                                super.onDestroy()
                                Log.d(TAG, "Command Service destroyed.")
                            }

                            /**
                             * Stops all running processes and coroutines.
                             */
                            private fun stopBackgroundProcesses() {
                                Log.i(TAG, "Stopping foreground processes and coroutines...")

                                tunnelMonitorJob?.cancel()
                                processOutputReaderJob?.cancel()

                                val sshKilled = killProcess(sshProcess, "ssh")
                                val slipstreamKilled = killProcess(slipstreamProcess, BINARY_NAME)

                                val finalSlipstreamStatus = if (slipstreamKilled) "Stopped" else "Failed to Stop"
                                val finalSshStatus = if (sshKilled) "Stopped" else "Failed to Stop"

                                sendStatusUpdate(slipstreamStatus = finalSlipstreamStatus, sshStatus = finalSshStatus)

                                if (!slipstreamKilled || !sshKilled) {
                                    Log.e(TAG, "CRITICAL: One or more processes failed to stop! Slipstream: $finalSlipstreamStatus, SSH: $finalSshStatus")
                                }

                                slipstreamProcess = null
                                sshProcess = null
                                processOutputReaderJob = null
                                tunnelMonitorJob = null
                            }
}
