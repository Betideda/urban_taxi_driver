package com.driverapp.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.digitax.android.libcomtax2.taximeter.events.DisplayExtendedStatusListener
import com.digitax.android.libcomtax2.taximeter.events.TripDetailsExtendedResponseListener
import com.digitax.android.libcomtax2.taximeter.messages.DisplayExtendedStatusResponse
import com.digitax.android.libcomtax2.taximeter.messages.TripDetailsExtendedResponse
import com.digitax.android.libcomtax2.taximeter.objects.ExtendedStatus
import com.digitax.android.libcomtax2.taximeter.objects.TaximeterStatusCodes
import com.driverapp.R
import com.driverapp.handlers.OnlineStatusHandler
import com.driverapp.handlers.ShiftHandler
import com.driverapp.models.LocationData
import com.driverapp.models.OffersData
import com.driverapp.models.StoreForFaitTripBody
import com.driverapp.models.Zone
import com.driverapp.networkApi.Api
import com.driverapp.networkApi.models.MyTripData
import com.driverapp.recycler.OffersAdapter
import com.driverapp.utils.DigitaxTaximeterInitializer
import com.driverapp.utils.OffersClickListener
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.utils.TaxiModelAgent
import com.google.android.gms.location.LocationServices
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.isActive
import retrofit2.Call

/**
 * AddOfferActivity displays available taxi offers to drivers and handles trip initiation.
 * This activity manages taximeter communication with robust connection handling and automatic reconnection.
 */
