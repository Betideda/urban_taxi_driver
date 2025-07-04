package com.driverapp.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContentProviderCompat.requireContext
import com.digitax.android.libutility.concurrent.ThreadUtility.runOnUiThread
import com.driverapp.R
import com.driverapp.activity.MainActivity
import com.driverapp.models.TripEventManager
import com.driverapp.networkApi.Api
import com.driverapp.networkApi.models.MyTripData
import com.driverapp.networkApi.models.Trip
import com.driverapp.utils.SharedPreferencesManager
import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import okhttp3.ResponseBody
import org.json.JSONObject

class TripPusherService : Service() {
    private lateinit var pusher: Pusher
    private lateinit var sharedPreferencesManager: SharedPreferencesManager

    override fun onCreate() {
        super.onCreate()
        sharedPreferencesManager = SharedPreferencesManager(this)
        val driverId = sharedPreferencesManager.getString("id", "")

        if (!driverId.isNullOrEmpty()) {
            setupPusher(driverId)
        } else {
            stopSelf() // Don't continue if driverId is invalid
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun setupPusher(driverId: String) {
        val options = PusherOptions().apply { setCluster("eu") }
        pusher = Pusher("75cec9c29d7fbbebad0e", options)

        val assignedChannel = pusher.subscribe("new-trip-assigned-$driverId")
        assignedChannel.bind("new-trip-assigned") { event ->
            Log.d("PUSHER", "new-trip-assigned: ${event.data}")
            val json = JSONObject(event.data)
            val tripId = json.getInt("trip_id")
            val broadcastEventId = json.getInt("broadcast_event_id")
            val assigned = true
            markAsReceived(broadcastEventId)
            fetchTripDetails(tripId,assigned)
        }

        val availableChannel = pusher.subscribe("new-trip-available-$driverId")
        availableChannel.bind("new-trip-available") { event ->
            val json = JSONObject(event.data)
            val tripId = json.getInt("trip_id")
            val broadcastEventId = json.getInt("broadcast_event_id")
            val assigned = false
            markAsReceived(broadcastEventId)
            fetchTripDetails(tripId,assigned)
        }
        pusher.connect()
    }

    private fun markAsReceived(broadcastEventId: Int) {
        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"
        Api.retrofitService.markAsReceived(token, broadcastEventId.toString()).enqueue(
            object : retrofit2.Callback<ResponseBody> {
                override fun onResponse(
                    call: retrofit2.Call<ResponseBody>,
                    response: retrofit2.Response<ResponseBody>
                ) {
                }

                override fun onFailure(call: retrofit2.Call<ResponseBody>, t: Throwable) {
                }
            }
        )
    }

    private fun fetchTripDetails(tripId: Int, assigned: Boolean) {
        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"
        Api.retrofitService.getShowTripDetails(token, tripId).enqueue(
            object : retrofit2.Callback<MyTripData> {
                override fun onResponse(
                    call: retrofit2.Call<MyTripData>,
                    response: retrofit2.Response<MyTripData>
                ) {
                    if (response.isSuccessful) {
                        val trip = response.body()?.data
                        Log.d("API", "Trip: $trip")

                        trip?.let {
                            TripEventManager.notifyTripReceived(trip,assigned)
                            showNewTripNotification(trip)
                        }
                    } else {
                        Log.e("API", "Failed to get trip: ${response.code()}")
                    }
                }

                override fun onFailure(call: retrofit2.Call<MyTripData>, t: Throwable) {
                    Log.e("API", "Error: ${t.localizedMessage}")
                }
            }
        )
    }

    private fun showNewTripNotification(trip: Trip) {
        val channelId = "trip_notifications_channel"
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Trip Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new trips"
                enableLights(true)
                lightColor = Color.BLUE
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Create intent to open TripDetailsActivity (or your desired activity)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("trip_id", trip.id)  // pass trip id if needed
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            trip.id,  // unique request code
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.urban_logo_no_bg)
            .setContentTitle("NEW TRIP ASSIGNED")
            .setContentText(trip.requested_pickup_address ?: "Details available")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)  // <-- this makes notification clickable
            .build()

        notificationManager.notify(trip.id, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::pusher.isInitialized) {
            pusher.disconnect()
        }
        super.onDestroy()
    }
}