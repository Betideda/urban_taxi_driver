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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

class AddOfferActivity : AppCompatActivity(),
    DisplayExtendedStatusListener,
    OffersClickListener,
    TripDetailsExtendedResponseListener {

    private lateinit var sharedPreferencesManager: SharedPreferencesManager
    private var taximeterManagerr: TaximeterManager? = null
    private var taxiModelAgentt: TaxiModelAgent? = null
    private lateinit var recyclerView: RecyclerView
    private lateinit var backButton: ImageView
    var exStat: ExtendedStatus? = null
    var shiftID: Int? = 0
    var addressName: String = ""
    var addressLat: Double = 0.0
    var addressLng: Double = 0.0
    var secTripId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offers)
        sharedPreferencesManager = SharedPreferencesManager(this)
        lifecycleScope.launch(Dispatchers.IO) {
            initializeDigitaxTaximeter()
        }
        backButton = findViewById(R.id.backButton)
        backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        recyclerView = findViewById(R.id.recyclerViewOffers)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.setHasFixedSize(true)
        taximeterManagerr?.askDisplayExtendedStatus()
        requestForOffers()
    }

    private fun requestForOffers() {
        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"
        Api.retrofitService.getOffers(token).enqueue(
            object : retrofit2.Callback<OffersData> {
                override fun onResponse(
                    call: retrofit2.Call<OffersData>,
                    response: retrofit2.Response<OffersData>
                ) {
                    if (response.isSuccessful) {
                        val offersList = response.body()?.data ?: emptyList()
                        val adapter = OffersAdapter(offersList, this@AddOfferActivity)
                        recyclerView.adapter = adapter
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@AddOfferActivity, "Something went wrong " + response.message(), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onFailure(call: retrofit2.Call<OffersData>, t: Throwable) {
                    runOnUiThread {
                        Toast.makeText(this@AddOfferActivity, "Error: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun initializeDigitaxTaximeter() {
        DigitaxTaximeterInitializer(this, this)
            .initialize(object : DigitaxTaximeterInitializer.Callback {
                override fun onInitialized(
                    taximeterManager: TaximeterManager,
                    taxiModelAgent: TaxiModelAgent
                ) {
                    // Save or use them as needed
                    taximeterManagerr = taximeterManager
                    taxiModelAgentt = taxiModelAgent
                    taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@AddOfferActivity)
                    taximeterManager.OnTripDetailsExtendedResponseReceived.registerListener(this@AddOfferActivity)

                }

                override fun onConnectionStatusChanged(connected: Boolean) {
                    // Optional UI feedback
                }
            })
    }

    override fun onStartEndButtonClick(zoneOffer: Zone, acceptButton: Button) {
        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"
       Log.e("StatusCode", exStat?.StatusCode.toString())
        if (exStat?.StatusCode == TaximeterStatusCodes.ForHire) {
            val price = zoneOffer.price.toDoubleOrNull()?.toInt() ?: 0
            taxiModelAgentt?.startForfaitTrip(price.toString())

            Handler(Looper.getMainLooper()).postDelayed({
                taximeterManagerr?.askTripDetailsExtended()
                taximeterManagerr?.askDisplayExtendedStatus()
            }, 100)

            Handler(Looper.getMainLooper()).postDelayed({
                requestStoreFroFaitTrip(token, zoneOffer)
            }, 2500)

        } else {
            runOnUiThread {
                Toast.makeText(this@AddOfferActivity, "Taximeter Status must be ForHire! ", Toast.LENGTH_LONG).show()
            }
            return
        }
    }

    private fun requestStoreFroFaitTrip(token: String, zoneOffer: Zone) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this@AddOfferActivity)
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
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                addressLat = location.latitude
                addressLng = location.longitude
                try {
                    val geocoder = Geocoder(this@AddOfferActivity, Locale.getDefault())
                    val addresses = geocoder.getFromLocation(addressLat, addressLng, 1)
                    addressName = if (!addresses.isNullOrEmpty()) {
                        addresses[0].getAddressLine(0)
                    } else {
                        "Unknown Address"
                    }
                } catch (e: IOException) {
                    addressName = "Geocoder Error"
                    e.printStackTrace()
                }
            } else {
                Toast.makeText(this@AddOfferActivity, "Could not get location", Toast.LENGTH_SHORT).show()
            }

            Log.d("secTripId",secTripId?.toInt().toString())
            val body = StoreForFaitTripBody(shiftID, secTripId?.toInt(), zoneOffer.id, addressName, addressLat, addressLng)
            Api.retrofitService.requestStoreForFaitTrip("application/json",token, body).enqueue(
                object : retrofit2.Callback<MyTripData> {
                    override fun onResponse(
                        call: retrofit2.Call<MyTripData>,
                        response: retrofit2.Response<MyTripData>
                    ) {
                        if (response.isSuccessful) {
                            val id = response.body()?.data?.id
                            val intentMainActivity = Intent(this@AddOfferActivity, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                putExtra("trip_id", id)  // pass trip id if needed
                            }
                            startActivity(intentMainActivity)

                            runOnUiThread {
                                Toast.makeText(this@AddOfferActivity, "Trip started successfully with amount: " + zoneOffer.price + " ALL", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this@AddOfferActivity, "Failed to START trip: ${response.message()}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    override fun onFailure(call: retrofit2.Call<MyTripData>, t: Throwable) {
                        runOnUiThread {
                            Toast.makeText(this@AddOfferActivity, "Error: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }

    override fun onDisplayExtendedStatus(p0: Any?, displayExtendedStatusResponse: DisplayExtendedStatusResponse?) {
        exStat = displayExtendedStatusResponse?.extendedStatusData
        Log.e("AddOfferAct", "CurrentFareAmount: ${exStat?.CurrentFareAmount}")
        Log.e("AddOfferAct", "ShiftNumber: ${exStat?.ShiftNumber}")
        shiftID = exStat?.ShiftNumber ?: 0
    }

    override fun onTripDetailsExtendedResponse(p0: Any?, responseTripDetails: TripDetailsExtendedResponse?) {
        secTripId = responseTripDetails?.extendedTripDetails?.TripSequentialNumber
        shiftID = responseTripDetails?.extendedTripDetails?.ShiftSequentialNumber?.toInt()
        val forHireDistance = responseTripDetails?.extendedTripDetails?.DistanceHired
        Log.d("erald", "Trip ID: $secTripId")
        Log.d("erald", "shiftID: $shiftID")
        Log.d("erald", "forHireDistance: $forHireDistance")
    }
}
