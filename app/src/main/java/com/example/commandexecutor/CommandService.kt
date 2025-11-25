package com.example.commandexecutor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import java.io.File
import java.io.InputStreamReader
import java.io.BufferedReader
import java.io.PrintWriter
import java.util.concurrent.TimeUnit

// Data class to hold the execution result (required for conditional logic)
data class CommandResult(val exitCode: Int, val output: String)

class CommandService : LifecycleService() {

    private val TAG = "CommandService"
    private val NOTIFICATION_CHANNEL_ID = "CommandServiceChannel"
    private val NOTIFICATION_ID = 101

    // Global variables to hold references to the long-running processes
    private var slipstreamProcess: Process? = null
        private var sshProcess: Process? = null

            companion object {
                const val EXTRA_IP_ADDRESS = "ip_address"
                const val EXTRA_DOMAIN = "domain_name"
                const val BINARY_NAME = "slipstream-client"
            }

            override fun onCreate() {
                super.onCreate()
                createNotificationChannel()
            }

            override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                super.onStartCommand(intent, flags, startId)

                val ipAddress = intent?.getStringExtra(EXTRA_IP_ADDRESS) ?: ""
                val domainName = intent?.getStringExtra(EXTRA_DOMAIN) ?: ""

                Log.d(TAG, "Service started. IP: $ipAddress, Domain: $domainName")

                // 1. Start as Foreground Service, required for long-running tasks on modern Android
                startForeground(NOTIFICATION_ID, buildForegroundNotification())

                // 2. Execute commands on a background thread (Dispatchers.IO)
                CoroutineScope(Dispatchers.IO).launch {
                    val binaryPath = copyBinaryToFilesDir(BINARY_NAME)
                    if (binaryPath != null) {
                        executeCommands(binaryPath, ipAddress, domainName)
                    } else {
                        Log.e(TAG, "Failed to prepare '$BINARY_NAME' binary for execution.")
                    }
                }

