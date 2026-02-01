package net.typeblob.socks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

class CommandService : Service() {
    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var slipstreamProcess: Process? = null
    private var monitorJob: Job? = null
    
    private var statusCallback: ((SlipstreamStatus, SocksStatus) -> Unit)? = null
    private var logCallback: ((String) -> Unit)? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): CommandService = this@CommandService
    }
    
    override fun onBind(intent: Intent): IBinder = binder
    
    fun setStatusCallback(callback: (SlipstreamStatus, SocksStatus) -> Unit) {
        statusCallback = callback
    }
    
    fun setLogCallback(callback: (String) -> Unit) {
        logCallback = callback
    }
    
    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logMessage = "[$timestamp] $message"
        Log.d(TAG, message)
        logCallback?.invoke(logMessage)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("[Service] Service starting...")
        
        val domain = intent?.getStringExtra("domain") ?: ""
        val resolvers = intent?.getStringExtra("resolvers") ?: ""
        val port = intent?.getIntExtra("port", 1081) ?: 1081
        
        if (domain.isBlank() || resolvers.isBlank()) {
            log("[Service] ERROR: Missing domain or resolvers")
            updateStatus(SlipstreamStatus.Failed("Missing configuration"), SocksStatus.Stopped)
            stopSelf()
            return START_NOT_STICKY
        }
        
        log("[Service] Service starting - Domain: $domain, Port: $port")
        
        startForeground(NOTIFICATION_ID, createNotification("Starting...", "Initializing proxy"))
        
        scope.launch {
            try {
                // Clean up any existing processes
                log("[Service] Cleaning up old processes...")
                val cleanup = killSlipstreamProcesses()
                log("[Service] Cleanup exit: $cleanup")
                
                updateStatus(SlipstreamStatus.Starting("Cleaning up..."), SocksStatus.Waiting)
                delay(500)
                
                // Start slipstream
                startSlipstreamProcess(domain, resolvers, port)
                delay(1000)
                
                if (slipstreamProcess?.isAlive == true) {
                    updateStatus(SlipstreamStatus.Running, SocksStatus.Running)
                    updateNotification("Running", "SOCKS5 proxy on port $port")
                    log("[Service] Tunnel started successfully")
                } else {
                    throw IOException("Process terminated immediately")
                }
                
            } catch (e: Exception) {
                log("[Service] ERROR: ${e.javaClass.simpleName} - ${e.message}")
                updateStatus(SlipstreamStatus.Failed("Slipstream failed"), SocksStatus.Stopped)
                stopSelf()
            }
        }
        
        return START_STICKY
    }
    
    private fun startSlipstreamProcess(domain: String, resolvers: String, port: Int) {
        try {
            log("[Service] Step 1: Native lib dir: ${applicationInfo.nativeLibraryDir}")
            
            // Source: Native library location
            val sourceFile = File(applicationInfo.nativeLibraryDir, "libslipstream.so")
            log("[Service] Step 2: Looking for binary: ${sourceFile.absolutePath}")
            
            if (!sourceFile.exists()) {
                throw IOException("Binary not found at: ${sourceFile.absolutePath}")
            }
            
            log("[Service] Step 3: Binary exists: true")
            log("[Service] Step 4: Binary size: ${sourceFile.length()} bytes")
            log("[Service] Step 5: Binary executable: ${sourceFile.canExecute()}")
            
            // Destination: App's private files directory (executable location)
            val destFile = File(filesDir, "slipstream-client")
            
            // Copy binary to writable location
            if (!destFile.exists() || sourceFile.length() != destFile.length()) {
                log("[Service] Step 6: Copying binary to ${filesDir.absolutePath}/slipstream-client")
                sourceFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                log("[Service] Step 7: Copy completed (${destFile.length()} bytes)")
            } else {
                log("[Service] Step 6: Binary already exists in private directory")
            }
            
            // Make executable
            destFile.setExecutable(true, false)
            destFile.setReadable(true, false)
            log("[Service] Step 7: Made binary executable")
            
            val command = listOf(
                destFile.absolutePath,
                domain,
                resolvers,
                "--socks-port",
                port.toString()
            )
            
            log("[Service] Step 8: Command: ${command.joinToString(" ")}")
            log("[Service] Step 9: Working dir: ${filesDir.absolutePath}")
            
            val processBuilder = ProcessBuilder(command)
                .directory(filesDir)
                .redirectErrorStream(true)
            
            // Set library path
            val env = processBuilder.environment()
            env["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
            
            log("[Service] Step 10: Starting process...")
            
            slipstreamProcess = processBuilder.start()
            
            log("[Service] Step 11: Process started with hash: ${slipstreamProcess?.hashCode()}")
            
            // Start monitoring
            startOutputMonitoring()
            
        } catch (e: Exception) {
            log("[Service] Error: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            updateStatus(SlipstreamStatus.Failed("Slipstream failed"), SocksStatus.Stopped)
            throw e
        }
    }
    
    private fun startOutputMonitoring() {
        monitorJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(slipstreamProcess?.inputStream))
                var line: String?
                
                while (reader.readLine().also { line = it } != null) {
                    line?.let { log("[Slipstream] $it") }
                }
                
                val exitCode = slipstreamProcess?.waitFor() ?: -1
                log("[Service] Process exited with code: $exitCode")
                
                if (exitCode != 0) {
                    updateStatus(SlipstreamStatus.Failed("Exit code: $exitCode"), SocksStatus.Stopped)
                }
                
            } catch (e: Exception) {
                log("[Service] Monitor error: ${e.message}")
            }
        }
    }
    
    private fun killSlipstreamProcesses(): Int {
        return try {
            val process = Runtime.getRuntime().exec("killall slipstream-client")
            process.waitFor()
        } catch (e: Exception) {
            log("[Service] Cleanup error: ${e.message}")
            1
        }
    }
    
    private fun updateStatus(slipstream: SlipstreamStatus, socks: SocksStatus) {
        statusCallback?.invoke(slipstream, socks)
    }
    
    private fun updateNotification(title: String, content: String) {
        val notification = createNotification(title, content)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun createNotification(title: String, content: String): Notification {
        val channelId = "slipstream_service"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Slipstream Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
        
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        log("[Service] Stopping tunnel...")
        updateStatus(SlipstreamStatus.Stopping, SocksStatus.Stopping)
        
        monitorJob?.cancel()
        slipstreamProcess?.destroy()
        killSlipstreamProcesses()
        
        updateStatus(SlipstreamStatus.Stopped, SocksStatus.Stopped)
        log("[Service] Tunnel stopped")
        
        scope.cancel()
    }
    
    companion object {
        private const val TAG = "CommandService"
        private const val NOTIFICATION_ID = 1
    }
}