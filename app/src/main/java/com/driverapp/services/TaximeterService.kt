package com.driverapp.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.driverapp.R
import com.driverapp.utils.TaxiModelAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.digitax.protocols.Bluetooth.BluetoothDevicePaired
import com.digitax.protocols.Bluetooth.BluetoothManagerDigitax
import com.digitax.protocols.DataSource.BleTaxDataSource
import com.digitax.protocols.ILoggerHandler

class TaximeterService : LifecycleService() {

    private val binder = LocalBinder()
    private var taximeterManager: TaximeterManager? = null
    private var taxiModelAgent: TaxiModelAgent? = null
    private var dataSource: BleTaxDataSource? = null

    private val stateMutex = Mutex()
    private var isTaximeterInitialized = false
    private var isConnected = false
    private var reconnectionAttempts = 0
    private var lastStatusRequestTime = 0L

    private var connectionMonitorJob: Job? = null
    private var reconnectionJob: Job? = null
    private var heartbeatJob: Job? = null

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "TaximeterServiceChannel"
        private const val MAX_RECONNECTION_ATTEMPTS = 10
        private const val RECONNECTION_DELAY_MS = 3000L
        private const val HEARTBEAT_INTERVAL_MS = 10000L
        private const val STATUS_REQUEST_TIMEOUT_MS = 5000L
        private const val LOG_TAG = "TaximeterService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): TaximeterService = this@TaximeterService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        initializeTaximeter()
        startConnectionMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(LOG_TAG, "onDestroy: Service is being destroyed.")
        cleanup()
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Taximeter Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Taximeter Service")
            .setContentText("Managing taximeter connection.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    fun getTaximeterManager(): TaximeterManager? {
        return taximeterManager
    }

    fun getTaxiModelAgent(): TaxiModelAgent? {
        return taxiModelAgent
    }

    private fun initializeTaximeter() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                stateMutex.withLock {
                    reconnectionAttempts = 0
                }
                initializeDigitaxTaximeter()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to initialize taximeter: ${e.message}")
                scheduleReconnection()
            }
        }
    }

    private suspend fun initializeDigitaxTaximeter() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(LOG_TAG, "initializeDigitaxTaximeter: Starting initialization.")
                val btManager = BluetoothManagerDigitax(this@TaximeterService)
                val pairedDevices: List<BluetoothDevicePaired>? = btManager.PairedDigitaxDevicesGet()
                val btDevice = pairedDevices?.firstOrNull()?.Device

                val localDataSource = BleTaxDataSource(this@TaximeterService, btDevice)
                val loggerHandler = ILoggerHandler { }
                val localTaximeterManager = btManager.BleTaximeterDataSourceGet(loggerHandler, localDataSource)
                val localTaxiModelAgent = TaxiModelAgent(localTaximeterManager)

                dataSource = localDataSource
                taximeterManager = localTaximeterManager
                taxiModelAgent = localTaxiModelAgent

                handleTaximeterInitialized(localTaximeterManager, localTaxiModelAgent)
                Log.d(LOG_TAG, "initializeDigitaxTaximeter: Calling ProtocolStart.")
                dataSource?.ProtocolStart()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Taximeter initialization failed: ${e.message}")
                throw e
            }
        }
    }

    private suspend fun handleTaximeterInitialized(
        taximeterManager: TaximeterManager,
        taxiModelAgent: TaxiModelAgent
    ) {
        stateMutex.withLock {
            this.taximeterManager = taximeterManager
            this.taxiModelAgent = taxiModelAgent
            isTaximeterInitialized = true
            reconnectionAttempts = 0
        }
        Log.d(LOG_TAG, "Taximeter initialized successfully")
    }

    private suspend fun handleConnectionStatusChange(connected: Boolean) {
        stateMutex.withLock {
            isConnected = connected
            if (!connected) {
                isTaximeterInitialized = false
            }
        }

        Log.d(LOG_TAG, "Taximeter connection: $connected")

        if (!connected) {
            scheduleReconnection()
        } else {
            stateMutex.withLock {
                reconnectionAttempts = 0
            }
        }
    }

    private fun startConnectionMonitoring() {
        heartbeatJob = lifecycleScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                checkConnectionHealth()
            }
        }

        connectionMonitorJob = lifecycleScope.launch {
            while (isActive) {
                delay(5000)
                monitorConnectionStatus()
            }
        }
    }

    private suspend fun checkConnectionHealth() {
        stateMutex.withLock {
            if (!isTaximeterInitialized || !isConnected) {
                return
            }
        }

        try {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastRequest = currentTime - lastStatusRequestTime

            if (timeSinceLastRequest > STATUS_REQUEST_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    taximeterManager?.askDisplayExtendedStatus()
                    stateMutex.withLock {
                        lastStatusRequestTime = currentTime
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Health check failed: ${e.message}")
            handleConnectionLoss()
        }
    }

    private suspend fun monitorConnectionStatus() {
        stateMutex.withLock {
            if (isTaximeterInitialized && !isConnected) {
                Log.w(LOG_TAG, "Connection lost detected, attempting reconnection")
                handleConnectionLoss()
            }
        }
    }

    private suspend fun handleConnectionLoss() {
        Log.w(LOG_TAG, "handleConnectionLoss: Connection lost.")
        cleanupConnection()
        stateMutex.withLock {
            isConnected = false
            isTaximeterInitialized = false
        }
        scheduleReconnection()
    }

    private fun scheduleReconnection() {
        reconnectionJob?.cancel()
        reconnectionJob = lifecycleScope.launch {
            Log.d(LOG_TAG, "scheduleReconnection: Scheduling reconnection.")
            cleanupConnection()
            stateMutex.withLock {
                if (reconnectionAttempts >= MAX_RECONNECTION_ATTEMPTS) {
                    Log.e(
                        LOG_TAG,
                        "Unable to reconnect to taximeter. Max attempts reached."
                    )
                    return@launch
                }
                reconnectionAttempts++
            }

            val delay = RECONNECTION_DELAY_MS * reconnectionAttempts
            Log.d(
                LOG_TAG,
                "Scheduling reconnection attempt $reconnectionAttempts in ${delay}ms"
            )
            delay(delay)

            try {
                initializeDigitaxTaximeter()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Reconnection attempt failed: ${e.message}")
                scheduleReconnection()
            }
        }
    }

    private fun cleanupConnection() {
        Log.d(LOG_TAG, "cleanupConnection: Cleaning up connection.")
        try {
            dataSource?.ProtocolStop(true)
            Log.d(LOG_TAG, "cleanupConnection: ProtocolStop called.")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "cleanupConnection: Error calling ProtocolStop: ${e.message}")
        }
        dataSource = null
        taximeterManager = null
        taxiModelAgent = null
    }

    private fun cleanup() {
        Log.d(LOG_TAG, "cleanup: Cleaning up service.")
        connectionMonitorJob?.cancel()
        reconnectionJob?.cancel()
        heartbeatJob?.cancel()
        cleanupConnection()
        Log.d(LOG_TAG, "Cleanup completed")
    }
}