class AddOfferActivity :
    AppCompatActivity(),
    DisplayExtendedStatusListener,
    OffersClickListener,
    TripDetailsExtendedResponseListener {

    // region Class Member Variables
    private lateinit var sharedPreferencesManager: SharedPreferencesManager
    private lateinit var onlineStatusHandler: OnlineStatusHandler
    private lateinit var shiftHandler: ShiftHandler
    private var taximeterManagerr: TaximeterManager? = null
    private var taxiModelAgentt: TaxiModelAgent? = null
    private lateinit var recyclerView: RecyclerView
    private lateinit var backButton: ImageView

    // Enhanced state management with thread safety
    private val stateMutex = Mutex()
    private var currentExtendedStatus: ExtendedStatus? = null
    private var currentShiftID: Int? = null
    private var currentTripId: Long? = null
    private var isProcessingTrip = false
    private var isTaximeterInitialized = false
    private var isConnected = false
    private var reconnectionAttempts = 0
    private var lastStatusRequestTime = 0L

    // Location data
    private val addressInfo = LocationData("", 0.0, 0.0)

    // Enhanced coroutine jobs management
    private var offersJob: Job? = null
    private var tripProcessingJob: Job? = null
    private var connectionMonitorJob: Job? = null
    private var reconnectionJob: Job? = null
    private var heartbeatJob: Job? = null

    // Connection management constants
    companion object {
        private const val MAX_RECONNECTION_ATTEMPTS = 5
        private const val RECONNECTION_DELAY_MS = 3000L
        private const val HEARTBEAT_INTERVAL_MS = 10000L
        private const val STATUS_REQUEST_TIMEOUT_MS = 5000L
    }

    // Authentication token
    private val authToken: String
        get() = "Bearer ${sharedPreferencesManager.getString("token", "")}"

    // endregion

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offers)

        initializeComponents()
        setupUI()
        initializeTaximeter()
        loadOffers()
        startConnectionMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    override fun onResume() {
        super.onResume()
        // Check connection status when resuming
        lifecycleScope.launch {
            checkConnectionHealth()
        }
    }

    /**
     * Initialize core components and shared preferences
     */
    private fun initializeComponents() {
        sharedPreferencesManager = SharedPreferencesManager(this)
        onlineStatusHandler = OnlineStatusHandler(this, lifecycleScope)
        shiftHandler = ShiftHandler(this, lifecycleScope, onlineStatusHandler)
    }

    /**
     * Setup UI components and event listeners
     */
    private fun setupUI() {
        backButton = findViewById(R.id.backButton)
        backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        recyclerView = findViewById(R.id.recyclerViewOffers)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.setHasFixedSize(true)
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
                Log.e("AddOfferActivity", "Failed to initialize taximeter: ${e.message}")
                withContext(Dispatchers.Main) {
                    showToast("Failed to initialize taximeter connection")
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
                    taximeterManagerr?.askDisplayExtendedStatus()
                    stateMutex.withLock {
                        lastStatusRequestTime = currentTime
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AddOfferActivity", "Health check failed: ${e.message}")
            handleConnectionLoss()
        }
    }

    /**
     * Monitor connection status and handle disconnections
     */
    private suspend fun monitorConnectionStatus() {
        stateMutex.withLock {
            if (isTaximeterInitialized && !isConnected) {
                Log.w("AddOfferActivity", "Connection lost detected, attempting reconnection")
                handleConnectionLoss()
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
            currentExtendedStatus = null
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
                        showToast("Unable to reconnect to taximeter. Please check connection.")
                    }
                    return@launch
                }
                reconnectionAttempts++
            }

            val delay = RECONNECTION_DELAY_MS * reconnectionAttempts
            Log.d(
                "AddOfferActivity",
                "Scheduling reconnection attempt $reconnectionAttempts in ${delay}ms"
            )
            delay(delay)

            try {
                initializeDigitaxTaximeter()
            } catch (e: Exception) {
                Log.e("AddOfferActivity", "Reconnection attempt failed: ${e.message}")
                scheduleReconnection()
            }
        }
    }

    /**
     * Load available offers from backend
     */
    private fun loadOffers() {
        // Cancel any existing offers loading job
        offersJob?.cancel()

        offersJob = lifecycleScope.launch {
            try {
                requestForOffers()
            } catch (e: Exception) {
                Log.e("AddOfferActivity", "Failed to load offers: ${e.message}")
                withContext(Dispatchers.Main) {
                    showToast("Failed to load offers")
                }
            }
        }
    }

    /**
     * Fetch offers from backend API with proper error handling
     */
    private suspend fun requestForOffers() {
        withContext(Dispatchers.IO) {
            Api.retrofitService
                .getOffers(authToken)
                .enqueue(object : retrofit2.Callback<OffersData> {
                    override fun onResponse(
                        call: Call<OffersData>,
                        response: retrofit2.Response<OffersData>
                    ) {
                        lifecycleScope.launch {
                            handleOffersResponse(response)
                        }
                    }

                    override fun onFailure(call: Call<OffersData>, t: Throwable) {
                        lifecycleScope.launch {
                            Log.e("AddOfferActivity", "Offers API failure: ${t.message}")
                            showToast("Network error: ${t.localizedMessage}")
                        }
                    }
                })
        }
    }

    /**
     * Handle offers API response
     */
    private suspend fun handleOffersResponse(response: retrofit2.Response<OffersData>) {
        withContext(Dispatchers.Main) {
            if (response.isSuccessful) {
                val offersList = response.body()?.data ?: emptyList()
                val adapter = OffersAdapter(offersList, this@AddOfferActivity)
                recyclerView.adapter = adapter

                Log.d("AddOfferActivity", "Loaded ${offersList.size} offers")
            } else {
                showToast("Failed to load offers: ${response.message()}")
            }
        }
    }

    /**
     * Initialize taximeter with enhanced callback handling and timeouts
     */
    private suspend fun initializeDigitaxTaximeter() {
        withContext(Dispatchers.IO) {
            try {
                DigitaxTaximeterInitializer(this@AddOfferActivity, this@AddOfferActivity)
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
                Log.e("AddOfferActivity", "Taximeter initialization failed: ${e.message}")
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
            taximeterManagerr = taximeterManager
            taxiModelAgentt = taxiModelAgent
            isTaximeterInitialized = true
            reconnectionAttempts = 0 // Reset reconnection attempts on successful connection
        }

        try {
            // Register listeners
            taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@AddOfferActivity)
            taximeterManager.OnTripDetailsExtendedResponseReceived.registerListener(this@AddOfferActivity)

            // Request initial status with timeout
            withContext(Dispatchers.IO) {
                taximeterManager.askDisplayExtendedStatus()
                stateMutex.withLock {
                    lastStatusRequestTime = System.currentTimeMillis()
                }
            }

            Log.d("AddOfferActivity", "Taximeter initialized successfully")
        } catch (e: Exception) {
            Log.e("AddOfferActivity", "Failed to setup taximeter listeners: ${e.message}")
            throw e
        }
    }

    /**
     * Handle taximeter connection status changes with improved logic
     */
    private suspend fun handleConnectionStatusChange(connected: Boolean) {
        stateMutex.withLock {
            isConnected = connected
            if (!connected) {
                isTaximeterInitialized = false
                currentExtendedStatus = null
            }
        }

        Log.d("AddOfferActivity", "Taximeter connection: $connected")

        if (!connected) {
            scheduleReconnection()
        } else {
            // Connection restored, reset reconnection attempts
            stateMutex.withLock {
                reconnectionAttempts = 0
            }
        }
    }

    /**
     * Handle offer acceptance and trip initiation with enhanced error handling
     */
    override fun onStartEndButtonClick(zoneOffer: Zone, acceptButton: Button) {
        // Prevent multiple simultaneous trip processing
        lifecycleScope.launch {
            val alreadyProcessing = stateMutex.withLock {
                if (isProcessingTrip) {
                    true
                } else {
                    isProcessingTrip = true
                    false
                }
            }

            if (alreadyProcessing) {
                showToast("Trip is already being processed")
                return@launch
            }

            tripProcessingJob?.cancel()
            tripProcessingJob = lifecycleScope.launch {
                try {
                    processTrip(zoneOffer)
                } catch (e: Exception) {
                    Log.e("AddOfferActivity", "Trip processing failed: ${e.message}")
                    showToast("Failed to process trip: ${e.localizedMessage}")
                } finally {
                    stateMutex.withLock {
                        isProcessingTrip = false
                    }
                }
            }
        }
    }

    /**
     * Process trip creation with enhanced connection validation
     */
    private suspend fun processTrip(zoneOffer: Zone) {
        // Validate connection status
        val connectionValid = stateMutex.withLock {
            isTaximeterInitialized && isConnected
        }

        if (!connectionValid) {
            showToast("Taximeter not connected. Please wait for reconnection.")
            return
        }

        // Ensure shift is active
        if (!shiftHandler.isShiftActive()) {
            shiftHandler.startShift()
            delay(1000) // Give time for shift to start
        }

        // Validate taximeter status with retry logic
        var statusValidationAttempts = 0
        var statusValid = false

        while (statusValidationAttempts < 3 && !statusValid) {
            val extendedStatus = stateMutex.withLock { currentExtendedStatus }

            if (extendedStatus?.StatusCode == TaximeterStatusCodes.ForHire) {
                statusValid = true
            } else {
                statusValidationAttempts++
                if (statusValidationAttempts < 3) {
                    // Request status update and wait
                    withContext(Dispatchers.IO) {
                        taximeterManagerr?.askDisplayExtendedStatus()
                    }
                    delay(1000)
                }
            }
        }

        if (!statusValid) {
            showToast("Taximeter must be in ForHire status to start trip")
            return
        }

        // Start forfait trip with retry logic
        val success = startForfaitTripWithRetry(zoneOffer)
        if (!success) {
            showToast("Failed to start forfait trip after multiple attempts")
            return
        }

        // Wait for taximeter to update and get trip details
        delay(100)
        requestTaximeterUpdatesWithRetry()

        // Wait for trip details to be available with timeout
        var waitTime = 0
        val maxWaitTime = 5000 // 5 seconds

        while (waitTime < maxWaitTime) {
            val tripId = stateMutex.withLock { currentTripId }
            if (tripId != null) break
            delay(500)
            waitTime += 500
        }

        // Store trip in backend
        storeTrip(zoneOffer)
    }

    /**
     * Start forfait trip with retry mechanism
     */
    private suspend fun startForfaitTripWithRetry(zoneOffer: Zone): Boolean {
        repeat(3) { attempt ->
            try {
                val success = withContext(Dispatchers.IO) {
                    val price = zoneOffer.price.toDoubleOrNull()?.toInt() ?: 0
                    taxiModelAgentt?.startForfaitTrip(price.toString())
                    true
                }

                if (success) {
                    Log.d("AddOfferActivity", "Started forfait trip with price: ${zoneOffer.price}")
                    return true
                }
            } catch (e: Exception) {
                Log.e(
                    "AddOfferActivity",
                    "Forfait trip attempt ${attempt + 1} failed: ${e.message}"
                )
                if (attempt < 2) {
                    delay(1000) // Wait before retry
                }
            }
        }
        return false
    }

    /**
     * Request taximeter status and trip details updates with retry
     */
    private suspend fun requestTaximeterUpdatesWithRetry() {
        repeat(3) { attempt ->
            try {
                withContext(Dispatchers.IO) {
                    taximeterManagerr?.askTripDetailsExtended()
                    taximeterManagerr?.askDisplayExtendedStatus()
                }
                return // Success, exit retry loop
            } catch (e: Exception) {
                Log.e(
                    "AddOfferActivity",
                    "Update request attempt ${attempt + 1} failed: ${e.message}"
                )
                if (attempt < 2) {
                    delay(500) // Wait before retry
                }
            }
        }
    }

    /**
     * Store trip in backend with location data
     */
    private suspend fun storeTrip(zoneOffer: Zone) {
        // Get current location
        val locationSuccess = getCurrentLocation()
        if (!locationSuccess) {
            showToast("Failed to get current location")
            return
        }

        // Get current trip data
        val (tripId, shiftId) = stateMutex.withLock {
            Pair(currentTripId, currentShiftID)
        }

        if (tripId == null || shiftId == null) {
            showToast("Trip details not available")
            return
        }

        // Create request body
        val body = StoreForFaitTripBody(
            shiftId,
            tripId.toInt(),
            zoneOffer.id,
            addressInfo.name,
            addressInfo.lat,
            addressInfo.lng
        )

        // Make API call
        storeTripInBackend(body, zoneOffer)
    }

    /**
     * Get current location with improved error handling
     */
    private suspend fun getCurrentLocation(): Boolean {
        return withContext(Dispatchers.Main) {
            if (ActivityCompat.checkSelfPermission(
                    this@AddOfferActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this@AddOfferActivity,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    1001
                )
                return@withContext false
            }

            try {
                val fusedLocationClient =
                    LocationServices.getFusedLocationProviderClient(this@AddOfferActivity)
                val locationTask = fusedLocationClient.lastLocation

                var locationReceived = false
                locationTask.addOnSuccessListener { loc: Location? ->
                    lifecycleScope.launch {
                        locationReceived = processLocation(loc)
                    }
                }.addOnFailureListener { e ->
                    Log.e("AddOfferActivity", "Location request failed: ${e.message}")
                    locationReceived = false
                }

                // Wait for location callback with timeout
                var waitTime = 0
                val maxWaitTime = 3000 // 3 seconds

                while (waitTime < maxWaitTime && !locationReceived) {
                    delay(100)
                    waitTime += 100
                }

                locationReceived
            } catch (e: Exception) {
                Log.e("AddOfferActivity", "Location error: ${e.message}")
                false
            }
        }
    }

    /**
     * Process location data and get address
     */
    private suspend fun processLocation(location: Location?): Boolean {
        return withContext(Dispatchers.IO) {
            if (location == null) {
                withContext(Dispatchers.Main) {
                    showToast("Could not get location")
                }
                return@withContext false
            }

            addressInfo.setCoordinates(location.latitude, location.longitude)

            try {
                val geocoder = Geocoder(this@AddOfferActivity, Locale.getDefault())
                val addresses = geocoder.getFromLocation(addressInfo.lat, addressInfo.lng, 1)
                addressInfo.name = if (!addresses.isNullOrEmpty()) {
                    addresses[0].getAddressLine(0)
                } else {
                    "Unknown Address"
                }
                true
            } catch (e: IOException) {
                Log.e("AddOfferActivity", "Geocoder error: ${e.message}")
                addressInfo.name = "Geocoder Error"
                true // Still continue with coordinates
            }
        }
    }

    /**
     * Store trip in backend API
     */
    private suspend fun storeTripInBackend(body: StoreForFaitTripBody, zoneOffer: Zone) {
        withContext(Dispatchers.IO) {
            Api.retrofitService
                .requestStoreForFaitTrip("application/json", authToken, body)
                .enqueue(object : retrofit2.Callback<MyTripData> {
                    override fun onResponse(
                        call: Call<MyTripData>,
                        response: retrofit2.Response<MyTripData>
                    ) {
                        lifecycleScope.launch {
                            handleStoreTripResponse(response, zoneOffer)
                        }
                    }

                    override fun onFailure(call: Call<MyTripData>, t: Throwable) {
                        lifecycleScope.launch {
                            Log.e("AddOfferActivity", "Store trip API failure: ${t.message}")
                            showToast("Failed to store trip: ${t.localizedMessage}")
                        }
                    }
                })
        }
    }

    /**
     * Handle store trip API response
     */
    private suspend fun handleStoreTripResponse(
        response: retrofit2.Response<MyTripData>,
        zoneOffer: Zone
    ) {
        withContext(Dispatchers.Main) {
            if (response.isSuccessful) {
                val tripId = response.body()?.data?.id
                navigateToMainActivity(tripId.toString())
                showToast("Trip started successfully with amount: ${zoneOffer.price} ALL")
            } else {
                showToast("Failed to start trip: ${response.message()}")
            }
        }
    }

    /**
     * Navigate to MainActivity with trip data
     */
    private fun navigateToMainActivity(tripId: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            tripId?.let { putExtra("trip_id", it) }
        }
        startActivity(intent)
    }

    /**
     * Handle taximeter extended status updates with connection validation
     */
    override fun onDisplayExtendedStatus(
        sender: Any?,
        response: DisplayExtendedStatusResponse?
    ) {
        lifecycleScope.launch {
            stateMutex.withLock {
                currentExtendedStatus = response?.extendedStatusData
                currentShiftID = response?.extendedStatusData?.ShiftNumber
                isConnected = true // Status received means we're connected
            }

            Log.d(
                "AddOfferActivity",
                "Status updated - Fare: ${response?.extendedStatusData?.CurrentFareAmount}, Shift: ${response?.extendedStatusData?.ShiftNumber}"
            )
        }
    }

    /**
     * Handle taximeter trip details updates
     */
    override fun onTripDetailsExtendedResponse(
        sender: Any?,
        response: TripDetailsExtendedResponse?
    ) {
        lifecycleScope.launch {
            stateMutex.withLock {
                currentTripId = response?.extendedTripDetails?.TripSequentialNumber
                // Only update shift ID if it's not already set to avoid conflicts
                if (currentShiftID == null) {
                    currentShiftID = response?.extendedTripDetails?.ShiftSequentialNumber?.toInt()
                }
                isConnected = true // Trip details received means we're connected
            }

            Log.d(
                "AddOfferActivity",
                "Trip details updated - TripID: $currentTripId, ShiftID: $currentShiftID"
            )
        }
    }

    /**
     * Show toast message on main thread
     */
    private fun showToast(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this@AddOfferActivity, message, Toast.LENGTH_SHORT).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@AddOfferActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Enhanced cleanup with proper job cancellation and resource management
     */
    private fun cleanup() {
        // Cancel all running jobs
        offersJob?.cancel()
        tripProcessingJob?.cancel()
        connectionMonitorJob?.cancel()
        reconnectionJob?.cancel()
        heartbeatJob?.cancel()

        // Unregister listeners safely
        try {
            taximeterManagerr?.OnDisplayExtendedStatusReceived?.unregisterListener(this)
            taximeterManagerr?.OnTripDetailsExtendedResponseReceived?.unregisterListener(this)
        } catch (e: Exception) {
            Log.e("AddOfferActivity", "Error unregistering listeners: ${e.message}")
        }

        // Clear references
        taximeterManagerr = null
        taxiModelAgentt = null

        Log.d("AddOfferActivity", "Cleanup completed")
    }
}
