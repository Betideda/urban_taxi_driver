package com.driverapp.fragments

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.digitax.android.libcomtax2.taximeter.events.DisplayExtendedStatusListener
import com.digitax.android.libcomtax2.taximeter.events.TripDetailsExtendedResponseListener
import com.digitax.android.libcomtax2.taximeter.messages.DisplayExtendedStatusResponse
import com.digitax.android.libcomtax2.taximeter.messages.TripDetailsExtendedResponse
import com.digitax.android.libcomtax2.taximeter.objects.ExtendedStatus
import com.digitax.android.libcomtax2.taximeter.objects.TaximeterButtons
import com.digitax.android.libcomtax2.taximeter.objects.TaximeterStatusCodes
import com.driverapp.R
import com.driverapp.activity.AddOfferActivity
import com.driverapp.models.LocationData
import com.driverapp.models.MyTripListData
import com.driverapp.networkApi.Api
import com.driverapp.networkApi.models.DropOffTripAddressBody
import com.driverapp.networkApi.models.MyTripData
import com.driverapp.networkApi.models.Trip
import com.driverapp.networkApi.models.PickupTripAddressBody
import com.driverapp.recycler.MyTripsAdapter
import com.driverapp.utils.DigitaxTaximeterInitializer
import com.driverapp.utils.MyTripsButtonClickListener
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.utils.TaxiModelAgent
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.isActive
import java.io.IOException
import java.util.Locale
import androidx.core.net.toUri

/**
 * TripsFragment handles the display and management of assigned taxi trips.
 * This fragment manages taximeter communication, trip lifecycle, and navigation features
 * with enhanced connection management and automatic reconnection.
 */
