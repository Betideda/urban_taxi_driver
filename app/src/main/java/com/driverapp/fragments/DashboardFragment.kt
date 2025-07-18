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
import com.driverapp.models.TripEventManager
import com.driverapp.models.TripUpdateListener
import com.driverapp.networkApi.Api
import com.driverapp.networkApi.models.OnlineStatusBody
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
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * DashboardFragment serves as the main driver interface displaying map, trip information,
 * and taximeter status. Handles online/offline status, trip notifications, and location tracking.
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
    // endregion

    // region Taximeter Components
    private var taximeterManager: TaximeterManager? = null
    private var taxiModelAgent: TaxiModelAgent? = null
    // endregion

    // region State Management with Thread Safety
    private val stateMutex = Mutex()
    private var currentExtendedStatus: ExtendedStatus? = null
    private var isTaximeterInitialized = false
    private var isMapReady = false
    private var isRingingActive = false
    private var isProcessingOnlineStatus = false
    // endregion

    // region Notification System
    private var mediaPlayer: MediaPlayer? = null
    private var currentTrip: Trip? = null
    private var pendingTrip: Trip? = null
    private var isAssigned = false
    // endregion

    // region Coroutine Jobs
    private var initializationJob: Job? = null
    private var statusUpdateJob: Job? = null
    private var locationJob: Job? = null
    // endregion

    // region Authentication
    private val authToken: String
        get() = "Bearer ${sharedPreferencesManager.getString("token", "")}"
    // endregion

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
    }

    override fun onResume() {
        super.onResume()
        TripEventManager.addListener(tripUpdateListener)

        // Request taximeter status update if available
        lifecycleScope.launch {
            requestTaximeterStatusUpdate()
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
        sharedPreferencesManager = SharedPreferencesManager(requireContext())
        vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
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
            lifecycleScope.launch {
                handleStatusChange(false)
            }
        }

        onlineLayout.setOnClickListener {
            lifecycleScope.launch {
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
     * Start initialization process
     */
    private fun startInitialization() {
        initializationJob = lifecycleScope.launch {
            try {
                initializeTaximeter()
            } catch (e: Exception) {
                Log.e("DashboardFragment", "Initialization failed: ${e.message}")
                showToast("Failed to initialize taximeter connection")
            }
        }
    }

    /**
     * Initialize taximeter connection with proper error handling
     */
    private suspend fun initializeTaximeter() {
        withContext(Dispatchers.IO) {
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
            this.taximeterManager = taximeterManager
            this.taxiModelAgent = taxiModelAgent
            isTaximeterInitialized = true
        }

        // Register listeners
        taximeterManager.OnCurrentFareResponseReceived.registerListener(this@DashboardFragment)
        taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@DashboardFragment)

        // Request initial status
        requestTaximeterStatusUpdate()

        Log.d("DashboardFragment", "Taximeter initialized successfully")
    }

    /**
     * Handle taximeter connection status changes
     */
    private suspend fun handleConnectionStatusChange(connected: Boolean) {
        stateMutex.withLock {
            if (!connected) {
                isTaximeterInitialized = false
                currentExtendedStatus = null
            }
        }

        Log.d("DashboardFragment", "Taximeter connection: $connected")
    }

    /**
     * Request taximeter status update
     */
    private suspend fun requestTaximeterStatusUpdate() {
        val manager = stateMutex.withLock { taximeterManager }
        if (manager != null && isTaximeterInitialized) {
            withContext(Dispatchers.IO) {
                try {
                    manager.askDisplayExtendedStatus()
                } catch (e: Exception) {
                    Log.e("DashboardFragment", "Failed to request status update: ${e.message}")
                }
            }
        }
    }

    /**
     * Handle online/offline status change
     */
    private suspend fun handleStatusChange(isOnline: Boolean) {
        // Prevent multiple simultaneous status changes
        if (isProcessingOnlineStatus) {
            showToast("Status change already in progress")
            return
        }

        stateMutex.withLock {
            isProcessingOnlineStatus = true
        }

        try {
            // Update UI immediately for better UX
            updateStatusUI(isOnline)

            // Vibrate for feedback
            vibrate()

            // Update status on backend
            updateOnlineStatus(isOnline)

        } catch (e: Exception) {
            Log.e("DashboardFragment", "Status change failed: ${e.message}")
            showToast("Failed to update status")
        } finally {
            stateMutex.withLock {
                isProcessingOnlineStatus = false
            }
        }
    }

    /**
     * Update status UI components
     */
    private suspend fun updateStatusUI(isOnline: Boolean) {
        withContext(Dispatchers.Main) {
            if (isOnline) {
                // Set online to active
                onlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.black))
                onlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))

                // Set offline to inactive
                offlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.grey))
                offlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))
            } else {
                // Set offline to active
                offlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.black))
                offlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))

                // Set online to inactive
                onlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.grey))
                onlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))
            }
        }
    }

    /**
     * Update online status on backend
     */
    private suspend fun updateOnlineStatus(isOnline: Boolean) {
        withContext(Dispatchers.IO) {
            val body = OnlineStatusBody(isOnline)

            Api.retrofitService.onlineStatus(authToken, body)
                .enqueue(object : Callback<okhttp3.ResponseBody> {
                    override fun onResponse(
                        call: Call<okhttp3.ResponseBody>,
                        response: Response<okhttp3.ResponseBody>
                    ) {
                        lifecycleScope.launch {
                            handleOnlineStatusResponse(response, isOnline)
                        }
                    }

                    override fun onFailure(call: Call<okhttp3.ResponseBody>, t: Throwable) {
                        lifecycleScope.launch {
                            Log.e("DashboardFragment", "Online status API failure: ${t.message}")
                            showToast("Network error: ${t.localizedMessage}")
                        }
                    }
                })
        }
    }

    /**
     * Handle online status API response
     */
    private suspend fun handleOnlineStatusResponse(
        response: Response<okhttp3.ResponseBody>,
        isOnline: Boolean
    ) {
        withContext(Dispatchers.Main) {
            if (response.isSuccessful) {
                val statusText = if (isOnline) "online" else "offline"
                showToast("Successfully set $statusText")
            } else {
                showToast("Failed to set status: ${response.message()}")
                // TODO: Revert UI changes if API call failed
            }
        }
    }

    /**
     * Trip update listener for handling incoming trips
     */
    private val tripUpdateListener = object : TripUpdateListener {
        override fun onTripReceived(trip: Trip, assigned: Boolean) {
            lifecycleScope.launch {
                handleTripReceived(trip, assigned)
            }
        }
    }

    /**
     * Handle incoming trip notification
     */
    private suspend fun handleTripReceived(trip: Trip, assigned: Boolean) {
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
                updateTripUI(trip, assigned)
            } else {
                pendingTrip = trip
            }
        }
    }

    /**
     * Start trip notification (sound and vibration)
     */
    private suspend fun startTripNotification() {
        stateMutex.withLock {
            if (isRingingActive) return
            isRingingActive = true
        }

        withContext(Dispatchers.Main) {
            // Start audio notification
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer.create(requireContext(), R.raw.quietly_brilliant)
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
    private suspend fun updateTripUI(trip: Trip, assigned: Boolean) {
        withContext(Dispatchers.Main) {
            if (!::map.isInitialized) return@withContext

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
    private suspend fun addCurrentLocationMarker() {
        locationJob = lifecycleScope.launch {
            try {
                val location = getCurrentLocation()
                location?.let { loc ->
                    withContext(Dispatchers.Main) {
                        val userLatLng = LatLng(loc.latitude, loc.longitude)
                        val markerOptions = MarkerOptions()
                            .position(userLatLng)
                            .icon(BitmapDescriptorFactory.fromResource(R.drawable.small_taxi_car_optimized))

                        map.addMarker(markerOptions)
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
            if (ActivityCompat.checkSelfPermission(
                    requireContext(),
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
        lifecycleScope.launch {
            stateMutex.withLock {
                map = googleMap
                isMapReady = true
            }

            // Process pending trip if available
            val pending = stateMutex.withLock { pendingTrip }
            val assigned = stateMutex.withLock { isAssigned }

            if (pending != null) {
                updateTripUI(pending, assigned)
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
            if (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                map.isMyLocationEnabled = true

                // Center map on user location
                lifecycleScope.launch {
                    centerMapOnUserLocation()
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
        val location = getCurrentLocation()
        location?.let { loc ->
            withContext(Dispatchers.Main) {
                val userLatLng = LatLng(loc.latitude, loc.longitude)
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(userLatLng, 16f)
                )
            }
        }
    }

    /**
     * Handle taximeter extended status updates
     */
    override fun onDisplayExtendedStatus(
        sender: Any?,
        response: DisplayExtendedStatusResponse?
    ) {
        lifecycleScope.launch {
            stateMutex.withLock {
                currentExtendedStatus = response?.extendedStatusData
            }

            updateTaximeterUI(response?.extendedStatusData)
        }
    }

    /**
     * Update taximeter UI components
     */
    private suspend fun updateTaximeterUI(extendedStatus: ExtendedStatus?) {
        withContext(Dispatchers.Main) {
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
     * Handle current fare response
     */
    override fun onCurrentFareResponse(sender: Any?, fare: CurrentFareResponse?) {
        lifecycleScope.launch {
            val trip = stateMutex.withLock { currentTrip }

            Log.d(
                "DashboardFragment",
                "Current Fare Response: ${fare?.currentFareAmount}, Trip ID: ${trip?.id}"
            )

            // TODO: Handle fare updates if needed
            // This could be used to update trip fare in real-time
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
     * Show snackbar message
     */
    private fun showSnackbar(message: String) {
        if (isAdded && view != null) {
            Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
        }
    }

    /**
     * Logger interface implementation
     */
    override fun Log(lea: LogEventArgs?) {
        lea?.let {
            val log = "[${it.Data}] ${it.Tag}"
            android.util.Log.d("TaximeterLog", log)
        }
    }

    /**
     * Cleanup resources and cancel running jobs
     */
    private fun cleanup() {
        // Cancel all running jobs
        initializationJob?.cancel()
        statusUpdateJob?.cancel()
        locationJob?.cancel()

        // Stop notifications
        lifecycleScope.launch {
            stopTripNotification()
        }

        // Unregister listeners
        taximeterManager?.OnCurrentFareResponseReceived?.unregisterListener(this)
        taximeterManager?.OnDisplayExtendedStatusReceived?.unregisterListener(this)

        // Clear references
        taximeterManager = null
        taxiModelAgent = null

        Log.d("DashboardFragment", "Cleanup completed")
    }
}
