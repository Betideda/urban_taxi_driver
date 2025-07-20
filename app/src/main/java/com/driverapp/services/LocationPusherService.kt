package com.driverapp.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.digitax.android.libcomtax2.taximeter.events.DisplayExtendedStatusListener
import com.digitax.android.libcomtax2.taximeter.messages.DisplayExtendedStatusResponse
import com.digitax.android.libcomtax2.taximeter.objects.ExtendedStatus
import com.driverapp.activity.MainActivity
import com.driverapp.R
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.utils.TaxiModelAgent
import com.driverapp.networkApi.Api
import com.driverapp.networkApi.models.GenericResponse
import com.driverapp.networkApi.models.SetLocationBody
import com.driverapp.utils.ServiceTaximeterInitializer
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import retrofit2.Call
import retrofit2.Response

class LocationPusherService : Service(),
    DisplayExtendedStatusListener {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var pusher: Pusher
    private lateinit var sharedPreferencesManager: SharedPreferencesManager

    // Taximeter components
    private var taximeterManager: TaximeterManager? = null
    private var taxiModelAgent: TaxiModelAgent? = null
    private var isTaximeterInitialized = false

    private var currentExtendedStatus: ExtendedStatus? = null
    private val stateMutex = Mutex()

    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    override fun onCreate() {
        super.onCreate()
        sharedPreferencesManager = SharedPreferencesManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize taximeter first, then setup pusher
        initializeTaximeter()
        setupPusher()
    }

    /**
     * Initialize taximeter connection
     */
    private fun initializeTaximeter() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                ServiceTaximeterInitializer(this@LocationPusherService)
                    .initialize(object : ServiceTaximeterInitializer.Callback {
                        override fun onInitialized(
                            taximeterManager: TaximeterManager,
                            taxiModelAgent: TaxiModelAgent
                        ) {
                            serviceScope.launch {
                                handleTaximeterInitialized(taximeterManager, taxiModelAgent)
                            }
                        }

                        override fun onConnectionStatusChanged(connected: Boolean) {
                            serviceScope.launch {
                                handleConnectionStatusChange(connected)
                            }
                        }
                    })
            } catch (e: Exception) {
                Log.e("LocationPusherService", "Failed to initialize taximeter: ${e.message}")
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
            this.taximeterManager = taximeterManager
            this.taxiModelAgent = taxiModelAgent
            isTaximeterInitialized = true
        }

        // Register this service as a listener for extended status updates
        taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@LocationPusherService)

        // Request initial status
        taximeterManager.askDisplayExtendedStatus()

        Log.d("LocationPusherService", "Taximeter initialized and listener registered")
    }

    /**
     * Handle taximeter connection status changes
     */
    private suspend fun handleConnectionStatusChange(connected: Boolean) {
        stateMutex.withLock {
            if (!connected) {
                isTaximeterInitialized = false
                // Don't clear currentExtendedStatus here - keep last known status
            }
        }
        Log.d("LocationPusherService", "Taximeter connection: $connected")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //startForeground(1, createNotification())
        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotification(): Notification {
        val channelId = "location_channel"
        val channel = NotificationChannel(
            channelId,
            "Location Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Location Tracking")
            .setContentText("Waiting for location request...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
    }

    /**
     * Handle taximeter extended status updates
     */
    override fun onDisplayExtendedStatus(
        sender: Any?,
        response: DisplayExtendedStatusResponse?
    ) {
        serviceScope.launch {
            try {
                val previousStatus = stateMutex.withLock { currentExtendedStatus }

                stateMutex.withLock {
                    currentExtendedStatus = response?.extendedStatusData
                }

                val newStatus = response?.extendedStatusData

                Log.d(
                    "LocationPusherService",
                    "Status updated - StatusCode: ${newStatus?.StatusCode}, Fare: ${newStatus?.CurrentFareAmount}, Shift: ${newStatus?.ShiftNumber}"
                )

                // Check if status actually changed (like in AddOfferActivity)
                if (previousStatus?.StatusCode != newStatus?.StatusCode) {
                    Log.d(
                        "LocationPusherService",
                        "Status changed from ${previousStatus?.StatusCode} to ${newStatus?.StatusCode}"
                    )
                }

            } catch (e: Exception) {
                Log.e("LocationPusherService", "Error updating extended status: ${e.message}")
            }
        }
    }

    private fun showPushNotification(title: String, message: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "push_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Push Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        // Create an Intent to launch your desired activity
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("from_notification", true) // optional: flag for tracking
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.urban_logo_no_bg)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2, notification)
    }

    private fun setupPusher() {
        val options = PusherOptions().apply {
            setCluster("eu")
        }

        pusher = Pusher("75cec9c29d7fbbebad0e", options)
        val channel = pusher.subscribe("dispatch-fleet-request")

        channel.bind("location-request-received") { event ->
            Log.d("PUSHER", "Event received from server: ${event.data}")
            requestLocation()
        }
        pusher.connect()
    }

    private fun requestLocation() {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = 1000
            numUpdates = 1
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("LocationPusherService", "Location permission not granted")
            return
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onLocationResult(result: LocationResult) {
                val location: Location? = result.lastLocation
                location?.let {
                    serviceScope.launch {
                        sendLocationToServer(it.latitude, it.longitude)
                    }
                }
            }
        }, Looper.getMainLooper())
    }

    private suspend fun sendLocationToServer(lat: Double, lng: Double) {
        Log.d("LocationService", "LocationPusherService: $lat, $lng")
        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"

        val extendedStatus = stateMutex.withLock { currentExtendedStatus }

        val extendedStatusString = if (extendedStatus != null) {
            extendedStatus.StatusCode?.toString()
        } else {
            ""
        }

        val location = SetLocationBody(lat, lng, extendedStatusString)

        Log.d(
            "LocationPusherService",
            "Sending location with status: lat=$lat, lng=$lng, status='$extendedStatusString'"
        )

        Api.retrofitService.setLocation(token, location)
            .enqueue(object : retrofit2.Callback<GenericResponse> {
                override fun onResponse(
                    call: Call<GenericResponse>,
                    response: Response<GenericResponse>
                ) {
                    if (response.isSuccessful) {
                        Log.d(
                            "LocationPusherService",
                            "Location sent successfully: $lat, $lng with status: $extendedStatusString"
                        )
                    } else {
                        Log.e(
                            "LocationPusherService",
                            "Failed to send location: ${response.errorBody()?.string()}"
                        )
                    }
                }

                override fun onFailure(call: Call<GenericResponse>, t: Throwable) {
                    Log.e("LocationPusherService", "Error sending location: ${t.message}")
                }
            })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Unregister listener before destroying
        taximeterManager?.OnDisplayExtendedStatusReceived?.unregisterListener(this)

        pusher.disconnect()
        super.onDestroy()
    }
}