class TripsFragment : Fragment(), MyTripsButtonClickListener, DisplayExtendedStatusListener,
    TripDetailsExtendedResponseListener {

    // region UI Components
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MyTripsAdapter
    private lateinit var addOffersBtn: ImageView
    private lateinit var emptyStateLayout: View
    private lateinit var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout

    // region Core Components
    private lateinit var sharedPreferencesManager: SharedPreferencesManager
    private var taximeterManagerr: TaximeterManager? = null
    private var taxiModelAgentt: TaxiModelAgent? = null

    // Enhanced State Management with Thread Safety
    private val stateMutex = Mutex()
    private var currentExtendedStatus: ExtendedStatus? = null
    private var currentShiftID: Int? = null
    private var currentTripId: Long? = null
    private var hiredDistance: Float = 0f
    private var isTaximeterInitialized = false
    private var isConnected = false
    private var isProcessingTrip = false
    private var reconnectionAttempts = 0
    private var lastStatusRequestTime = 0L

    // region Data
    private lateinit var tripList: List<Trip>
    private val addressInfo = LocationData("", 0.0, 0.0)

    // Enhanced Coroutine Jobs Management
    private var tripsLoadJob: Job? = null
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_trips, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeComponents()
        setupUI(view)
        initializeTaximeter()
        loadTrips()
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
     * Initialize core components
     */
    private fun initializeComponents() {
        sharedPreferencesManager = SharedPreferencesManager(requireContext())
    }

    /**
     * Setup UI components and event listeners
     */
    private fun setupUI(view: View) {
        // Initialize UI components
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        emptyStateLayout = view.findViewById(R.id.emptyStateLayout)
        recyclerView = view.findViewById(R.id.recyclerViewTrips)
        addOffersBtn = view.findViewById(R.id.addOffersBtn)

        // Setup RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.setHasFixedSize(true)

        // Setup swipe refresh
        swipeRefreshLayout.setOnRefreshListener {
            loadTrips()
        }

        // Setup add offers button
        addOffersBtn.setOnClickListener {
            val intent = Intent(requireContext(), AddOfferActivity::class.java)
            startActivity(intent)
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
                Log.e("TripsFragment", "Failed to initialize taximeter: ${e.message}")
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
            Log.e("TripsFragment", "Health check failed: ${e.message}")
            handleConnectionLoss()
        }
    }

    /**
     * Monitor connection status and handle disconnections
     */
    private suspend fun monitorConnectionStatus() {
        stateMutex.withLock {
            if (isTaximeterInitialized && !isConnected) {
                Log.w("TripsFragment", "Connection lost detected, attempting reconnection")
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
                "TripsFragment",
                "Scheduling reconnection attempt $reconnectionAttempts in ${delay}ms"
            )
            delay(delay)

            try {
                initializeDigitaxTaximeter()
            } catch (e: Exception) {
                Log.e("TripsFragment", "Reconnection attempt failed: ${e.message}")
                scheduleReconnection()
            }
        }
    }

    /**
     * Load trips from backend
     */
    private fun loadTrips() {
        // Cancel any existing loading job
        tripsLoadJob?.cancel()

        tripsLoadJob = lifecycleScope.launch {
            try {
                requestTaxiMeterStatus()
                requestMyListTrips()
            } catch (e: Exception) {
                Log.e("TripsFragment", "Failed to load trips: ${e.message}")
                withContext(Dispatchers.Main) {
                    showToast("Failed to load trips")
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    /**
     * Request taximeter status with connection validation
     */
    private suspend fun requestTaxiMeterStatus() {
        val connectionValid = stateMutex.withLock {
            isTaximeterInitialized && isConnected
        }

        if (!connectionValid) {
            Log.w("TripsFragment", "Skipping status request - taximeter not connected")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                taximeterManagerr?.askDisplayExtendedStatus()
                stateMutex.withLock {
                    lastStatusRequestTime = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.e("TripsFragment", "Failed to request taximeter status: ${e.message}")
                handleConnectionLoss()
            }
        }
    }

    /**
     * Fetch trips from backend API
     */
    private suspend fun requestMyListTrips() {
        withContext(Dispatchers.IO) {
            Api.retrofitService
                .myTrips(authToken)
                .enqueue(object : retrofit2.Callback<MyTripListData> {
                    override fun onResponse(
                        call: retrofit2.Call<MyTripListData>,
                        response: retrofit2.Response<MyTripListData>
                    ) {
                        lifecycleScope.launch {
                            handleTripsResponse(response)
                        }
                    }

                    override fun onFailure(call: retrofit2.Call<MyTripListData>, t: Throwable) {
                        lifecycleScope.launch {
                            handleTripsFailure(t)
                        }
                    }
                })
        }
    }

    /**
     * Handle trips API response
     */
    private suspend fun handleTripsResponse(response: retrofit2.Response<MyTripListData>) {
        withContext(Dispatchers.Main) {
            swipeRefreshLayout.isRefreshing = false

            if (response.isSuccessful) {
                tripList = response.body()?.data ?: emptyList()
                updateUI()
            } else {
                showToast("Something went wrong: ${response.message()}")
            }
        }
    }

    /**
     * Handle trips API failure
     */
    private suspend fun handleTripsFailure(t: Throwable) {
        withContext(Dispatchers.Main) {
            swipeRefreshLayout.isRefreshing = false
            showToast("Error: ${t.localizedMessage}")
        }
    }

    /**
     * Update UI based on trip list
     */
    private fun updateUI() {
        if (tripList.isEmpty()) {
            emptyStateLayout.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyStateLayout.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter = MyTripsAdapter(tripList, this@TripsFragment)
            recyclerView.adapter = adapter
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
                Log.e("TripsFragment", "Taximeter initialization failed: ${e.message}")
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
            taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@TripsFragment)
            taximeterManager.OnTripDetailsExtendedResponseReceived.registerListener(this@TripsFragment)

            // Request initial status with timeout
            withContext(Dispatchers.IO) {
                taximeterManager.askDisplayExtendedStatus()
                stateMutex.withLock {
                    lastStatusRequestTime = System.currentTimeMillis()
                }
            }

            Log.d("TripsFragment", "Taximeter initialized successfully")
        } catch (e: Exception) {
            Log.e("TripsFragment", "Failed to setup taximeter listeners: ${e.message}")
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

        Log.d("TripsFragment", "Taximeter connection: $connected")

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
     * Handle trip action buttons (accept/start/complete) with enhanced connection validation
     */
    override fun onAcceptStartEndButtonClick(trip: Trip) {
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
                    when {
                        trip.can_accept -> handleAcceptTrip(trip)
                        trip.can_start -> handleStartTrip(trip)
                        trip.can_complete -> handleCompleteTrip(trip)
                    }
                } catch (e: Exception) {
                    Log.e("TripsFragment", "Trip processing failed: ${e.message}")
                    showToast("Failed to process trip: ${e.localizedMessage}")
                } finally {
                    stateMutex.withLock { isProcessingTrip = false }
                }
            }
        }
    }

    /**
     * Handle trip acceptance
     */
    private suspend fun handleAcceptTrip(trip: Trip) {
        withContext(Dispatchers.IO) {
            Api.retrofitService
                .acceptTrip(authToken, trip.id.toString())
                .enqueue(object : retrofit2.Callback<MyTripData> {
                    override fun onResponse(
                        call: retrofit2.Call<MyTripData>,
                        response: retrofit2.Response<MyTripData>
                    ) {
                        lifecycleScope.launch {
                            handleAcceptTripResponse(response)
                        }
                    }

                    override fun onFailure(call: retrofit2.Call<MyTripData>, t: Throwable) {
                        lifecycleScope.launch {
                            showToast("Error: ${t.localizedMessage}")
                        }
                    }
                })
        }
    }

    /**
     * Handle accept trip response
     */
    private suspend fun handleAcceptTripResponse(response: retrofit2.Response<MyTripData>) {
        withContext(Dispatchers.Main) {
            if (response.isSuccessful) {
                loadTrips()
                showToast("Trip accepted successfully")
            } else {
                showToast("Failed to accept trip: ${response.code()}")
            }
        }
    }

    /**
     * Handle trip start with enhanced connection validation
     */
    private suspend fun handleStartTrip(trip: Trip) {
        // Validate connection status
        val connectionValid = stateMutex.withLock {
            isTaximeterInitialized && isConnected
        }

        if (!connectionValid) {
            showToast("Taximeter not connected. Please wait for reconnection.")
            return
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
            showToast("Taximeter Status must be ForHire!")
            return
        }

        // Start forfait trip with retry logic
        val success = startForfaitTripWithRetry(trip.total)
        if (!success) {
            showToast("Failed to start forfait trip after multiple attempts")
            return
        }

        // Get current location and start trip
        val locationSuccess = getCurrentLocation()
        if (!locationSuccess) {
            showToast("Failed to get current location")
            return
        }

        // Start trip in backend
        startTripInBackend(trip)
    }

    /**
     * Start forfait trip with retry mechanism
     */
    private suspend fun startForfaitTripWithRetry(total: String): Boolean {
        repeat(3) { attempt ->
            try {
                val success = withContext(Dispatchers.IO) {
                    taxiModelAgentt?.startForfaitTrip(total)
                    true
                }

                if (success) {
                    Log.d("TripsFragment", "Started forfait trip with total: $total")
                    return true
                }
            } catch (e: Exception) {
                Log.e("TripsFragment", "Forfait trip attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < 2) {
                    delay(1000) // Wait before retry
                }
            }
        }
        return false
    }

    /**
     * Start trip in backend
     */
    private suspend fun startTripInBackend(trip: Trip) {
        val (shiftId, tripId) = stateMutex.withLock {
            Pair(currentShiftID, currentTripId)
        }

        val tripAddressBody = PickupTripAddressBody(
            shiftId ?: 0,
            tripId?.toInt(),
            addressInfo.name,
            addressInfo.lat,
            addressInfo.lng
        )

        withContext(Dispatchers.IO) {
            Api.retrofitService
                .startTrip(authToken, trip.id.toString(), tripAddressBody)
                .enqueue(object : retrofit2.Callback<MyTripData> {
                    override fun onResponse(
                        call: retrofit2.Call<MyTripData>,
                        response: retrofit2.Response<MyTripData>
                    ) {
                        lifecycleScope.launch {
                            handleStartTripResponse(response, trip.total)
                        }
                    }

                    override fun onFailure(call: retrofit2.Call<MyTripData>, t: Throwable) {
                        lifecycleScope.launch {
                            showToast("Error: ${t.localizedMessage}")
                        }
                    }
                })
        }
    }

    /**
     * Handle start trip response
     */
    private suspend fun handleStartTripResponse(
        response: retrofit2.Response<MyTripData>,
        total: String
    ) {
        withContext(Dispatchers.Main) {
            if (response.isSuccessful) {
                loadTrips()
                showToast("Trip started successfully with amount: $total ALL")
            } else {
                showToast("Failed to start trip: ${response.message()}")
            }
        }
    }

    /**
     * Handle trip completion with enhanced connection validation
     */
    private suspend fun handleCompleteTrip(trip: Trip) {
        // Validate connection status
        val connectionValid = stateMutex.withLock {
            isTaximeterInitialized && isConnected
        }

        if (!connectionValid) {
            showToast("Taximeter not connected. Please wait for reconnection.")
            return
        }

        // Validate taximeter status with retry logic
        var statusValidationAttempts = 0
        var statusValid = false

        while (statusValidationAttempts < 3 && !statusValid) {
            val extendedStatus = stateMutex.withLock { currentExtendedStatus }

            if (extendedStatus?.StatusCode == TaximeterStatusCodes.Hired) {
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
            showToast("Taximeter Status must be Hired!")
            return
        }

        // Request taximeter updates with retry
        requestTaxiMeterUpdatesWithRetry()

        // Press taximeter buttons
        pressTaxiMeterButtons()

        // Get current location
        val locationSuccess = getCurrentLocation()
        if (!locationSuccess) {
            showToast("Failed to get current location")
            return
        }

        // Complete trip in backend
        completeTripInBackend(trip)
    }

    /**
     * Request taximeter status and trip details updates with retry
     */
    private suspend fun requestTaxiMeterUpdatesWithRetry() {
        repeat(3) { attempt ->
            try {
                withContext(Dispatchers.IO) {
                    taximeterManagerr?.askDisplayExtendedStatus()
                    taximeterManagerr?.askTripDetailsExtended()
                }
                return // Success, exit retry loop
            } catch (e: Exception) {
                Log.e("TripsFragment", "Update request attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < 2) {
                    delay(500) // Wait before retry
                }
            }
        }
    }

    /**
     * Press taximeter buttons for completion
     */
    private suspend fun pressTaxiMeterButtons() {
        withContext(Dispatchers.IO) {
            try {
                val buttons = TaximeterButtons().apply { op = true }
                taximeterManagerr?.pressKeys(buttons)
                delay(1000L)
                taximeterManagerr?.pressKeys(buttons)
            } catch (e: Exception) {
                Log.e("TripsFragment", "Failed to press taximeter buttons: ${e.message}")
            }
        }
    }

    /**
     * Complete trip in backend
     */
    private suspend fun completeTripInBackend(trip: Trip) {
        val distanceInMeter = hiredDistance * 1000
        val tripAddressBody = DropOffTripAddressBody(
            addressInfo.name,
            addressInfo.lat,
            addressInfo.lng,
            distanceInMeter
        )

        withContext(Dispatchers.IO) {
            Api.retrofitService
                .completeTrip(authToken, trip.id.toString(), tripAddressBody)
                .enqueue(object : retrofit2.Callback<MyTripData> {
                    override fun onResponse(
                        call: retrofit2.Call<MyTripData>,
                        response: retrofit2.Response<MyTripData>
                    ) {
                        lifecycleScope.launch {
                            handleCompleteTripResponse(response)
                        }
                    }

                    override fun onFailure(call: retrofit2.Call<MyTripData>, t: Throwable) {
                        lifecycleScope.launch {
                            showToast("Error: ${t.localizedMessage}")
                        }
                    }
                })
        }
    }

    /**
     * Handle complete trip response
     */
    private suspend fun handleCompleteTripResponse(response: retrofit2.Response<MyTripData>) {
        withContext(Dispatchers.Main) {
            if (response.isSuccessful) {
                loadTrips()
                showToast("Trip completed successfully")
            } else {
                showToast("Failed to complete trip: ${response.code()}")
            }
        }
    }

    /**
     * Get current location with improved error handling
     */
    private suspend fun getCurrentLocation(): Boolean {
        return withContext(Dispatchers.Main) {
            if (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    1001
                )
                return@withContext false
            }

            try {
                val fusedLocationClient =
                    LocationServices.getFusedLocationProviderClient(requireContext())
                val locationTask = fusedLocationClient.lastLocation

                var locationReceived = false
                locationTask.addOnSuccessListener { loc: Location? ->
                    lifecycleScope.launch {
                        locationReceived = processLocation(loc)
                    }
                }.addOnFailureListener { e ->
                    Log.e("TripsFragment", "Location request failed: ${e.message}")
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
                Log.e("TripsFragment", "Location error: ${e.message}")
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
                val geocoder = Geocoder(requireContext(), Locale.getDefault())
                val addresses = geocoder.getFromLocation(addressInfo.lat, addressInfo.lng, 1)
                addressInfo.name = if (!addresses.isNullOrEmpty()) {
                    addresses[0].getAddressLine(0)
                } else {
                    "Unknown Address"
                }
                true
            } catch (e: IOException) {
                Log.e("TripsFragment", "Geocoder error: ${e.message}")
                addressInfo.name = "Geocoder Error"
                true // Still continue with coordinates
            }
        }
    }

    /**
     * Handle opening trip in maps
     */
    override fun onOpenInMapsClick(trip: Trip) {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                openMapsWithRoute(location, trip)
            } else {
                showToast("Could not get current location")
            }
        }
    }

    /**
     * Open Google Maps with route
     */
    private fun openMapsWithRoute(location: Location, trip: Trip) {
        val uri = ("https://www.google.com/maps/dir/?api=1" +
                "&origin=${location.latitude},${location.longitude}" +
                "&destination=${trip.requested_drop_off_address_lat},${trip.requested_drop_off_address_lng}" +
                "&waypoints=${trip.requested_pickup_address_lat},${trip.requested_pickup_address_lng}" +
                "&travelmode=driving").toUri()

        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.google.android.apps.maps")

        try {
            context?.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            context?.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
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
                hiredDistance = response?.extendedStatusData?.HiredDistance ?: 0f
                isConnected = true // Status received means we're connected
            }

            Log.d(
                "TripsFragment",
                "Status updated - Distance: $hiredDistance, Shift: $currentShiftID, Connected: true"
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
                "TripsFragment",
                "Trip details updated - TripID: $currentTripId, ShiftID: $currentShiftID, Connected: true"
            )
        }
    }

    /**
     * Show toast message on main thread
     */
    private fun showToast(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Enhanced cleanup with proper job cancellation and resource management
     */
    private fun cleanup() {
        // Cancel all running jobs
        tripsLoadJob?.cancel()
        tripProcessingJob?.cancel()
        connectionMonitorJob?.cancel()
        reconnectionJob?.cancel()
        heartbeatJob?.cancel()

        // Unregister listeners safely
        try {
            taximeterManagerr?.OnDisplayExtendedStatusReceived?.unregisterListener(this)
            taximeterManagerr?.OnTripDetailsExtendedResponseReceived?.unregisterListener(this)
        } catch (e: Exception) {
            Log.e("TripsFragment", "Error unregistering listeners: ${e.message}")
        }

        // Clear references
        taximeterManagerr = null
        taxiModelAgentt = null

        Log.d("TripsFragment", "Cleanup completed")
    }
}
