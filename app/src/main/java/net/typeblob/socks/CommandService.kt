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
                killSlipstreamProcesses()
                
                updateStatus(SlipstreamStatus.Starting("Starting tunnel..."), SocksStatus.Waiting)
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
            
            val binaryFile = File(applicationInfo.nativeLibraryDir, "libslipstream.so")
            log("[Service] Step 2: Binary path: ${binaryFile.absolutePath}")
            
            if (!binaryFile.exists()) {
                throw IOException("Binary not found at: ${binaryFile.absolutePath}")
            }
            
            log("[Service] Step 3: Binary exists: true")
            log("[Service] Step 4: Binary size: ${binaryFile.length()} bytes")
            
            // Use shell to execute (bypasses some SELinux restrictions)
            val shellCommand = "cd ${filesDir.absolutePath} && LD_LIBRARY_PATH=${applicationInfo.nativeLibraryDir} ${binaryFile.absolutePath} $domain $resolvers --socks-port $port"
            
            val command = listOf(
                "/system/bin/sh",
                "-c",
                shellCommand
            )
            
            log("[Service] Step 5: Using shell wrapper")
            log("[Service] Step 6: Working dir: ${filesDir.absolutePath}")
            
            val processBuilder = ProcessBuilder(command)
                .directory(filesDir)
                .redirectErrorStream(true)
            
            log("[Service] Step 7: Starting process via shell...")
            
            slipstreamProcess = processBuilder.start()
            
            log("[Service] Step 8: Process started successfully")
            
            // Start monitoring
            startOutputMonitoring()
            
        } catch (e: Exception) {
            log("[Service] Error: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
    
    private fun startOutputMonitoring() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(slipstreamProcess?.inputStream))
                
                while (isActive) {
                    val line = reader.readLine() ?: break
                    log("[Slipstream] $line")
                }
                
                log("[Service] Process output stream ended")
                
            } catch (e: Exception) {
                if (isActive) {
                    log("[Service] Output monitoring error: ${e.message}")
                }
            }
        }
    }
    
    private fun killSlipstreamProcesses() {
        try {
            Runtime.getRuntime().exec("killall libslipstream.so").waitFor()
        } catch (e: Exception) {
            log("[Service] Cleanup: ${e.message}")
        }
    }
    
    fun stopTunnel() {
        log("[Service] Stopping tunnel...")
        
        try {
            slipstreamProcess?.destroy()
            slipstreamProcess = null
            
            monitorJob?.cancel()
            monitorJob = null
            
            killSlipstreamProcesses()
            
            updateStatus(SlipstreamStatus.Stopped, SocksStatus.Stopped)
            
        } catch (e: Exception) {
            log("[Service] Stop error: ${e.message}")
        }
        
        stopSelf()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        log("[Service] Service destroyed")
        
        slipstreamProcess?.destroy()
        monitorJob?.cancel()
        scope.cancel()
    }
    
    private fun updateStatus(slipstreamStatus: SlipstreamStatus, socksStatus: SocksStatus) {
        statusCallback?.invoke(slipstreamStatus, socksStatus)
    }
    
    private fun createNotification(title: String, text: String): Notification {
        createNotificationChannel()
        
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(title: String, text: String) {
        val notification = createNotification(title, text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Slipstream Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Slipstream tunnel service notifications"
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    companion object {
        private const val TAG = "CommandService"
        private const val CHANNEL_ID = "slipstream_service"
        private const val NOTIFICATION_ID = 1
    }
}