                return START_STICKY // Service should restart if killed by the OS
            }

            // --- Foreground Service Setup ---

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

            private fun buildForegroundNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Command Service Running")
            .setContentText("Executing background commands...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .build()

            // --- Binary Preparation ---

            /**
             * Copies the bundled binary to the app's files directory and sets executable permissions.
             */
            private fun copyBinaryToFilesDir(binaryName: String): String? {
                val destFile = File(filesDir, binaryName)
                if (destFile.exists()) {
                    Log.d(TAG, "$binaryName already exists, skipping copy.")
                    return destFile.absolutePath
                }

                try {
                    assets.open(binaryName).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Set executable permission (crucial for native binaries)
                    destFile.setExecutable(true, false)
                    Log.d(TAG, "Successfully copied and set executable permission for: ${destFile.absolutePath}")
                    return destFile.absolutePath
                } catch (e: Exception) {
                    Log.e(TAG, "Error copying binary '$binaryName': ${e.message}", e)
                    return null
                }
            }

            // --- Command Execution and Process Management ---

            /**
             * Executes the two required shell commands and manages their process lifecycle.
             */
            private suspend fun executeCommands(
                slipstreamClientPath: String,
                ipAddress: String,
                domainName: String
            ) {
                // Command 1: Execution of the bundled slipstream-client binary
                val command1 = listOf(
                    slipstreamClientPath,
                    "--congestion-control=bbr",
                    "--resolver=$ipAddress:53",
                    "--domain=$domainName"
                )

                // Start slipstream-client and wait for connection confirmation (2000ms timeout)
                val slipstreamClientResult = startLongRunningProcess(command1, "slipstream-client", 2000L)
                // FIX: Access the Process object using the Pair's 'second' property
                slipstreamProcess = slipstreamClientResult.second

                // FIX: Access the output string using the Pair's 'first' property
                if (slipstreamClientResult.first.contains("Connection confirmed.", ignoreCase = false)) {
                    Log.i(TAG, "slipstream-client output confirmed successful connection. Proceeding with ssh.")

                    delay(500L) // Wait for 500 milliseconds for port stability

                    // Command 2: Execution of system-preinstalled ssh binary, wrapped in 'su -c'.
                    // IMPORTANT FIX: Removed -f flag to prevent ssh from forking to background.
                    // ssh runs in the foreground as a child of 'su', allowing us to kill it via the su process.
                    val sshArgs = "-p 5201 -ND 3080 root@localhost"
                    val shellCommand = "ssh $sshArgs"

                    val command2 = listOf(
                        "su",
                        "-c",
                        shellCommand
                    )

                    // Start ssh (no read timeout, we just start and let it run)
                    val sshResult = startLongRunningProcess(command2, "ssh", null)
                    // FIX: Access the Process object using the Pair's 'second' property
                    sshProcess = sshResult.second

                } else {
                    Log.w(TAG, "slipstream-client output did NOT contain \"Connection confirmed.\" within 2000ms. Skipping ssh execution.")
                    // Since the slipstream-client failed to connect, we should stop it immediately.
                    killProcess(slipstreamProcess, "slipstream-client")
                }
            }

            /**
             * Starts a shell command, reads initial output with an optional timeout, and returns the Process object.
             * This is designed for long-running background processes (tunnels).
             * @param readTimeoutMillis If provided, the function returns as soon as the timeout expires or a success message is found.
             * @return A tuple of (output, Process). Output contains all text read up to the exit/timeout.
             */
            private suspend fun startLongRunningProcess(
                command: List<String>,
                logTag: String,
                readTimeoutMillis: Long?
            ): Pair<String, Process> {
                try {
                    Log.i(TAG, "Starting $logTag execution: ${command.joinToString(" ")}")

                    // FIX: Declare process and output as non-nullable local 'val' inside the try block
                    // to resolve the 'Unresolved reference' errors that occurred when they were declared as nullable 'var' outside.
                    val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

                    val output = StringBuilder()
                    val reader = BufferedReader(InputStreamReader(process.inputStream))

                    // Use withTimeoutOrNull if a timeout is specified (e.g., for slipstream-client's confirmation)
                    if (readTimeoutMillis != null) {
                        withTimeoutOrNull(readTimeoutMillis) {
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                output.append(line).append('\n')
                                // Log line by line (re-enables explicit Logcat logging)
                                if (line?.isNotBlank() == true) Log.d(TAG, "$logTag Output: $line")

                                    // Break if success message is found for slipstream-client
                                    if (logTag == BINARY_NAME && line?.contains("Connection confirmed.", ignoreCase = false) == true) {
                                        Log.d(TAG, "$logTag Success message found, stopping initial output read and proceeding.")
                                        break
                                    }
                            }
                            true
                        }
                    } else {
                        // For long-running processes like ssh, we only read the initial buffer and immediately return.
                        // Output is mostly for initial error messages if the process fails immediately.
                        if (process.isAlive) {
                            Log.d(TAG, "$logTag is running in the background. Stopping output read.")
                        } else {
                            // Capture final output if the process exited immediately
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                output.append(line).append('\n')
                                if (line?.isNotBlank() == true) Log.d(TAG, "$logTag Final Output: $line")
                            }
                        }
                    }

                    // Return the process and the initial output (or final output if it exited)
                    // No '!!' is needed here because process and output are non-nullable local 'val's defined above.
                    return Pair(output.toString().trim(), process)

                } catch (e: Exception) {
                    Log.e(TAG, "Error executing $logTag command: ${e.message}", e)
                    // Return dummy process if failed to start
                    return Pair("Execution Error: ${e.message}", ProcessBuilder("echo", "error").start())
                }
            }

            /**
             * Attempts a graceful (SIGTERM) then forced (SIGKILL) termination of a native process.
             */
            private fun killProcess(process: Process?, tag: String) {
                if (process != null && process.isAlive) {
                    Log.w(TAG, "Attempting graceful termination (SIGTERM) for $tag.")
                    process.destroy() // Sends SIGTERM (standard termination signal)
                    try {
                        // Wait up to 1 second for graceful shutdown
                        if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) {
                            Log.e(TAG, "Graceful termination failed. Forcing kill (SIGKILL) for $tag.")
                            process.destroyForcibly()
                        }
                        Log.i(TAG, "$tag process successfully terminated.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error waiting/forcing termination for $tag: ${e.message}")
                        // Fallback: forcefully kill it
                        if (process.isAlive) {
                            process.destroyForcibly()
                        }
                    }
                } else if (process != null) {
                    Log.i(TAG, "$tag process was already terminated.")
                }
            }

            override fun onBind(intent: Intent): IBinder? {
                super.onBind(intent)
                return null // We don't support binding in this simple implementation
            }

            override fun onDestroy() {
                // Stop all background processes we started
                stopBackgroundProcesses()
                super.onDestroy()
                Log.d(TAG, "Command Service destroyed.")
            }

            private fun stopBackgroundProcesses() {
                Log.i(TAG, "Stopping background processes...")
                // Kill slipstream-client
                killProcess(slipstreamProcess, "slipstream-client")
                slipstreamProcess = null

                // Kill ssh/su wrapper (which is now blocking because -f was removed, and should stop ssh cleanly)
                killProcess(sshProcess, "ssh")
                sshProcess = null
            }
}
