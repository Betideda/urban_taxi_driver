package com.driverapp.fragments

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
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
import java.io.IOException
import java.util.Locale

class TripsFragment : Fragment(), MyTripsButtonClickListener, DisplayExtendedStatusListener, TripDetailsExtendedResponseListener {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MyTripsAdapter
    private lateinit var addOffersBtn: ImageView
    private lateinit var tripList: List<Trip>
    private lateinit var sharedPreferencesManager: SharedPreferencesManager
    var exStat: ExtendedStatus? = null
    private var taximeterManagerr: TaximeterManager? = null
    private var taxiModelAgentt: TaxiModelAgent? = null
    lateinit var addressName: String
    var addressLat: Double = 0.0
    var addressLng: Double = 0.0
    var hiredDistance: Float = 0f
    var secTripId: Long? = null
    var shiftID: Int = 0
    private lateinit var emptyStateLayout: View
    private lateinit var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_trips, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedPreferencesManager = SharedPreferencesManager(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            initializeDigitaxTaximeter()
        }
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)

        swipeRefreshLayout.setOnRefreshListener {
            requestMyListTrips()
        }
        emptyStateLayout = view.findViewById(R.id.emptyStateLayout)
        recyclerView = view.findViewById(R.id.recyclerViewTrips)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.setHasFixedSize(true)

        addOffersBtn = view.findViewById(R.id.addOffersBtn)
        addOffersBtn.setOnClickListener {
            val intent = Intent(requireContext(), AddOfferActivity::class.java)
            startActivity(intent)
        }
        taximeterManagerr?.askDisplayExtendedStatus()
        requestMyListTrips()
    }

    private fun requestMyListTrips() {
        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"
        Api.retrofitService.myTrips(token).enqueue(
            object : retrofit2.Callback<MyTripListData> {
                override fun onResponse(
                    call: retrofit2.Call<MyTripListData>,
                    response: retrofit2.Response<MyTripListData>
                ) {
                    swipeRefreshLayout.isRefreshing = false
                    if (response.isSuccessful) {
                        tripList = response.body()?.data ?: emptyList()
                        if (tripList.isEmpty()) {
                            emptyStateLayout.visibility = View.VISIBLE
                            recyclerView.visibility = View.GONE
                        } else {
                            emptyStateLayout.visibility = View.GONE
                            recyclerView.visibility = View.VISIBLE
                            adapter = MyTripsAdapter(tripList, this@TripsFragment)
                            recyclerView.adapter = adapter
                        }
                    } else {
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Something went wrong " + response.message(), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onFailure(call: retrofit2.Call<MyTripListData>, t: Throwable) {
                    swipeRefreshLayout.isRefreshing = false
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Error: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    override fun onAcceptStartEndButtonClick(trip: Trip) {
        taximeterManagerr?.askDisplayExtendedStatus()
        if (trip.can_accept) {
            requestForAcceptTrip(trip)
        } else if (trip.can_start) {
            try {
                val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"
                if (exStat?.StatusCode == TaximeterStatusCodes.ForHire) {
                    taxiModelAgentt?.startForfaitTrip(trip.total)
                    requestToStartTrip(trip, trip.id, token, trip.total.toInt())
                } else {
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Taximeter Status must be ForHire! ", Toast.LENGTH_LONG).show()
                    }
                    return
                }

            } catch (e: Exception) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Trip start failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else if (trip.can_complete) {
            requestForCompleteTrip(trip)
        }
    }

    private fun requestForAcceptTrip(trip: Trip) {
        val tripId = trip.id
        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"
        Api.retrofitService.acceptTrip(token, tripId.toString()).enqueue(
            object : retrofit2.Callback<MyTripData> {
                override fun onResponse(
                    call: retrofit2.Call<MyTripData>,
                    response: retrofit2.Response<MyTripData>
                ) {
                    if (response.isSuccessful) {
                        activity?.runOnUiThread {
                            requestMyListTrips()
                            Toast.makeText(requireContext(), "Trip accepted successfully", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Failed to ACCEPT trip: ${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onFailure(call: retrofit2.Call<MyTripData>, t: Throwable) {
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Error: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun requestToStartTrip(trip: Trip, tripId: Int?, token: String, total: Int?) {
        taximeterManagerr?.askDisplayExtendedStatus()
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
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
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                addressLat = location.latitude
                addressLng = location.longitude
                try {
                    val geocoder = Geocoder(requireContext(), Locale.getDefault())
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
                Toast.makeText(requireContext(), "Could not get location", Toast.LENGTH_SHORT).show()
            }
            val tripAddressBody = PickupTripAddressBody(shiftID, secTripId?.toInt(), addressName, addressLat, addressLng)
            Api.retrofitService.startTrip(token, tripId.toString(), tripAddressBody).enqueue(
                object : retrofit2.Callback<MyTripData> {
                    override fun onResponse(
                        call: retrofit2.Call<MyTripData>,
                        response: retrofit2.Response<MyTripData>
                    ) {
                        if (response.isSuccessful) {
                            activity?.runOnUiThread {
                                requestMyListTrips()
                                Toast.makeText(requireContext(), "Trip started successfully with amount: " + total.toString() + " ALL", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            activity?.runOnUiThread {
                                Toast.makeText(requireContext(), "Failed to START trip: ${response.message()}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    override fun onFailure(call: retrofit2.Call<MyTripData>, t: Throwable) {
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Error: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }

    private fun requestForCompleteTrip(trip: Trip) {
        taximeterManagerr?.askDisplayExtendedStatus()
        taximeterManagerr?.askTripDetailsExtended()
        lifecycleScope.launch {
            val buttons = TaximeterButtons().apply { op = true }
            taximeterManagerr?.pressKeys(buttons)

            delay(1000L) // 1 second delay

            taximeterManagerr?.pressKeys(buttons)
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
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
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                addressLat = location.latitude
                addressLng = location.longitude
                try {
                    val geocoder = Geocoder(requireContext(), Locale.getDefault())
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
                Toast.makeText(requireContext(), "Could not get location", Toast.LENGTH_SHORT).show()
            }
            val tripId = trip.id
            val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"
            val distanceInMeter = hiredDistance * 1000
            val tripAddressBody = DropOffTripAddressBody(addressName, addressLat, addressLng, distanceInMeter)
            Api.retrofitService.completeTrip(token, tripId.toString(), tripAddressBody).enqueue(
                object : retrofit2.Callback<MyTripData> {
                    override fun onResponse(
                        call: retrofit2.Call<MyTripData>,
                        response: retrofit2.Response<MyTripData>
                    ) {
                        if (response.isSuccessful) {
                            activity?.runOnUiThread {
                                requestMyListTrips()
                                Toast.makeText(requireContext(), "Trip completed successfully", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            activity?.runOnUiThread {
                                Toast.makeText(requireContext(), "Failed to ACCEPT trip: ${response.code()}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    override fun onFailure(call: retrofit2.Call<MyTripData>, t: Throwable) {
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Error: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }

    private fun initializeDigitaxTaximeter() {
        DigitaxTaximeterInitializer(requireContext(), requireActivity())
            .initialize(object : DigitaxTaximeterInitializer.Callback {
                override fun onInitialized(
                    taximeterManager: TaximeterManager,
                    taxiModelAgent: TaxiModelAgent
                ) {
                    // Save or use them as needed
                    taximeterManagerr = taximeterManager
                    taxiModelAgentt = taxiModelAgent
                    taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@TripsFragment)
                    taximeterManager.OnTripDetailsExtendedResponseReceived.registerListener(this@TripsFragment)
                }

                override fun onConnectionStatusChanged(connected: Boolean) {
                    // Optional UI feedback
                }
            })
    }

    override fun onOpenInMapsClick(trip: Trip) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                val driverLat = location.latitude
                val driverLng = location.longitude
                val uri = Uri.parse(
                    "https://www.google.com/maps/dir/?api=1" +
                            "&origin=$driverLat,$driverLng" +
                            "&destination=${trip.requested_drop_off_address_lat},${trip.requested_drop_off_address_lng}" +
                            "&waypoints=${trip.requested_pickup_address_lat},${trip.requested_pickup_address_lng}" +
                            "&travelmode=driving"
                )
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.setPackage("com.google.android.apps.maps")
                try {
                    context?.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    context?.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            }
    }

    override fun onDisplayExtendedStatus(any: Any?, displayExtendedStatusResponse: DisplayExtendedStatusResponse?) {
        exStat = displayExtendedStatusResponse?.extendedStatusData
        Log.d("TripsFragment", "HiredDistance: ${exStat?.HiredDistance}")
        hiredDistance = exStat?.HiredDistance ?: 0f
        shiftID = exStat?.ShiftNumber ?: 0
    }

    override fun onTripDetailsExtendedResponse(p0: Any?, responseTripDetails: TripDetailsExtendedResponse?) {
        secTripId = responseTripDetails?.extendedTripDetails?.TripSequentialNumber
        Log.d("TripsFragment", "Trip ID: $secTripId")
    }
}
