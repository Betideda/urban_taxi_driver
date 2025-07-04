package com.driverapp.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
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
import com.driverapp.networkApi.models.TaximeterStatusBody
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
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response


class DashboardFragment : Fragment(), OnMapReadyCallback, ILoggerHandler, CurrentFareResponseListener, DisplayExtendedStatusListener {

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

    private lateinit var sharedPreferencesManager: SharedPreferencesManager
    private lateinit var vibrator: Vibrator
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var taximeterManagerr: TaximeterManager? = null
    private var taxiModelAgentt: TaxiModelAgent? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isVibrating = false
    var exStat: ExtendedStatus? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync { googleMap ->
            map = googleMap
        }
        lifecycleScope.launch(Dispatchers.IO) {
            initializeDigitaxTaximeter()
        }
        mapFragment.getMapAsync(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        sharedPreferencesManager = SharedPreferencesManager(requireContext())

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

        taximeterManagerr?.askDisplayExtendedStatus()
        offlineLayout.setOnClickListener {
            // Set offline to black
            vibrate()
            offlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.black))
            offlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))

            // Set online to grey
            onlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.grey))
            onlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))
            isOnline(0)
        }
        onlineLayout.setOnClickListener {
            // Set offline to grey
            vibrate()
            offlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.grey))
            offlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))

            // Set online to black
            onlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.black))
            onlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            isOnline(1)
        }
    }

    override fun onResume() {
        super.onResume()
        TripEventManager.addListener(tripUpdateListener)
    }

    override fun onPause() {
        super.onPause()
        TripEventManager.removeListener(tripUpdateListener)
    }

    private fun getLastRangTripId(): Int? =
        sharedPreferencesManager.getInt("lastRangTripId", -1).takeIf { it != -1 }

    private fun setLastRangTripId(id: Int) =
        sharedPreferencesManager.saveInt("lastRangTripId", id)

    private val tripUpdateListener = object : TripUpdateListener {
        override fun onTripReceived(trip: Trip, assigned: Boolean) {
            requireActivity().runOnUiThread {
                sharedPreferencesManager.putTrip("current_trip", trip)
                if (getLastRangTripId() != trip.id) {
                    setLastRangTripId(trip.id)
                    startRinging()
                } else {
                    stopRinging()
                }
                if (::map.isInitialized) {
                    updateUI(trip, assigned)
                } else {
                    pendingTrip = trip
                }
            }
        }
    }

    private fun startRinging() {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(requireContext(), R.raw.quietly_brilliant)
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        }
        if (isVibrating) return // already vibrating
        val pattern = longArrayOf(0, 500, 1000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }
        isVibrating = true
    }

    private fun stopRinging() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        vibrator.cancel()
        isVibrating = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRinging()
        if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
            mediaPlayer?.stop()
            mediaPlayer = null
        }
    }

    private fun updateUI(trip: Trip, assigned: Boolean) {
        map.clear()
        val pickupLatLng = LatLng(trip.requested_pickup_address_lat, trip.requested_pickup_address_lng)
        map.addMarker(
            MarkerOptions()
                .position(pickupLatLng)
                .title("Pickup Location")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )
    }

    override fun onDisplayExtendedStatus(p0: Any?, displayExtendedStatusResponse: DisplayExtendedStatusResponse?) {
        exStat = displayExtendedStatusResponse?.extendedStatusData
        statusCode.text = exStat?.StatusCode?.let {
            when (it) {
                TaximeterStatusCodes.ForHire -> "E LIRE"
                TaximeterStatusCodes.Hired -> "E ZENE"
                TaximeterStatusCodes.Stopped -> "ARKA"
                else -> "N/A"
            }
        } ?: "N/A"

        if (exStat?.StatusCode!= TaximeterStatusCodes.Hired&& exStat?.StatusCode!= TaximeterStatusCodes.Stopped) {
            currentFareAmount.text = "0 ALL"
        } else {
            currentFareAmount.text = (exStat?.CurrentFareAmount?:0).toString() + " ALL"
        }
        shiftNo.text ="TURNI: "+ (exStat?.ShiftNumber?:"N/A").toString()
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
                    taximeterManager.OnCurrentFareResponseReceived.registerListener(this@DashboardFragment)
                    taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@DashboardFragment)
                }

                override fun onConnectionStatusChanged(connected: Boolean) {
                    // Optional UI feedback
                }
            })
    }

    private fun isOnline(onlineStatus: Int) {
      /*  if (onlineStatus == 1) {
            val firstname = sharedPreferencesManager.getString("firstName", "")
            val id = sharedPreferencesManager.getString("id", "")
            taxiModelAgentt?.openShift(id, firstname)
            showSnackbar(getString(R.string.you_are_online))
        } else {
            showSnackbar(getString(R.string.you_are_offline))
            taxiModelAgentt?.closeShift()
            taxiModelAgentt?.printLastShiftReport()
        }*/

        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"
        val body = OnlineStatusBody(onlineStatus)

        Api.retrofitService.onlineStatus(token, body).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) {
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Successfully set", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Failed to set status: ${response.errorBody()?.string()}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Error setting status: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        })

    }

    private fun vibrate() {
        // Check if the device has a vibrator
        if (vibrator.hasVibrator()) {
            // Vibrate for 100 milliseconds
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(100)  // For older versions
            }
        }
    }

    private fun showSnackbar(message: String) {
        activity?.runOnUiThread {
            Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
        }
    }

    private var pendingTrip: Trip? = null
    private var assigned: Boolean = false
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        pendingTrip?.let {
            updateUI(it, assigned)
            pendingTrip = null
        }
        enableLocation()
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            map.isMyLocationEnabled = true
            getLastLocation()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLastLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let {
                val userLatLng = LatLng(it.latitude, it.longitude)

                // Add a custom image pin (marker) at the user's location
                val markerOptions = MarkerOptions()
                    .position(userLatLng)
                    .icon(BitmapDescriptorFactory.fromResource(R.drawable.small_taxi_car_optimized)) // Custom pin image

                map.addMarker(markerOptions)  // Add marker to map
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 16f)) // Zoom level 16
            }
        }
    }

    override fun Log(lea: LogEventArgs?) {
        lea?.let {
            val log = "[${it.Data}] ${it.Tag}"
            android.util.Log.d("TaximeterLog", log)
        }
    }

    override fun onCurrentFareResponse(p0: Any?, fare: CurrentFareResponse?) {
        val tripId = pendingTrip?.id
        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"
        //requestToStartTrip(tripId, token, fare)
        android.util.Log.d("onCurrentFareResponse", "Current Fare Response: ${fare?.currentFareAmount}")
    }

}
