package com.driverapp.fragments

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.digitax.android.libcomtax2.taximeter.events.DisplayExtendedStatusListener
import com.digitax.android.libcomtax2.taximeter.events.FullShiftDetailsResponseListener
import com.digitax.android.libcomtax2.taximeter.events.LastClosedShiftDetailsResponseListener
import com.digitax.android.libcomtax2.taximeter.messages.DisplayExtendedStatusResponse
import com.digitax.android.libcomtax2.taximeter.messages.FullShiftDetailsResponse
import com.digitax.android.libcomtax2.taximeter.messages.LastClosedShiftDetailsResponse
import com.digitax.android.libcomtax2.taximeter.objects.ExtendedStatus
import com.driverapp.R
import com.driverapp.handlers.OnlineStatusHandler
import com.driverapp.handlers.ShiftHandler
import com.driverapp.networkApi.models.ShiftInfo
import com.driverapp.utils.DigitaxTaximeterInitializer
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.utils.TaxiModelAgent
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class ShiftsFragment : Fragment(),
    DisplayExtendedStatusListener,
    FullShiftDetailsResponseListener,
    LastClosedShiftDetailsResponseListener {

    // UI Components
    private lateinit var offlineLayout: LinearLayout
    private lateinit var onlineLayout: LinearLayout
    private lateinit var offlineIcon: ImageView
    private lateinit var onlineIcon: ImageView
    private lateinit var offlineText: TextView
    private lateinit var onlineText: TextView
    private lateinit var shiftNumberText: TextView
    private lateinit var tripCountText: TextView
    private lateinit var totalFareText: TextView
    private lateinit var connectionButton: ImageButton

    // region Class Member Variables
    private lateinit var sharedPreferencesManager: SharedPreferencesManager
    private lateinit var onlineStatusHandler: OnlineStatusHandler
    private lateinit var shiftHandler: ShiftHandler

    // Taximeter Components
    private var extendedStatus: ExtendedStatus? = null
    private var taximeterManager: TaximeterManager? = null
    private var taxiModelAgent: TaxiModelAgent? = null

    // Enhanced state management with thread safety
    private val stateMutex = Mutex()
    private var isTaximeterInitialized = false
    private var isConnected = false
    private var reconnectionAttempts = 0
    private var lastStatusRequestTime = 0L
    private var isShiftChangeInProgress = false

    // Enhanced coroutine jobs management
    private var connectionMonitorJob: Job? = null
    private var reconnectionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var shiftDetailsJob: Job? = null
    private var uiUpdateJob: Job? = null

    // Connection management constants
    companion object {
        private const val MAX_RECONNECTION_ATTEMPTS = 5
        private const val RECONNECTION_DELAY_MS = 3000L
        private const val HEARTBEAT_INTERVAL_MS = 15000L // Slightly longer for fragment
        private const val STATUS_REQUEST_TIMEOUT_MS = 5000L
        private const val SHIFT_DETAILS_REQUEST_INTERVAL_MS =
            30000L // Request shift details every 30 seconds
    }

    // endregion

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_shifts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeComponents()
        setupUI(view)

        // Load and display current shift status
        val currentShiftStatus = shiftHandler.isShiftActive()
        updateShiftUI(currentShiftStatus)

        initializeTaximeter()
        startConnectionMonitoring()
    }

    override fun onResume() {
        super.onResume()
        // Check connection status when resuming
        lifecycleScope.launch {
            checkConnectionHealth()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    /**
     * Initialize core components
     */
    private fun initializeComponents() {
        sharedPreferencesManager = SharedPreferencesManager(requireContext())
        onlineStatusHandler = OnlineStatusHandler(requireContext(), lifecycleScope)
        shiftHandler = ShiftHandler(requireContext(), lifecycleScope, onlineStatusHandler)
    }

    /**
     * Setup UI components and event listeners
     */
    private fun setupUI(view: View) {
        // Initialize UI elements
        offlineLayout = view.findViewById(R.id.offline_layout)
        onlineLayout = view.findViewById(R.id.online_layout)
        offlineIcon = view.findViewById(R.id.offline_icon)
        onlineIcon = view.findViewById(R.id.online_icon)
        offlineText = view.findViewById(R.id.offline_text)
        onlineText = view.findViewById(R.id.online_text)
        shiftNumberText = view.findViewById(R.id.shiftNo)
        tripCountText = view.findViewById(R.id.tripCount)
        totalFareText = view.findViewById(R.id.totalFare)


        connectionButton = view.findViewById(R.id.connectionButton)
        connectionButton.setOnClickListener {
            restartApp()
        }

        // Set click listeners
        offlineLayout.setOnClickListener {
            handleOfflineClick()
        }

        onlineLayout.setOnClickListener {
            handleOnlineClick()
        }
    }


    // Simple restart function for Fragment
    private fun restartApp() {
        try {
            // Get packageManager from context instead of directly
            val intent =
                requireContext().packageManager.getLaunchIntentForPackage(requireContext().packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK) // This is important
            startActivity(intent)

            // Force kill the process
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(0)
        } catch (e: Exception) {
            Log.e("ShiftsFragment", "Error restarting app: ${e.message}")
            // Fallback: try to restart via activity
            activity?.let { activity ->
                val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(0)
            }
        }
    }

    /**
     * Initialize taximeter connection with enhanced error handling
     */
    private fun initializeTaximeter() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                stateMutex.withLock {
                    reconnectionAttempts = 0
                }
                initializeDigitaxTaximeter()
            } catch (e: Exception) {
                Log.e("ShiftsFragment", "Failed to initialize taximeter: ${e.message}")
                withContext(Dispatchers.Main) {
                    showSnackbar("Failed to initialize taximeter connection")
                }
                scheduleReconnection()
            }
        }
    }

    /**
     * Start connection monitoring and heartbeat
     */
    private fun startConnectionMonitoring() {
        // Start heartbeat monitoring
        heartbeatJob = lifecycleScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                checkConnectionHealth()
            }
        }

        // Start connection status monitoring
        connectionMonitorJob = lifecycleScope.launch {
            while (isActive) {
                delay(5000) // Check every 5 seconds
                monitorConnectionStatus()
            }
        }

        // Start periodic shift details updates
        shiftDetailsJob = lifecycleScope.launch {
            while (isActive) {
                delay(SHIFT_DETAILS_REQUEST_INTERVAL_MS)
                requestShiftDetailsIfConnected()
            }
        }
    }

    /**
     * Check connection health with timeout
     */
    private suspend fun checkConnectionHealth() {
        stateMutex.withLock {
            if (!isTaximeterInitialized || !isConnected) {
                return
            }
        }

        try {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastRequest = currentTime - lastStatusRequestTime

            // Only request status if enough time has passed to avoid spamming
            if (timeSinceLastRequest > STATUS_REQUEST_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    taximeterManager?.askDisplayExtendedStatus()
                    stateMutex.withLock {
                        lastStatusRequestTime = currentTime
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ShiftsFragment", "Health check failed: ${e.message}")
            handleConnectionLoss()
        }
    }

    /**
     * Monitor connection status and handle disconnections
     */
    private suspend fun monitorConnectionStatus() {
        stateMutex.withLock {
            if (isTaximeterInitialized && !isConnected) {
                Log.w("ShiftsFragment", "Connection lost detected, attempting reconnection")
                handleConnectionLoss()
            }
        }
    }

    /**
     * Request shift details if connected
     */
    private suspend fun requestShiftDetailsIfConnected() {
        val canRequest = stateMutex.withLock {
            isTaximeterInitialized && isConnected
        }

        if (canRequest) {
            try {
                withContext(Dispatchers.IO) {
                    taximeterManager?.askFullShiftDetails(true)
                }
            } catch (e: Exception) {
                Log.e("ShiftsFragment", "Failed to request shift details: ${e.message}")
            }
        }
    }

    /**
     * Handle connection loss with automatic reconnection
     */
    private suspend fun handleConnectionLoss() {
        stateMutex.withLock {
            isConnected = false
            isTaximeterInitialized = false
            extendedStatus = null
        }

        scheduleReconnection()
    }

    /**
     * Schedule reconnection attempt with exponential backoff
     */
    private fun scheduleReconnection() {
        reconnectionJob?.cancel()
        reconnectionJob = lifecycleScope.launch {
            stateMutex.withLock {
                if (reconnectionAttempts >= MAX_RECONNECTION_ATTEMPTS) {
                    withContext(Dispatchers.Main) {
                        showSnackbar("Unable to reconnect to taximeter. Please check connection.")
                    }
                    return@launch
                }
                reconnectionAttempts++
            }

            val delay = RECONNECTION_DELAY_MS * reconnectionAttempts
            Log.d(
                "ShiftsFragment",
                "Scheduling reconnection attempt $reconnectionAttempts in ${delay}ms"
            )
            delay(delay)

            try {
                initializeDigitaxTaximeter()
            } catch (e: Exception) {
                Log.e("ShiftsFragment", "Reconnection attempt failed: ${e.message}")
                scheduleReconnection()
            }
        }
    }

    /**
     * Handle offline button click with connection validation
     */
    private fun handleOfflineClick() {
        lifecycleScope.launch {
            val alreadyInProgress = stateMutex.withLock {
                if (isShiftChangeInProgress) {
                    true
                } else {
                    isShiftChangeInProgress = true
                    false
                }
            }

            if (alreadyInProgress) {
                showSnackbar("Shift change already in progress")
                return@launch
            }

            try {
                val currentShiftStatus = shiftHandler.isShiftActive()
                updateShiftUI(false)

                shiftHandler.endShift(object : ShiftHandler.ShiftChangeCallback {
                    override fun onShiftChanged(
                        isActive: Boolean,
                        success: Boolean,
                        message: String?
                    ) {
                        lifecycleScope.launch {
                            stateMutex.withLock {
                                isShiftChangeInProgress = false
                            }

                            activity?.runOnUiThread {
                                if (success) {
                                    showSnackbar(message ?: getString(R.string.you_are_offline))
                                    // Clear shift display data
                                    clearShiftDisplayData()
                                } else {
                                    // Revert UI on failure
                                    updateShiftUI(currentShiftStatus)
                                    showSnackbar(message ?: "Failed to end shift")
                                }
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                stateMutex.withLock {
                    isShiftChangeInProgress = false
                }
                Log.e("ShiftsFragment", "Error ending shift: ${e.message}")
                showSnackbar("Error ending shift")
            }
        }
    }

    /**
     * Handle online button click with connection validation
     */
    private fun handleOnlineClick() {
        lifecycleScope.launch {
            val alreadyInProgress = stateMutex.withLock {
                if (isShiftChangeInProgress) {
                    true
                } else {
                    isShiftChangeInProgress = true
                    false
                }
            }

            if (alreadyInProgress) {
                showSnackbar("Shift change already in progress")
                return@launch
            }

            try {
                val currentShiftStatus = shiftHandler.isShiftActive()
                updateShiftUI(true)

                shiftHandler.startShift(object : ShiftHandler.ShiftChangeCallback {
                    override fun onShiftChanged(
                        isActive: Boolean,
                        success: Boolean,
                        message: String?
                    ) {
                        lifecycleScope.launch {
                            stateMutex.withLock {
                                isShiftChangeInProgress = false
                            }

                            activity?.runOnUiThread {
                                if (success) {
                                    showSnackbar(message ?: getString(R.string.you_are_online))
                                    // Request fresh shift details
                                    requestShiftDetails()
                                } else {
                                    // Revert UI on failure
                                    updateShiftUI(currentShiftStatus)
                                    showSnackbar(message ?: "Failed to start shift")
                                }
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                stateMutex.withLock {
                    isShiftChangeInProgress = false
                }
                Log.e("ShiftsFragment", "Error starting shift: ${e.message}")
                showSnackbar("Error starting shift")
            }
        }
    }

    /**
     * Update UI state for online/offline status
     */
    private fun updateShiftUI(isOnline: Boolean) {
        if (!isAdded) return // Safety check for fragment lifecycle

        try {
            if (isOnline) {
                // Set offline to grey
                offlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.grey))
                offlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))

                // Set online to black
                onlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.black))
                onlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            } else {
                // Set offline to black
                offlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.black))
                offlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))

                // Set online to grey
                onlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.grey))
                onlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))
            }
        } catch (e: Exception) {
            Log.e("ShiftsFragment", "Error updating shift UI: ${e.message}")
        }
    }

    /**
     * Clear shift display data when going offline
     */
    private fun clearShiftDisplayData() {
        if (!isAdded) return

        try {
            shiftNumberText.text = "TURNI: --"
            tripCountText.text = "UDHETIMET: --"
            totalFareText.text = "TOTALI: --"
        } catch (e: Exception) {
            Log.e("ShiftsFragment", "Error clearing shift display: ${e.message}")
        }
    }

    /**
     * Request current shift details with connection validation
     */
    private fun requestShiftDetails() {
        lifecycleScope.launch {
            val canRequest = stateMutex.withLock {
                isTaximeterInitialized && isConnected
            }

            if (canRequest) {
                try {
                    withContext(Dispatchers.IO) {
                        taximeterManager?.askFullShiftDetails(true)
                    }
                } catch (e: Exception) {
                    Log.e("ShiftsFragment", "Failed to request shift details: ${e.message}")
                }
            } else {
                Log.w("ShiftsFragment", "Cannot request shift details - taximeter not connected")
            }
        }
    }

    /**
     * Initialize taximeter with enhanced callback handling and timeouts
     */
    private suspend fun initializeDigitaxTaximeter() {
        withContext(Dispatchers.IO) {
            try {
                DigitaxTaximeterInitializer(requireContext(), requireActivity())
                    .initialize(object : DigitaxTaximeterInitializer.Callback {
                        override fun onInitialized(
                            taximeterManager: TaximeterManager,
                            taxiModelAgent: TaxiModelAgent
                        ) {
                            lifecycleScope.launch {
                                handleTaximeterInitialized(taximeterManager, taxiModelAgent)
                            }
                        }

                        override fun onConnectionStatusChanged(connected: Boolean) {
                            lifecycleScope.launch {
                                handleConnectionStatusChange(connected)
                            }
                        }
                    })
            } catch (e: Exception) {
                Log.e("ShiftsFragment", "Taximeter initialization failed: ${e.message}")
                throw e
            }
        }
    }

    /**
     * Handle successful taximeter initialization
     */
    private suspend fun handleTaximeterInitialized(
        taximeterManager: TaximeterManager,
        taxiModelAgent: TaxiModelAgent
    ) {
        stateMutex.withLock {
            this@ShiftsFragment.taximeterManager = taximeterManager
            this@ShiftsFragment.taxiModelAgent = taxiModelAgent
            isTaximeterInitialized = true
            reconnectionAttempts = 0 // Reset reconnection attempts on successful connection
        }

        try {
            // Register listeners
            taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@ShiftsFragment)
            taximeterManager.OnFullShiftDetailsResponseReceived.registerListener(this@ShiftsFragment)
            taximeterManager.OnLastClosedShiftDetailsResponseReceived.registerListener(this@ShiftsFragment)

            // Request initial status and shift details with timeout
            withContext(Dispatchers.IO) {
                taximeterManager.askDisplayExtendedStatus()
                taximeterManager.askFullShiftDetails(true)
                stateMutex.withLock {
                    lastStatusRequestTime = System.currentTimeMillis()
                }
            }

            Log.d("ShiftsFragment", "Taximeter initialized successfully")
        } catch (e: Exception) {
            Log.e("ShiftsFragment", "Failed to setup taximeter listeners: ${e.message}")
            throw e
        }

        // Set taximeter components in shift handler
        shiftHandler.setTaximeterComponents(taximeterManager, taxiModelAgent)
    }

    /**
     * Handle taximeter connection status changes with improved logic
     */
    private suspend fun handleConnectionStatusChange(connected: Boolean) {
        stateMutex.withLock {
            isConnected = connected
            if (!connected) {
                isTaximeterInitialized = false
                extendedStatus = null
            }
        }

        Log.d("ShiftsFragment", "Taximeter connection: $connected")

        if (!connected) {
            scheduleReconnection()
        } else {
            // Connection restored, reset reconnection attempts and request fresh data
            stateMutex.withLock {
                reconnectionAttempts = 0
            }
            // Request fresh shift details when connection is restored
            delay(1000) // Small delay to ensure connection is stable
            requestShiftDetails()
        }
    }

    /**
     * Show snackbar message on main thread
     */
    private fun showSnackbar(message: String) {
        if (!isAdded) return // Safety check for fragment lifecycle

        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("ShiftsFragment", "Error showing snackbar: ${e.message}")
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                if (isAdded) {
                    try {
                        Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("ShiftsFragment", "Error showing snackbar: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Update shift details UI safely
     */
    private fun updateShiftDetailsUI(shiftNumber: String?, tripCount: Int, totalFare: Double) {
        if (!isAdded) return

        uiUpdateJob?.cancel()
        uiUpdateJob = lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                try {
                    shiftNumberText.text = "TURNI: ${shiftNumber ?: "N/A"}"
                    tripCountText.text = "UDHETIMET: $tripCount"
                    totalFareText.text = "TOTALI: $totalFare"
                } catch (e: Exception) {
                    Log.e("ShiftsFragment", "Error updating shift details UI: ${e.message}")
                }
            }
        }
    }

    // region Taximeter Event Listeners

    override fun onDisplayExtendedStatus(
        sender: Any?,
        displayExtendedStatusResponse: DisplayExtendedStatusResponse?
    ) {
        lifecycleScope.launch {
            stateMutex.withLock {
                extendedStatus = displayExtendedStatusResponse?.extendedStatusData
                isConnected = true // Status received means we're connected
            }
            Log.d("ShiftsFragment", "Extended status received")
        }
    }

    override fun onFullShiftDetailsResponse(sender: Any?, response: FullShiftDetailsResponse?) {
        lifecycleScope.launch {
            stateMutex.withLock {
                isConnected = true // Shift details received means we're connected
            }

            response?.let { shiftResponse ->
                val shiftDetails = shiftResponse.fullShiftDetails

                // Calculate totals safely
                val totalTripsCount = try {
                    val endTrips = shiftDetails?.ShiftInfoEnd?.TripsQuantity?.toInt() ?: 0
                    val startTrips = shiftDetails?.ShiftInfoStart?.TripsQuantity?.toInt() ?: 0
                    maxOf(0, endTrips - startTrips) // Ensure non-negative
                } catch (e: NumberFormatException) {
                    Log.e("ShiftsFragment", "Error parsing trip counts: ${e.message}")
                    0
                }

                val totalFareAmount = try {
                    val endAmount = shiftDetails?.ShiftInfoEnd?.TotalAmount?.toDouble() ?: 0.0
                    val startAmount = shiftDetails?.ShiftInfoStart?.TotalAmount?.toDouble() ?: 0.0
                    maxOf(0.0, endAmount - startAmount) // Ensure non-negative
                } catch (e: NumberFormatException) {
                    Log.e("ShiftsFragment", "Error parsing fare amounts: ${e.message}")
                    0.0
                }

                // Update UI safely
                updateShiftDetailsUI(
                    shiftDetails?.ShiftConsecutiveNumber.toString(),
                    totalTripsCount,
                    totalFareAmount
                )

                Log.d(
                    "ShiftsFragment",
                    "Full shift details updated - Trips: $totalTripsCount, Fare: $totalFareAmount"
                )
            }
        }
    }

    override fun onLastClosedShiftDetailsResponse(
        sender: Any?,
        response: LastClosedShiftDetailsResponse?
    ) {
        lifecycleScope.launch {
            stateMutex.withLock {
                isConnected = true // Shift details received means we're connected
            }

            // FIXME: This listener is fired twice
            Log.d("CLOSE_SHIFT_SENDER", sender?.toString() ?: "N/A")

            response?.let { shiftResponse ->
                try {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val currentDateTime = dateFormat.format(Date())

                    val shiftDetails = shiftResponse.fullShiftDetails
                    val startDetails = shiftDetails?.ShiftInfoStart
                    val endDetails = shiftDetails?.ShiftInfoEnd

                    // Create ShiftInfo object with safe string conversions
                    val shiftInfo = ShiftInfo(
                        // General Information
                        taximeter_shift_id = (shiftDetails?.ShiftConsecutiveNumber
                            ?: "0").toString(),

                        // Start Info
                        start_info_trips_quantity = (startDetails?.TripsQuantity ?: "0").toString(),
                        start_info_units_quantity = (startDetails?.UnitsQuantity ?: "0").toString(),
                        start_info_total_distance = (startDetails?.TotalDistance ?: "0").toString(),
                        start_info_hired_distance = (startDetails?.HiredDistance ?: "0").toString(),
                        start_info_for_hire_distance = (startDetails?.ForHireDistance
                            ?: "0").toString(),
                        start_info_black_trip_distance = (startDetails?.BlackTripDistance
                            ?: "0").toString(),
                        start_info_waiting_time = (startDetails?.WaitingTime ?: "0").toString(),
                        start_info_fare_amount = (startDetails?.FareAmount ?: "0").toString(),
                        start_info_extras_amount = (startDetails?.ExtrasAmount ?: "0").toString(),
                        start_info_credit_card_amount = (startDetails?.CreditCardAmount
                            ?: "0").toString(),
                        start_info_tax_amount = (startDetails?.TaxAmount ?: "0").toString(),
                        start_info_tips_amount = (startDetails?.TipsAmount ?: "0").toString(),

                        // End Info
                        end_info_trips_quantity = (endDetails?.TripsQuantity ?: "0").toString(),
                        end_info_units_quantity = (endDetails?.UnitsQuantity ?: "0").toString(),
                        end_info_total_distance = (endDetails?.TotalDistance ?: "0").toString(),
                        end_info_hired_distance = (endDetails?.HiredDistance ?: "0").toString(),
                        end_info_for_hire_distance = (endDetails?.ForHireDistance
                            ?: "0").toString(),
                        end_info_black_trip_distance = (endDetails?.BlackTripDistance
                            ?: "0").toString(),
                        end_info_waiting_time = (endDetails?.WaitingTime ?: "0").toString(),
                        end_info_fare_amount = (endDetails?.FareAmount ?: "0").toString(),
                        end_info_extras_amount = (endDetails?.ExtrasAmount ?: "0").toString(),
                        end_info_credit_card_amount = (endDetails?.CreditCardAmount
                            ?: "0").toString(),
                        end_info_tax_amount = (endDetails?.TaxAmount ?: "0").toString(),
                        end_info_tips_amount = (endDetails?.TipsAmount ?: "0").toString(),

                        // Timestamps
                        shift_started_at = shiftDetails?.ShiftStartDate?.let { calendar ->
                            dateFormat.format(calendar.time) // Convert Calendar to Date then format
                        } ?: currentDateTime,

                        shift_ended_at = shiftDetails?.ShiftEndDate?.let { calendar ->
                            dateFormat.format(calendar.time) // Convert Calendar to Date then format
                        } ?: currentDateTime
                    )

                    // Use shift handler to send info
                    shiftHandler.sendShiftInfo(shiftInfo, object : ShiftHandler.ShiftInfoCallback {
                        override fun onShiftInfoSent(success: Boolean, message: String?) {
                            if (isAdded) {
                                activity?.runOnUiThread {
                                    val displayMessage = if (success) {
                                        "Shift information sent successfully"
                                    } else {
                                        message ?: "Failed to send shift information"
                                    }
                                    showSnackbar(displayMessage)
                                }
                            }
                        }
                    })

                    Log.d(
                        "ShiftsFragment",
                        "Last closed shift details processed and sent to backend"
                    )
                } catch (e: Exception) {
                    Log.e("ShiftsFragment", "Error processing closed shift details: ${e.message}")
                    if (isAdded) {
                        showSnackbar("Error processing shift information")
                    }
                }
            }
        }
    }

    // endregion

    /**
     * Enhanced cleanup with proper job cancellation and resource management
     */
    private fun cleanup() {
        // Cancel all running jobs
        connectionMonitorJob?.cancel()
        reconnectionJob?.cancel()
        heartbeatJob?.cancel()
        shiftDetailsJob?.cancel()
        uiUpdateJob?.cancel()

        // Unregister listeners safely
        try {
            taximeterManager?.OnDisplayExtendedStatusReceived?.unregisterListener(this)
            taximeterManager?.OnFullShiftDetailsResponseReceived?.unregisterListener(this)
            taximeterManager?.OnLastClosedShiftDetailsResponseReceived?.unregisterListener(this)
        } catch (e: Exception) {
            Log.e("ShiftsFragment", "Error unregistering listeners: ${e.message}")
        }

        // Clear references
        taximeterManager = null
        taxiModelAgent = null

        Log.d("ShiftsFragment", "Fragment destroyed and cleaned up")
    }
}
