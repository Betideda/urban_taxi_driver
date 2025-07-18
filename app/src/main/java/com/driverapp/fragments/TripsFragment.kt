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
import java.io.IOException
import java.util.Locale
import androidx.core.net.toUri

/**
 * TripsFragment handles the display and management of assigned taxi trips.
 * This fragment manages taximeter communication, trip lifecycle, and navigation features.
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

    // State Management with Thread Safety
    private val stateMutex = Mutex()
    private var currentExtendedStatus: ExtendedStatus? = null
    private var currentShiftID: Int? = null
    private var currentTripId: Long? = null
    private var hiredDistance: Float = 0f
    private var isTaximeterInitialized = false
    private var isProcessingTrip = false

    // region Data
    private lateinit var tripList: List<Trip>
    private val addressInfo = LocationData("", 0.0, 0.0)

    // Coroutine Jobs
    private var tripsLoadJob: Job? = null
    private var tripProcessingJob: Job? = null

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
     * Initialize taximeter connection asynchronously
     */
    private fun initializeTaximeter() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                initializeDigitaxTaximeter()
            } catch (e: Exception) {
                Log.e("TripsFragment", "Failed to initialize taximeter: ${e.message}")
                withContext(Dispatchers.Main) {
                    showToast("Failed to initialize taximeter connection")
                }
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
     * Request taximeter status if available
     */
    private suspend fun requestTaxiMeterStatus() {
        withContext(Dispatchers.IO) {
            try {
                taximeterManagerr?.askDisplayExtendedStatus()
            } catch (e: Exception) {
                Log.e("TripsFragment", "Failed to request taximeter status: ${e.message}")
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
     * Initialize taximeter with proper callback handling
     */
    private suspend fun initializeDigitaxTaximeter() {
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
            taximeterManagerr = taximeterManager
            taxiModelAgentt = taxiModelAgent
            isTaximeterInitialized = true
        }

        // Register listeners
        taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@TripsFragment)
        taximeterManager.OnTripDetailsExtendedResponseReceived.registerListener(this@TripsFragment)

        Log.d("TripsFragment", "Taximeter initialized successfully")
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

        Log.d("TripsFragment", "Taximeter connection: $connected")
    }

    /**
     * Handle trip action buttons (accept/start/complete)
     */
    override fun onAcceptStartEndButtonClick(trip: Trip) {
        // Prevent multiple simultaneous trip processing
        if (isProcessingTrip) {
            showToast("Trip is already being processed")
            return
        }

        tripProcessingJob = lifecycleScope.launch {
            try {
                stateMutex.withLock { isProcessingTrip = true }

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
     * Handle trip start
     */
    private suspend fun handleStartTrip(trip: Trip) {
        if (!isTaximeterInitialized) {
            showToast("Taximeter not initialized")
            return
        }

        // Validate taximeter status
        val extendedStatus = stateMutex.withLock { currentExtendedStatus }
        if (extendedStatus?.StatusCode != TaximeterStatusCodes.ForHire) {
            showToast("Taximeter Status must be ForHire!")
            return
        }

        // Start forfait trip
        val success = startForfaitTrip(trip.total)
        if (!success) {
            showToast("Failed to start forfait trip")
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
     * Start forfait trip with error handling
     */
    private suspend fun startForfaitTrip(total: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                taxiModelAgentt?.startForfaitTrip(total)
                Log.d("TripsFragment", "Started forfait trip with total: $total")
                true
            } catch (e: Exception) {
                Log.e("TripsFragment", "Failed to start forfait trip: ${e.message}")
                false
            }
        }
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
     * Handle trip completion
     */
    private suspend fun handleCompleteTrip(trip: Trip) {
        if (!isTaximeterInitialized) {
            showToast("Taximeter not initialized")
            return
        }

        // Validate taximeter status
        val extendedStatus = stateMutex.withLock { currentExtendedStatus }
        if (extendedStatus?.StatusCode != TaximeterStatusCodes.Hired) {
            showToast("Taximeter Status must be Hired!")
            return
        }

        // Request taximeter updates
        requestTaxiMeterUpdates()

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
     * Request taximeter updates for completion
     */
    private suspend fun requestTaxiMeterUpdates() {
        withContext(Dispatchers.IO) {
            try {
                taximeterManagerr?.askDisplayExtendedStatus()
                taximeterManagerr?.askTripDetailsExtended()
            } catch (e: Exception) {
                Log.e("TripsFragment", "Failed to request taximeter updates: ${e.message}")
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
     * Get current location with permission handling
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
                val location = fusedLocationClient.lastLocation

                var success = false
                location.addOnSuccessListener { loc: Location? ->
                    lifecycleScope.launch {
                        success = processLocation(loc)
                    }
                }

                // Wait for location callback
                delay(1000)
                success
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
     * Handle taximeter extended status updates
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
            }

            Log.d(
                "TripsFragment",
                "Status updated - Distance: $hiredDistance, Shift: $currentShiftID"
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
            }

            Log.d("TripsFragment", "Trip details updated - TripID: $currentTripId")
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
     * Cleanup resources and cancel running jobs
     */
    private fun cleanup() {
        // Cancel all running jobs
        tripsLoadJob?.cancel()
        tripProcessingJob?.cancel()

        // Unregister listeners
        taximeterManagerr?.OnDisplayExtendedStatusReceived?.unregisterListener(this)
        taximeterManagerr?.OnTripDetailsExtendedResponseReceived?.unregisterListener(this)

        // Clear references
        taximeterManagerr = null
        taxiModelAgentt = null

        Log.d("TripsFragment", "Cleanup completed")
    }
}
