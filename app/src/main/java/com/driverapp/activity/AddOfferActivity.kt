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

/**
 * AddOfferActivity displays available taxi offers to drivers and handles trip initiation.
 * This activity manages taximeter communication, offer selection, and trip creation workflow.
 */
class AddOfferActivity :
    AppCompatActivity(),
    DisplayExtendedStatusListener,
    OffersClickListener,
    TripDetailsExtendedResponseListener {

    // region Class Member Variables
    private lateinit var sharedPreferencesManager: SharedPreferencesManager
    private var taximeterManagerr: TaximeterManager? = null
    private var taxiModelAgentt: TaxiModelAgent? = null
    private lateinit var recyclerView: RecyclerView
    private lateinit var backButton: ImageView

    // State management with thread safety
    private val stateMutex = Mutex()
    private var currentExtendedStatus: ExtendedStatus? = null
    private var currentShiftID: Int? = null
    private var currentTripId: Long? = null
    private var isProcessingTrip = false
    private var isTaximeterInitialized = false

    // Location data
    private val addressInfo = LocationData("", 0.0, 0.0)

    // Coroutine jobs for cleanup
    private var offersJob: Job? = null
    private var tripProcessingJob: Job? = null

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
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    /**
     * Initialize core components and shared preferences
     */
    private fun initializeComponents() {
        sharedPreferencesManager = SharedPreferencesManager(this)
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
     * Initialize taximeter connection asynchronously
     */
    private fun initializeTaximeter() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                initializeDigitaxTaximeter()
            } catch (e: Exception) {
                Log.e("AddOfferActivity", "Failed to initialize taximeter: ${e.message}")
                withContext(Dispatchers.Main) {
                    showToast("Failed to initialize taximeter connection")
                }
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
                        call: retrofit2.Call<OffersData>,
                        response: retrofit2.Response<OffersData>
                    ) {
                        lifecycleScope.launch {
                            handleOffersResponse(response)
                        }
                    }

                    override fun onFailure(call: retrofit2.Call<OffersData>, t: Throwable) {
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
//    private fun initializeDigitaxTaximeter() {
//        DigitaxTaximeterInitializer(this, this)
//            .initialize(object : DigitaxTaximeterInitializer.Callback {
//                override fun onInitialized(
//                    taximeterManager: TaximeterManager,
//                    taxiModelAgent: TaxiModelAgent
//                ) {
//                    // Save or use them as needed
//                    taximeterManagerr = taximeterManager
//                    taxiModelAgentt = taxiModelAgent
//                    taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@AddOfferActivity)
//                    taximeterManager.OnTripDetailsExtendedResponseReceived.registerListener(this@AddOfferActivity)
//
//                }
//
//                override fun onConnectionStatusChanged(connected: Boolean) {
//                    // Optional UI feedback
//                }
//            })
//    }
    /**
     * Initialize taximeter with proper callback handling
     */
    private fun initializeDigitaxTaximeter() {
        Log.d("AddOfferActivity", "Case 1")
        DigitaxTaximeterInitializer(this, this)
                .initialize(object : DigitaxTaximeterInitializer.Callback {
                    override fun onInitialized(
                        taximeterManager: TaximeterManager,
                        taxiModelAgent: TaxiModelAgent
                    ) {
                        Log.d("AddOfferActivity", "Case 2")
                        lifecycleScope.launch {
                            handleTaximeterInitialized(taximeterManager, taxiModelAgent)
                        }
                    }

                    override fun onConnectionStatusChanged(connected: Boolean) {
                        Log.d("AddOfferActivity", "Case 3")
                        lifecycleScope.launch {
                            handleConnectionStatusChange(connected)
                        }
                    }
                })
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
        taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@AddOfferActivity)
        taximeterManager.OnTripDetailsExtendedResponseReceived.registerListener(this@AddOfferActivity)

        // Request initial status
        taximeterManager.askDisplayExtendedStatus()

        Log.d("AddOfferActivity", "Taximeter initialized successfully")
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

        Log.d("AddOfferActivity", "Taximeter connection: $connected")
    }

    /**
     * Handle offer acceptance and trip initiation
     */
    override fun onStartEndButtonClick(zoneOffer: Zone, acceptButton: Button) {
        // Prevent multiple simultaneous trip processing
        if (isProcessingTrip) {
            showToast("Trip is already being processed")
            return
        }

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

    /**
     * Process trip creation with proper async handling
     */
    private suspend fun processTrip(zoneOffer: Zone) {

        stateMutex.withLock {
            isProcessingTrip = true
        }

        requestTaximeterUpdates()

        // Wait for taximeter to update and get trip details
        delay(500)

        // Validate taximeter status
        val extendedStatus = stateMutex.withLock { currentExtendedStatus }
        if (extendedStatus?.StatusCode != TaximeterStatusCodes.ForHire) {
            showToast("Taximeter must be in ForHire status to start trip")
            Log.d("Extended Status", extendedStatus?.StatusCode.toString())
            return
        }

        if (!isTaximeterInitialized) {
            showToast("Taximeter not initialized")
            return
        }

        // Start forfait trip
        val success = startForfaitTrip(zoneOffer)
        if (!success) {
            showToast("Failed to start forfait trip")
            return
        }



        // Wait for trip details to be available
        delay(2500)

        // Store trip in backend
        storeTrip(zoneOffer)
    }

    /**
     * Start forfait trip with error handling
     */
    private suspend fun startForfaitTrip(zoneOffer: Zone): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val price = zoneOffer.price.toDoubleOrNull()?.toInt() ?: 0
                taxiModelAgentt?.startForfaitTrip(price.toString())
                Log.d("AddOfferActivity", "Started forfait trip with price: $price")
                true
            } catch (e: Exception) {
                Log.e("AddOfferActivity", "Failed to start forfait trip: ${e.message}")
                false
            }
        }
    }

    /**
     * Request taximeter status and trip details updates
     */
    private suspend fun requestTaximeterUpdates() {
        withContext(Dispatchers.IO) {
            try {
                taximeterManagerr?.askTripDetailsExtended()
                taximeterManagerr?.askDisplayExtendedStatus()
                Log.d("Request Updates", "Updates Requested")
            } catch (e: Exception) {
                Log.e("Request Updates", "Failed to request taximeter updates: ${e.message}")
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
     * Get current location with permission handling
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
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this@AddOfferActivity)
                val location = fusedLocationClient.lastLocation

                var success = false
                location.addOnSuccessListener { loc: Location? ->
                    lifecycleScope.launch {
                        success = processLocation(loc)
                    }
                }

                // Wait a bit for location callback
                delay(1000)
                success
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
                        call: retrofit2.Call<MyTripData>,
                        response: retrofit2.Response<MyTripData>
                    ) {
                        lifecycleScope.launch {
                            handleStoreTripResponse(response, zoneOffer)
                        }
                    }

                    override fun onFailure(call: retrofit2.Call<MyTripData>, t: Throwable) {
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
            }

            Log.d("Display Extended Status Response", "Status updated - Fare: ${response?.extendedStatusData?.CurrentFareAmount}, Shift: ${response?.extendedStatusData?.ShiftNumber}")
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

            Log.d("AddOfferActivity", "Trip details updated - TripID: $currentTripId, ShiftID: $currentShiftID")
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
     * Cleanup resources and cancel running jobs
     */
    private fun cleanup() {
        // Cancel all running jobs
        offersJob?.cancel()
        tripProcessingJob?.cancel()

        // Unregister listeners
        taximeterManagerr?.OnDisplayExtendedStatusReceived?.unregisterListener(this)
        taximeterManagerr?.OnTripDetailsExtendedResponseReceived?.unregisterListener(this)

        // Clear references
        taximeterManagerr = null
        taxiModelAgentt = null

        Log.d("AddOfferActivity", "Cleanup completed")
    }
}
