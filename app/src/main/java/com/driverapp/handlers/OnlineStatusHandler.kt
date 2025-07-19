package com.driverapp.handlers

import android.content.Context
import android.util.Log
import com.driverapp.networkApi.Api
import com.driverapp.networkApi.models.OnlineStatusBody
import com.driverapp.utils.SharedPreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Handles online/offline status management for taxi drivers.
 * Manages local storage and backend synchronization of driver availability status.
 */
class OnlineStatusHandler(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {

    private val sharedPreferencesManager = SharedPreferencesManager(context)
    private val statusKey = "driver_online_status"

    /**
     * Callback interface for status change results
     */
    interface StatusChangeCallback {
        fun onStatusChanged(isOnline: Boolean, success: Boolean, message: String? = null)
    }

    /**
     * Get current online status from local storage
     * @return true if online, false if offline (default)
     */
    fun isOnline(): Boolean {
        return sharedPreferencesManager.getBoolean(statusKey, false)
    }

    /**
     * Set online status with backend synchronization
     * @param isOnline desired online status
     * @param callback optional callback for result notification
     */
    fun setOnlineStatus(isOnline: Boolean, callback: StatusChangeCallback? = null) {
        coroutineScope.launch {
            try {
                // Update local storage immediately
                saveStatusLocally(isOnline)

                // Sync with backend
                syncWithBackend(isOnline, callback)

            } catch (e: Exception) {
                Log.e("OnlineStatusHandler", "Failed to set online status: ${e.message}")
                callback?.onStatusChanged(
                    isOnline,
                    false,
                    "Failed to update status: ${e.localizedMessage}"
                )
            }
        }
    }

    /**
     * Force offline status (used when shift ends)
     * @param callback optional callback for result notification
     */
    fun forceOffline(callback: StatusChangeCallback? = null) {
        setOnlineStatus(false, callback)
    }

    /**
     * Save status to local storage
     */
    private fun saveStatusLocally(isOnline: Boolean) {
        sharedPreferencesManager.saveBoolean(statusKey, isOnline)
        Log.d("OnlineStatusHandler", "Status saved locally: $isOnline")
    }

    /**
     * Synchronize status with backend API
     */
    private suspend fun syncWithBackend(isOnline: Boolean, callback: StatusChangeCallback?) {
        withContext(Dispatchers.IO) {
            val authToken = "Bearer ${sharedPreferencesManager.getString("token", "")}"
            val body = OnlineStatusBody(isOnline)

            Api.retrofitService.onlineStatus(authToken, body)
                .enqueue(object : Callback<ResponseBody> {
                    override fun onResponse(
                        call: Call<ResponseBody>,
                        response: Response<ResponseBody>
                    ) {
                        coroutineScope.launch {
                            handleBackendResponse(response, isOnline, callback)
                        }
                    }

                    override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                        coroutineScope.launch {
                            handleBackendFailure(t, isOnline, callback)
                        }
                    }
                })
        }
    }

    /**
     * Handle successful backend response
     */
    private suspend fun handleBackendResponse(
        response: Response<ResponseBody>,
        isOnline: Boolean,
        callback: StatusChangeCallback?
    ) {
        withContext(Dispatchers.Main) {
            if (response.isSuccessful) {
                val statusText = if (isOnline) "online" else "offline"
                val message = "Successfully set $statusText"
                Log.d("OnlineStatusHandler", message)
                callback?.onStatusChanged(isOnline, true, message)
            } else {
                val errorMessage = "Failed to set status: ${response.message()}"
                Log.e("OnlineStatusHandler", errorMessage)

                // Revert local changes on API failure
                saveStatusLocally(!isOnline)

                callback?.onStatusChanged(isOnline, false, errorMessage)
            }
        }
    }

    /**
     * Handle backend API failure
     */
    private suspend fun handleBackendFailure(
        t: Throwable,
        isOnline: Boolean,
        callback: StatusChangeCallback?
    ) {
        withContext(Dispatchers.Main) {
            val errorMessage = "Network error: ${t.localizedMessage}"
            Log.e("OnlineStatusHandler", "Backend API failure: ${t.message}")

            // Revert local changes on network failure
            saveStatusLocally(!isOnline)

            callback?.onStatusChanged(isOnline, false, errorMessage)
        }
    }

    /**
     * Clear stored status (useful for logout)
     */
    fun clearStatus() {
        sharedPreferencesManager.removeKey(statusKey)
        Log.d("OnlineStatusHandler", "Status cleared")
    }
}
