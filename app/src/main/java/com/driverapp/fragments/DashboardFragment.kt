package com.driverapp.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.digitax.android.libcomtax2.taximeter.events.CurrentFareResponseListener
import com.digitax.android.libcomtax2.taximeter.events.DisplayExtendedStatusListener
import com.digitax.android.libcomtax2.taximeter.messages.CurrentFareResponse
import com.digitax.android.libcomtax2.taximeter.messages.DisplayExtendedStatusResponse
import com.digitax.android.libcomtax2.taximeter.objects.ExtendedStatus
import com.digitax.android.libcomtax2.taximeter.objects.TaximeterStatusCodes
import com.digitax.protocols.ILoggerHandler
import com.digitax.protocols.LogEventArgs
import com.driverapp.R
import com.driverapp.handlers.OnlineStatusHandler
import com.driverapp.models.TripEventManager
import com.driverapp.models.TripUpdateListener
import com.driverapp.networkApi.models.Trip
import com.driverapp.utils.DigitaxTaximeterInitializer
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.utils.TaxiModelAgent
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive

/**
 * DashboardFragment serves as the main driver interface displaying map, trip information,
 * and taximeter status. Handles online/offline status, trip notifications, and location tracking
 * with robust taximeter connection management.
 */
class DashboardFragment : Fragment(), OnMapReadyCallback, ILoggerHandler,
    CurrentFareResponseListener, DisplayExtendedStatusListener {

    // region UI Components
    private lateinit var map: GoogleMap
    private lateinit var offlineLayout: LinearLayout
    private lateinit var onlineLayout: LinearLayout
    private lateinit var offlineIcon: ImageView
    private lateinit var onlineIcon: ImageView
    private lateinit var offlineText: TextView
    private lateinit var onlineText: TextView
    private lateinit var statusCode: TextView
    private lateinit var shiftNo: TextView
    private lateinit var currentFareAmount: TextView
    private lateinit var total: TextView
    private lateinit var relativeAssignedTrip: RelativeLayout
    // endregion

    // region Core Services
    private lateinit var sharedPreferencesManager: SharedPreferencesManager
    private lateinit var vibrator: Vibrator
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var onlineStatusHandler: OnlineStatusHandler
    // endregion

    // region Taximeter Components
    private var taximeterManager: TaximeterManager? = null
    private var taxiModelAgent: TaxiModelAgent? = null
    // endregion

    // region Enhanced State Management with Thread Safety
    private val stateMutex = Mutex()
    private var currentExtendedStatus: ExtendedStatus? = null
    private var isTaximeterInitialized = false
    private var isConnected = false
    private var isMapReady = false
    private var isRingingActive = false
    private var isProcessingOnlineStatus = false
    private var reconnectionAttempts = 0
    private var lastStatusRequestTime = 0L
    // endregion

    // region Notification System
    private var mediaPlayer: MediaPlayer? = null
    private var currentTrip: Trip? = null
    private var pendingTrip: Trip? = null
    private var isAssigned = false
    // endregion

    // region Enhanced Coroutine Jobs Management
    private var initializationJob: Job? = null
    private var statusUpdateJob: Job? = null
    private var locationJob: Job? = null
    private var connectionMonitorJob: Job? = null
    private var reconnectionJob: Job? = null
    private var heartbeatJob: Job? = null
    // endregion

    // Connection management constants (same as AddOfferActivity)
    companion object {
        private const val MAX_RECONNECTION_ATTEMPTS = 5
        private const val RECONNECTION_DELAY_MS = 3000L
        private const val HEARTBEAT_INTERVAL_MS = 10000L
        private const val STATUS_REQUEST_TIMEOUT_MS = 5000L
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeComponents()
        setupUI(view)
        initializeMap()
        startInitialization()
        startConnectionMonitoring()
    }

    override fun onResume() {
        super.onResume()
        TripEventManager.addListener(tripUpdateListener)

        // Load and display current status
        val currentStatus = onlineStatusHandler.isOnline()
        viewLifecycleOwner.lifecycleScope.launch {
            if (isAdded) {
                updateStatusUI(currentStatus)
                // Check connection health when resuming
                checkConnectionHealth()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        TripEventManager.removeListener(tripUpdateListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cleanup()
    }

    /**
     * Initialize core components and services
     */
    private fun initializeComponents() {
        if (!isAdded) return

        sharedPreferencesManager = SharedPreferencesManager(requireContext())
        vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        onlineStatusHandler =
            OnlineStatusHandler(requireContext(), viewLifecycleOwner.lifecycleScope)
    }

    /**
     * Setup UI components and event listeners
     */
    private fun setupUI(view: View) {
        // Initialize views
        relativeAssignedTrip = view.findViewById(R.id.relativeAssignedTrip)
        offlineLayout = view.findViewById(R.id.offline_layout)
        onlineLayout = view.findViewById(R.id.online_layout)
        offlineIcon = view.findViewById(R.id.offline_icon)
        onlineIcon = view.findViewById(R.id.online_icon)
        offlineText = view.findViewById(R.id.offline_text)
        onlineText = view.findViewById(R.id.online_text)
        shiftNo = view.findViewById(R.id.shiftNo)
        statusCode = view.findViewById(R.id.statusCode)
        currentFareAmount = view.findViewById(R.id.currentFareAmount)
        total = view.findViewById(R.id.total)

        // Setup click listeners
        setupStatusClickListeners()
    }

    /**
     * Setup online/offline status click listeners
     */
    private fun setupStatusClickListeners() {
        offlineLayout.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                handleStatusChange(false)
            }
        }

        onlineLayout.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                handleStatusChange(true)
            }
        }
    }

    /**
     * Initialize map fragment
     */
    private fun initializeMap() {
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    /**
     * Start initialization process with enhanced error handling
     */
    private fun startInitialization() {
        initializationJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (isAdded) {
                    stateMutex.withLock {
                        reconnectionAttempts = 0
                    }
                    initializeTaximeter()
                }
            } catch (e: Exception) {
                Log.e("DashboardFragment", "Initialization failed: ${e.message}")
                if (isAdded) {
                    showToast("Failed to initialize taximeter connection")
                }
                scheduleReconnection()
            }
        }
    }

    /**
     * Start connection monitoring and heartbeat (from AddOfferActivity)
     */
    private fun startConnectionMonitoring() {
        // Start heartbeat monitoring
        heartbeatJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && isAdded) {
                delay(HEARTBEAT_INTERVAL_MS)
                checkConnectionHealth()
            }
        }

        // Start connection status monitoring
        connectionMonitorJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && isAdded) {
                delay(5000) // Check every 5 seconds
                monitorConnectionStatus()
            }
        }
    }

    /**
     * Check connection health with timeout (from AddOfferActivity)
     */
    private suspend fun checkConnectionHealth() {
        if (!isAdded) return

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
            Log.e("DashboardFragment", "Health check failed: ${e.message}")
            handleConnectionLoss()
        }
    }

    /**
     * Monitor connection status and handle disconnections (from AddOfferActivity)
     */
    private suspend fun monitorConnectionStatus() {
        if (!isAdded) return

        stateMutex.withLock {
            if (isTaximeterInitialized && !isConnected) {
                Log.w("DashboardFragment", "Connection lost detected, attempting reconnection")
                handleConnectionLoss()
            }
        }
    }

    /**
     * Handle connection loss with automatic reconnection (from AddOfferActivity)
     */
    private suspend fun handleConnectionLoss() {
        if (!isAdded) return

        stateMutex.withLock {
            isConnected = false
            isTaximeterInitialized = false
            currentExtendedStatus = null
        }

        scheduleReconnection()
    }

    /**
     * Schedule reconnection attempt with exponential backoff (from AddOfferActivity)
     */
    private fun scheduleReconnection() {
        if (!isAdded) return

        reconnectionJob?.cancel()
        reconnectionJob = viewLifecycleOwner.lifecycleScope.launch {
            stateMutex.withLock {
                if (reconnectionAttempts >= MAX_RECONNECTION_ATTEMPTS) {
                    if (isAdded) {
                        withContext(Dispatchers.Main) {
                            showToast("Unable to reconnect to taximeter. Please check connection.")
                        }
                    }
                    return@launch
                }
                reconnectionAttempts++
            }

            val delay = RECONNECTION_DELAY_MS * reconnectionAttempts
            Log.d(
                "DashboardFragment",
                "Scheduling reconnection attempt $reconnectionAttempts in ${delay}ms"
            )
            delay(delay)

            if (isAdded) {
                try {
                    initializeTaximeter()
                } catch (e: Exception) {
                    Log.e("DashboardFragment", "Reconnection attempt failed: ${e.message}")
                    scheduleReconnection()
                }
            }
        }
    }

    /**
     * Initialize taximeter connection with enhanced callback handling
     */
    private suspend fun initializeTaximeter() {
        if (!isAdded) return

        withContext(Dispatchers.IO) {
            try {
                val context = context ?: return@withContext
                val activity = activity ?: return@withContext

                DigitaxTaximeterInitializer(context, activity)
                    .initialize(object : DigitaxTaximeterInitializer.Callback {
                        override fun onInitialized(
                            taximeterManager: TaximeterManager,
                            taxiModelAgent: TaxiModelAgent
                        ) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                if (isAdded) {
                                    handleTaximeterInitialized(taximeterManager, taxiModelAgent)
                                }
                            }
                        }

                        override fun onConnectionStatusChanged(connected: Boolean) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                if (isAdded) {
                                    handleConnectionStatusChange(connected)
                                }
                            }
                        }
                    })
            } catch (e: Exception) {
                Log.e("DashboardFragment", "Taximeter initialization failed: ${e.message}")
                throw e
            }
        }
    }

    /**
     * Handle successful taximeter initialization (enhanced from AddOfferActivity)
     */
    private suspend fun handleTaximeterInitialized(
        taximeterManager: TaximeterManager,
        taxiModelAgent: TaxiModelAgent
    ) {
        if (!isAdded) return

        stateMutex.withLock {
            this.taximeterManager = taximeterManager
            this.taxiModelAgent = taxiModelAgent
            isTaximeterInitialized = true
            reconnectionAttempts = 0 // Reset reconnection attempts on successful connection
        }

        try {
            // Register listeners
            taximeterManager.OnCurrentFareResponseReceived.registerListener(this@DashboardFragment)
            taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@DashboardFragment)

            // Request initial status with timeout
            withContext(Dispatchers.IO) {
                taximeterManager.askDisplayExtendedStatus()
                stateMutex.withLock {
                    lastStatusRequestTime = System.currentTimeMillis()
                }
            }

            Log.d("DashboardFragment", "Taximeter initialized successfully")
        } catch (e: Exception) {
            Log.e("DashboardFragment", "Failed to setup taximeter listeners: ${e.message}")
            throw e
        }
    }

    /**
     * Handle taximeter connection status changes with improved logic (from AddOfferActivity)
     */
    private suspend fun handleConnectionStatusChange(connected: Boolean) {
        if (!isAdded) return

        stateMutex.withLock {
            isConnected = connected
            if (!connected) {
                isTaximeterInitialized = false
                currentExtendedStatus = null
            }
        }

        Log.d("DashboardFragment", "Taximeter connection: $connected")

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
     * Request taximeter status update with connection validation
     */
    private suspend fun requestTaximeterStatusUpdate() {
        if (!isAdded) return

        val (manager, initialized, connected) = stateMutex.withLock {
            Triple(taximeterManager, isTaximeterInitialized, isConnected)
        }

        if (manager != null && initialized && connected) {
            withContext(Dispatchers.IO) {
                try {
                    manager.askDisplayExtendedStatus()
                    stateMutex.withLock {
                        lastStatusRequestTime = System.currentTimeMillis()
                    }
                } catch (e: Exception) {
                    Log.e("DashboardFragment", "Failed to request status update: ${e.message}")
                    handleConnectionLoss()
                }
            }
        }
    }

    /**
     * Handle online/offline status change
     */
    private suspend fun handleStatusChange(isOnline: Boolean) {
        if (!isAdded) return

        // Prevent multiple simultaneous status changes
        if (isProcessingOnlineStatus) {
            showToast("Status change already in progress")
            return
        }

        stateMutex.withLock {
            isProcessingOnlineStatus = true
        }

        // Update UI immediately for better UX
        updateStatusUI(isOnline)

        // Vibrate for feedback
        vibrate()

        // Use the handler to manage status
        onlineStatusHandler.setOnlineStatus(
            isOnline,
            object : OnlineStatusHandler.StatusChangeCallback {
                override fun onStatusChanged(
                    isOnline: Boolean,
                    success: Boolean,
                    message: String?
                ) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        stateMutex.withLock {
                            isProcessingOnlineStatus = false
                        }
                        if (isAdded) {
                            message?.let { showToast(it) }
                            // Revert UI if failed
                            if (!success) {
                                updateStatusUI(!isOnline)
                            }
                        }
                    }
                }
            })
    }

    /**
     * Update status UI components
     */
    private suspend fun updateStatusUI(isOnline: Boolean) {
        withContext(Dispatchers.Main) {
            // Check if fragment is still attached and views are available
            if (!isAdded || !::onlineIcon.isInitialized) {
                Log.w("DashboardFragment", "Fragment not ready for UI update")
                return@withContext
            }

            val context = requireContext() // Safe to call here since we checked isAdded

            if (isOnline) {
                // Set online to active
                onlineIcon.setColorFilter(ContextCompat.getColor(context, R.color.black))
                onlineText.setTextColor(ContextCompat.getColor(context, R.color.black))
                // Set offline to inactive
                offlineIcon.setColorFilter(ContextCompat.getColor(context, R.color.grey))
                offlineText.setTextColor(ContextCompat.getColor(context, R.color.grey))
            } else {
                // Set offline to active
                offlineIcon.setColorFilter(ContextCompat.getColor(context, R.color.black))
                offlineText.setTextColor(ContextCompat.getColor(context, R.color.black))
                // Set online to inactive
                onlineIcon.setColorFilter(ContextCompat.getColor(context, R.color.grey))
                onlineText.setTextColor(ContextCompat.getColor(context, R.color.grey))
            }
        }
    }

    /**
     * Trip update listener for handling incoming trips
     */
    private val tripUpdateListener = object : TripUpdateListener {
        override fun onTripReceived(trip: Trip, assigned: Boolean) {
            viewLifecycleOwner.lifecycleScope.launch {
                if (isAdded) {
                    handleTripReceived(trip, assigned)
                }
            }
        }
    }

    /**
     * Handle incoming trip notification
     */
    private suspend fun handleTripReceived(trip: Trip, assigned: Boolean) {
        if (!isAdded) return

        withContext(Dispatchers.Main) {
            // Store trip data
            sharedPreferencesManager.putTrip("current_trip", trip)
            stateMutex.withLock {
                currentTrip = trip
                isAssigned = assigned
            }

            // Check if this is a new trip
            val lastTripId = getLastRangTripId()
            if (lastTripId != trip.id) {
                setLastRangTripId(trip.id)
                startTripNotification()
            } else {
                stopTripNotification()
            }

            // Update UI
            if (isMapReady) {
                updateTripUI(trip)
            } else {
                pendingTrip = trip
            }
        }
    }

    /**
     * Start trip notification (sound and vibration)
     */
    private suspend fun startTripNotification() {
        if (!isAdded) return

        stateMutex.withLock {
            if (isRingingActive) return
            isRingingActive = true
        }

        withContext(Dispatchers.Main) {
            val ctx = context ?: return@withContext

            // Start audio notification
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer.create(ctx, R.raw.quietly_brilliant)
                mediaPlayer?.isLooping = true
                mediaPlayer?.start()
            }

            // Start vibration
            startVibrationPattern()
        }
    }

    /**
     * Stop trip notification
     */
    private suspend fun stopTripNotification() {
        stateMutex.withLock {
            isRingingActive = false
        }

        withContext(Dispatchers.Main) {
            // Stop audio
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null

            // Stop vibration
            vibrator.cancel()
        }
    }

    /**
     * Start vibration pattern for trip notification
     */
    private fun startVibrationPattern() {
        val pattern = longArrayOf(0, 500, 1000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }
    }

    /**
     * Update map and UI with trip information
     */
    private suspend fun updateTripUI(trip: Trip) {
        withContext(Dispatchers.Main) {
            if (!::map.isInitialized || !isAdded) return@withContext

            map.clear()

            // Add pickup location marker
            val pickupLatLng = LatLng(
                trip.requested_pickup_address_lat,
                trip.requested_pickup_address_lng
            )
            map.addMarker(
                MarkerOptions()
                    .position(pickupLatLng)
                    .title("Pickup Location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            )

            // Add current location marker if available
            addCurrentLocationMarker()
        }
    }

    /**
     * Add current location marker to map
     */
    private fun addCurrentLocationMarker() {
        locationJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!isAdded) return@launch

                val location = getCurrentLocation()
                location?.let { loc ->
                    withContext(Dispatchers.Main) {
                        if (isAdded && ::map.isInitialized) {
                            val userLatLng = LatLng(loc.latitude, loc.longitude)
                            val markerOptions = MarkerOptions()
                                .position(userLatLng)
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.small_taxi_car_optimized))
                            map.addMarker(markerOptions)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DashboardFragment", "Failed to add current location marker: ${e.message}")
            }
        }
    }

    /**
     * Get current location with proper permission handling
     */
    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocation(): Location? {
        return withContext(Dispatchers.Main) {
            val ctx = context ?: return@withContext null

            if (ActivityCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
                return@withContext null
            }

            try {
                var currentLocation: Location? = null
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    currentLocation = location
                }
                // Wait for location callback
                delay(1000)
                currentLocation
            } catch (e: Exception) {
                Log.e("DashboardFragment", "Location error: ${e.message}")
                null
            }
        }
    }

    /**
     * Trip ID management
     */
    private fun getLastRangTripId(): Int? =
        sharedPreferencesManager.getInt("lastRangTripId", -1).takeIf { it != -1 }

    private fun setLastRangTripId(id: Int) =
        sharedPreferencesManager.saveInt("lastRangTripId", id)

    /**
     * Map ready callback
     */
    override fun onMapReady(googleMap: GoogleMap) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!isAdded) return@launch

            stateMutex.withLock {
                map = googleMap
                isMapReady = true
            }

            // Process pending trip if available
            val pending = stateMutex.withLock { pendingTrip }
            if (pending != null) {
                updateTripUI(pending)
                stateMutex.withLock { pendingTrip = null }
            }

            enableMapLocation()
        }
    }

    /**
     * Enable location on map
     */
    @SuppressLint("MissingPermission")
    private suspend fun enableMapLocation() {
        withContext(Dispatchers.Main) {
            val ctx = context ?: return@withContext

            if (ActivityCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                if (::map.isInitialized && isAdded) {
                    map.isMyLocationEnabled = true
                    // Center map on user location
                    viewLifecycleOwner.lifecycleScope.launch {
                        centerMapOnUserLocation()
                    }
                }
            } else {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
        }
    }

    /**
     * Center map on user's current location
     */
    @SuppressLint("MissingPermission")
    private suspend fun centerMapOnUserLocation() {
        if (!isAdded) return

        val location = getCurrentLocation()
        location?.let { loc ->
            withContext(Dispatchers.Main) {
                if (::map.isInitialized && isAdded) {
                    val userLatLng = LatLng(loc.latitude, loc.longitude)
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(userLatLng, 16f)
                    )
                }
            }
        }
    }

    /**
     * Handle taximeter extended status updates with connection validation
     */
    override fun onDisplayExtendedStatus(
        sender: Any?,
        response: DisplayExtendedStatusResponse?
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (isAdded) {
                stateMutex.withLock {
                    currentExtendedStatus = response?.extendedStatusData
                    isConnected = true // Status received means we're connected
                }
                updateTaximeterUI(response?.extendedStatusData)

                Log.d(
                    "DashboardFragment",
                    "Status updated - Fare: ${response?.extendedStatusData?.CurrentFareAmount}, Shift: ${response?.extendedStatusData?.ShiftNumber}"
                )
            }
        }
    }

    /**
     * Update taximeter UI components
     */
    private suspend fun updateTaximeterUI(extendedStatus: ExtendedStatus?) {
        withContext(Dispatchers.Main) {
            if (!isAdded || !::statusCode.isInitialized) return@withContext

            // Update status code
            statusCode.text = extendedStatus?.StatusCode?.let { statusCode ->
                when (statusCode) {
                    TaximeterStatusCodes.ForHire -> "E LIRE"
                    TaximeterStatusCodes.Hired -> "E ZENE"
                    TaximeterStatusCodes.Stopped -> "ARKA"
                    else -> "N/A"
                }
            } ?: "N/A"

            // Update fare amount
            val shouldShowFare = extendedStatus?.StatusCode == TaximeterStatusCodes.Hired ||
                    extendedStatus?.StatusCode == TaximeterStatusCodes.Stopped
            currentFareAmount.text = if (shouldShowFare) {
                "${extendedStatus?.CurrentFareAmount ?: 0} ALL"
            } else {
                "0 ALL"
            }

            // Update shift number
            shiftNo.text = "TURNI: ${extendedStatus?.ShiftNumber ?: "N/A"}"
        }
    }

    /**
     * Handle current fare response with connection validation
     */
    override fun onCurrentFareResponse(sender: Any?, fare: CurrentFareResponse?) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (isAdded) {
                // Mark connection as active since we received a response
                stateMutex.withLock {
                    isConnected = true
                }

                val trip = stateMutex.withLock { currentTrip }
                Log.d(
                    "DashboardFragment",
                    "Current Fare Response: ${fare?.currentFareAmount}, Trip ID: ${trip?.id}"
                )
                // TODO: Handle fare updates if needed
                // This could be used to update trip fare in real-time
            }
        }
    }

    /**
     * Simple vibration for user feedback
     */
    private fun vibrate() {
        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        }
    }

    /**
     * Show toast message safely checking fragment state
     */
    private fun showToast(message: String) {
        // Check if fragment is still attached before showing toast
        if (!isAdded) {
            Log.w("DashboardFragment", "Fragment not attached, skipping toast: $message")
            return
        }

        val ctx = context
        if (ctx == null) {
            Log.w("DashboardFragment", "Context not available, skipping toast: $message")
            return
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                if (isAdded) { // Check again on main thread
                    context?.let { safeContext ->
                        Toast.makeText(safeContext, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Logger interface implementation
     */
    override fun Log(lea: LogEventArgs?) {
        lea?.let {
            val log = "[${it.Data}] ${it.Tag}"
            Log.d("TaximeterLog", log)
        }
    }

    /**
     * Enhanced cleanup with proper job cancellation and resource management (from AddOfferActivity)
     */
    private fun cleanup() {
        // Cancel all running jobs first to prevent callbacks after cleanup
        initializationJob?.cancel()
        statusUpdateJob?.cancel()
        locationJob?.cancel()
        connectionMonitorJob?.cancel()
        reconnectionJob?.cancel()
        heartbeatJob?.cancel()

        // Stop notifications
        lifecycleScope.launch {
            stopTripNotification()
        }

        // Unregister listeners safely
        try {
            taximeterManager?.OnCurrentFareResponseReceived?.unregisterListener(this)
            taximeterManager?.OnDisplayExtendedStatusReceived?.unregisterListener(this)
        } catch (e: Exception) {
            Log.e("DashboardFragment", "Error unregistering listeners: ${e.message}")
        }

        // Clear references
        taximeterManager = null
        taxiModelAgent = null

        Log.d("DashboardFragment", "Cleanup completed")
    }
}
