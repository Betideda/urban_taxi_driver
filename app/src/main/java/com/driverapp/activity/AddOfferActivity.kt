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

/**
 * AddOfferActivity is responsible for displaying available taxi offers to the driver, allowing them
 * to accept an offer, and initiating a trip with the taximeter. It also handles communication with
 * the taximeter and the backend API.
 */
class AddOfferActivity :
        AppCompatActivity(),
        DisplayExtendedStatusListener, // Listener for extended taximeter status updates
        OffersClickListener, // Listener for clicks on offer items in the RecyclerView
        TripDetailsExtendedResponseListener { // Listener for extended trip details responses from
    // the taximeter

    // region Class Member Variables

    private lateinit var sharedPreferencesManager:
            SharedPreferencesManager // Manages shared preferences for storing/retrieving data
    private var taximeterManagerr: TaximeterManager? =
            null // Instance of TaximeterManager for communicating with the taximeter
    private var taxiModelAgentt: TaxiModelAgent? =
            null // Instance of TaxiModelAgent for specific taxi model operations
    private lateinit var recyclerView: RecyclerView // RecyclerView to display the list of offers
    private lateinit var backButton: ImageView // Button to navigate back
    var exStat: ExtendedStatus? = null // Holds the extended status data from the taximeter
    var shiftID: Int? = 0 // Stores the current shift ID
    var addressName: String = "" // Stores the human-readable address name for the current location
    var addressLat: Double = 0.0 // Stores the latitude of the current location
    var addressLng: Double = 0.0 // Stores the longitude of the current location
    var secTripId: Long? = null // Stores the sequential trip ID from the taximeter

    // endregion

    /**
     * Called when the activity is first created. Initializes UI components,
     * SharedPreferencesManager, and initiates taximeter initialization.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offers) // Set the layout for this activity

        sharedPreferencesManager =
                SharedPreferencesManager(this) // Initialize SharedPreferencesManager

        // Initialize Digitax Taximeter on an I/O dispatcher as it might involve blocking operations
        lifecycleScope.launch(Dispatchers.IO) { initializeDigitaxTaximeter() }

        backButton = findViewById(R.id.backButton) // Find the back button by its ID
        backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        } // Set click listener for back button

        recyclerView = findViewById(R.id.recyclerViewOffers) // Find the RecyclerView by its ID
        recyclerView.layoutManager =
                LinearLayoutManager(this) // Set a LinearLayoutManager for the RecyclerView
        recyclerView.setHasFixedSize(
                true
        ) // Optimize RecyclerView performance by setting fixed size

        taximeterManagerr
                ?.askDisplayExtendedStatus() // Request the current extended status from the
        // taximeter
        requestForOffers() // Request available offers from the backend
    }

    /** Fetches the list of offers from the backend API. Uses a Bearer token for authentication. */
    private fun requestForOffers() {
        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}" // Get the
        // authentication token
        // from
        // SharedPreferences
        Api.retrofitService
                .getOffers(token) // Make the API call to get offers
                .enqueue(
                        object : retrofit2.Callback<OffersData> {
                            /**
                             * Called when the API response is received. Updates the RecyclerView
                             * with the fetched offers or shows an error message.
                             */
                            override fun onResponse(
                                    call: retrofit2.Call<OffersData>,
                                    response: retrofit2.Response<OffersData>
                            ) {
                                if (response.isSuccessful) {
                                    val offersList =
                                            response.body()?.data
                                                    ?: emptyList() // Get the list of offers from
                                    // the response body
                                    val adapter =
                                            OffersAdapter(
                                                    offersList,
                                                    this@AddOfferActivity
                                            ) // Create an adapter for the RecyclerView
                                    recyclerView.adapter =
                                            adapter // Set the adapter to the RecyclerView
                                } else {
                                    // If the response is not successful, show an error toast on the
                                    // UI thread
                                    runOnUiThread {
                                        Toast.makeText(
                                                        this@AddOfferActivity,
                                                        "Something went wrong " +
                                                                response.message(),
                                                        Toast.LENGTH_SHORT
                                                )
                                                .show()
                                    }
                                }
                            }

                            /**
                             * Called when the API call fails (e.g., network error). Displays an
                             * error message to the user.
                             */
                            override fun onFailure(call: retrofit2.Call<OffersData>, t: Throwable) {
                                // Show an error toast on the UI thread in case of API call failure
                                runOnUiThread {
                                    Toast.makeText(
                                                    this@AddOfferActivity,
                                                    "Error: ${t.localizedMessage}",
                                                    Toast.LENGTH_SHORT
                                            )
                                            .show()
                                }
                            }
                        }
                )
    }

    /**
     * Initializes the Digitax Taximeter using the DigitaxTaximeterInitializer utility class.
     * Registers listeners for taximeter status and trip details.
     */
    private fun initializeDigitaxTaximeter() {
        DigitaxTaximeterInitializer(this, this)
                .initialize(
                        object : DigitaxTaximeterInitializer.Callback {
                            /**
                             * Called when the taximeter is successfully initialized. Saves the
                             * TaximeterManager and TaxiModelAgent instances and registers
                             * listeners.
                             */
                            override fun onInitialized(
                                    taximeterManager: TaximeterManager,
                                    taxiModelAgent: TaxiModelAgent
                            ) {
                                // Save the initialized taximeter manager and taxi model agent
                                taximeterManagerr = taximeterManager
                                taxiModelAgentt = taxiModelAgent
                                // Register this activity as a listener for extended status updates
                                taximeterManager.OnDisplayExtendedStatusReceived.registerListener(
                                        this@AddOfferActivity
                                )
                                // Register this activity as a listener for trip details extended
                                // responses
                                taximeterManager.OnTripDetailsExtendedResponseReceived
                                        .registerListener(this@AddOfferActivity)
                            }

                            /**
                             * Called when the taximeter connection status changes. This callback
                             * can be used to provide optional UI feedback to the user.
                             */
                            override fun onConnectionStatusChanged(connected: Boolean) {
                                // Optional UI feedback can be implemented here, e.g., update a
                                // connection status indicator
                            }
                        }
                )
    }

    /**
     * Callback method triggered when the "Start/End Trip" button is clicked for an offer. Initiates
     * a forfait trip if the taximeter status is "ForHire".
     *
     * @param zoneOffer The Zone object representing the accepted offer.
     * @param acceptButton The Button that was clicked (not used in this implementation).
     */
    override fun onStartEndButtonClick(zoneOffer: Zone, acceptButton: Button) {
        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}" // Get the
        // authentication token
        Log.e("StatusCode", exStat?.StatusCode.toString()) // Log the current taximeter status code

        // Check if the taximeter status is "ForHire" (ready to start a new trip)
        if (exStat?.StatusCode == TaximeterStatusCodes.ForHire) {
            val price =
                    zoneOffer.price.toDoubleOrNull()?.toInt()
                            ?: 0 // Parse the offer price to an integer
            taxiModelAgentt?.startForfaitTrip(
                    price.toString()
            ) // Start a forfait trip with the specified price

            // Delay for a short period (100ms) to allow the taximeter to update its status
            Handler(Looper.getMainLooper())
                    .postDelayed(
                            {
                                taximeterManagerr
                                        ?.askTripDetailsExtended() // Request extended trip details
                                taximeterManagerr
                                        ?.askDisplayExtendedStatus() // Request extended display
                                // status
                            },
                            100
                    )

            // Delay for a longer period (2500ms) before requesting to store the forfait trip
            Handler(Looper.getMainLooper())
                    .postDelayed({ requestStoreFroFaitTrip(token, zoneOffer) }, 2500)
        } else {
            // If the taximeter is not "ForHire", display a toast message
            runOnUiThread {
                Toast.makeText(
                                this@AddOfferActivity,
                                "Taximeter Status must be ForHire! ",
                                Toast.LENGTH_LONG
                        )
                        .show()
            }
            return // Exit the function as the trip cannot be started
        }
    }

    /**
     * Requests to store the forfait trip details to the backend API. This involves getting the
     * current location and constructing the request body.
     *
     * @param token The authentication token.
     * @param zoneOffer The Zone object representing the accepted offer.
     */
    private fun requestStoreFroFaitTrip(token: String, zoneOffer: Zone) {
        val fusedLocationClient =
                LocationServices.getFusedLocationProviderClient(
                        this@AddOfferActivity
                ) // Get the FusedLocationProviderClient

        // Check for location permissions
        if (ActivityCompat.checkSelfPermission(
                        this@AddOfferActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Request location permission if not granted
            ActivityCompat.requestPermissions(
                    this@AddOfferActivity,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    1001
            )
            return // Exit the function as permission is not granted
        }

        // Get the last known location
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                addressLat = location.latitude // Get latitude
                addressLng = location.longitude // Get longitude
                try {
                    val geocoder =
                            Geocoder(
                                    this@AddOfferActivity,
                                    Locale.getDefault()
                            ) // Initialize Geocoder
                    val addresses =
                            geocoder.getFromLocation(
                                    addressLat,
                                    addressLng,
                                    1
                            ) // Get address from coordinates
                    addressName =
                            if (!addresses.isNullOrEmpty()) {
                                addresses[0].getAddressLine(0) // Get the full address line
                            } else {
                                "Unknown Address" // Default if no address found
                            }
                } catch (e: IOException) {
                    addressName = "Geocoder Error" // Handle Geocoder errors
                    e.printStackTrace()
                }
            } else {
                // If location is null, show a toast
                Toast.makeText(this@AddOfferActivity, "Could not get location", Toast.LENGTH_SHORT)
                        .show()
            }

            Log.d("secTripId", secTripId?.toInt().toString()) // Log the sequential trip ID

            // Create the request body for storing the forfait trip
            val body =
                    StoreForFaitTripBody(
                            shiftID, // Shift ID
                            secTripId?.toInt(), // Sequential Trip ID
                            zoneOffer.id, // Offer ID
                            addressName, // Address name
                            addressLat, // Address latitude
                            addressLng // Address longitude
                    )

            // Make the API call to store the forfait trip
            Api.retrofitService
                    .requestStoreForFaitTrip("application/json", token, body)
                    .enqueue(
                            object : retrofit2.Callback<MyTripData> {
                                /**
                                 * Called when the API response for storing the trip is received.
                                 * Navigates to MainActivity on success or shows an error message.
                                 */
                                override fun onResponse(
                                        call: retrofit2.Call<MyTripData>,
                                        response: retrofit2.Response<MyTripData>
                                ) {
                                    if (response.isSuccessful) {
                                        val id =
                                                response.body()
                                                        ?.data
                                                        ?.id // Get the trip ID from the response
                                        val intentMainActivity =
                                                Intent(
                                                                this@AddOfferActivity,
                                                                MainActivity::class.java
                                                        )
                                                        .apply {
                                                            // Clear activity stack and start new
                                                            // task
                                                            flags =
                                                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                                                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                                                            putExtra(
                                                                    "trip_id",
                                                                    id
                                                            ) // Pass trip ID to MainActivity
                                                        }
                                        startActivity(intentMainActivity) // Start MainActivity

                                        runOnUiThread {
                                            Toast.makeText(
                                                            this@AddOfferActivity,
                                                            "Trip started successfully with amount: " +
                                                                    zoneOffer.price +
                                                                    " ALL",
                                                            Toast.LENGTH_SHORT
                                                    )
                                                    .show()
                                        }
                                    } else {
                                        // If storing the trip failed, show an error toast
                                        runOnUiThread {
                                            Toast.makeText(
                                                            this@AddOfferActivity,
                                                            "Failed to START trip: ${response.message()}",
                                                            Toast.LENGTH_SHORT
                                                    )
                                                    .show()
                                        }
                                    }
                                }

                                /**
                                 * Called when the API call to store the trip fails. Displays an
                                 * error message to the user.
                                 */
                                override fun onFailure(
                                        call: retrofit2.Call<MyTripData>,
                                        t: Throwable
                                ) {
                                    runOnUiThread {
                                        Toast.makeText(
                                                        this@AddOfferActivity,
                                                        "Error: ${t.localizedMessage}",
                                                        Toast.LENGTH_SHORT
                                                )
                                                .show()
                                    }
                                }
                            }
                    )
        }
    }

    /**
     * Callback method from DisplayExtendedStatusListener. Called when an extended status response
     * is received from the taximeter. Updates the exStat and shiftID variables.
     *
     * @param p0 The sender of the event (usually the TaximeterManager).
     * @param displayExtendedStatusResponse The response containing extended status data.
     */
    override fun onDisplayExtendedStatus(
            p0: Any?,
            displayExtendedStatusResponse: DisplayExtendedStatusResponse?
    ) {
        exStat = displayExtendedStatusResponse?.extendedStatusData // Update the extended status
        Log.e(
                "AddOfferAct",
                "CurrentFareAmount: ${exStat?.CurrentFareAmount}"
        ) // Log the current fare amount
        Log.e("AddOfferAct", "ShiftNumber: ${exStat?.ShiftNumber}") // Log the shift number
        shiftID = exStat?.ShiftNumber ?: 0 // Update the shift ID, defaulting to 0 if null
    }

    /**
     * Callback method from TripDetailsExtendedResponseListener. Called when an extended trip
     * details response is received from the taximeter. Updates secTripId and shiftID variables and
     * logs trip details.
     *
     * @param p0 The sender of the event.
     * @param responseTripDetails The response containing extended trip details.
     */
    override fun onTripDetailsExtendedResponse(
            p0: Any?,
            responseTripDetails: TripDetailsExtendedResponse?
    ) {
        secTripId =
                responseTripDetails
                        ?.extendedTripDetails
                        ?.TripSequentialNumber // Update the sequential trip ID
        shiftID =
                responseTripDetails?.extendedTripDetails?.ShiftSequentialNumber
                        ?.toInt() // Update the shift ID
        val forHireDistance =
                responseTripDetails?.extendedTripDetails?.DistanceHired // Get the distance hired

        // Log the retrieved trip details
        Log.d("erald", "Trip ID: $secTripId")
        Log.d("erald", "shiftID: $shiftID")
        Log.d("erald", "forHireDistance: $forHireDistance")
    }
}
