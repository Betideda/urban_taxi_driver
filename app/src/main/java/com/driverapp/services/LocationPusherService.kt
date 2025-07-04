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
import com.driverapp.activity.MainActivity
import com.driverapp.R
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.networkApi.Api
import com.driverapp.networkApi.models.GenericResponse
import com.driverapp.networkApi.models.SetLocationBody
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import retrofit2.Call
import retrofit2.Response

class LocationPusherService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var pusher: Pusher
    private lateinit var sharedPreferencesManager: SharedPreferencesManager

    override fun onCreate() {
        super.onCreate()
        sharedPreferencesManager = SharedPreferencesManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupPusher()
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
            .setContentIntent(pendingIntent) // <-- important part
            .setAutoCancel(true) // dismiss when tapped
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

           /* showPushNotification(
                "Location Sent",
                "Location sent and confirmed to server." + " ${event.data}"
            )*/
            Log.d("PUSHER", "Event received from server: ${event.data}")

            requestLocation() // <--- call it here
        }
        pusher.connect()
    }

    private fun requestLocation() {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = 1000
            numUpdates = 1
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("LocationPusherService", "Location permission not granted")
            return
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onLocationResult(result: LocationResult) {
                val location: Location? = result.lastLocation
                location?.let {
                    sendLocationToServer(it.latitude, it.longitude)
                }
            }
        }, Looper.getMainLooper())
    }
    private fun sendLocationToServer(lat: Double, lng: Double) {
        Log.d("LocationService", "LocationPusherService: $lat, $lng")
        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"

        val location = SetLocationBody(lat, lng)
        Api.retrofitService.setLocation(token, location).enqueue(object : retrofit2.Callback<GenericResponse> {
            override fun onResponse(call: Call<GenericResponse>, response: Response<GenericResponse>) {
                if (response.isSuccessful) {
                    Log.d("LocationPusherService", "Location sent successfully: $lat, $lng")
                } else {
                    Log.e("LocationPusherService", "Failed to send location: ${response.errorBody()?.string()}")
                }
            }

            override fun onFailure(call: Call<GenericResponse>, t: Throwable) {
                Log.e("LocationPusherService", "Error sending location: ${t.message}")
            }
        })
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        pusher.disconnect()
        super.onDestroy()
    }
}