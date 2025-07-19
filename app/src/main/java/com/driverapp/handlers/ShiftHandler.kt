package com.driverapp.handlers

import android.content.Context
import android.util.Log
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.driverapp.networkApi.Api
import com.driverapp.networkApi.models.ShiftInfo
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.utils.TaxiModelAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Handles shift management for taxi drivers.
 * Manages local storage, taximeter operations, and backend synchronization of driver shift status.
 * Also manages dependency on OnlineStatusHandler (shift end forces offline status).
 */
class ShiftHandler(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onlineStatusHandler: OnlineStatusHandler
) {

    private val sharedPreferencesManager = SharedPreferencesManager(context)
    private val shiftStatusKey = "driver_shift_status"

    // Taximeter components - will be set when available
    private var taximeterManager: TaximeterManager? = null
    private var taxiModelAgent: TaxiModelAgent? = null

    /**
     * Callback interface for shift change results
     */
    interface ShiftChangeCallback {
        fun onShiftChanged(isActive: Boolean, success: Boolean, message: String? = null)
    }

    /**
     * Callback interface for shift info submission
     */
    interface ShiftInfoCallback {
        fun onShiftInfoSent(success: Boolean, message: String? = null)
    }

    /**
     * Set taximeter components when they become available
     */
    fun setTaximeterComponents(
        taximeterManager: TaximeterManager?,
        taxiModelAgent: TaxiModelAgent?
    ) {
        this.taximeterManager = taximeterManager
        this.taxiModelAgent = taxiModelAgent
        Log.d("ShiftHandler", "Taximeter components updated")
    }

    /**
     * Get current shift status from local storage
     * @return true if shift is active, false if inactive (default)
     */
    fun isShiftActive(): Boolean {
        return sharedPreferencesManager.getBoolean(shiftStatusKey, false)
    }

    /**
     * Start shift with taximeter operations and backend sync
     * @param callback optional callback for result notification
     */
    fun startShift(callback: ShiftChangeCallback? = null) {
        setShiftStatus(true, callback)
    }

    /**
     * End shift with taximeter operations, backend sync, and force offline
     * @param callback optional callback for result notification
     */
    fun endShift(callback: ShiftChangeCallback? = null) {
        setShiftStatus(false, callback)
    }

    /**
     * Set shift status with full handling
     * @param isActive desired shift status
     * @param callback optional callback for result notification
     */
    private fun setShiftStatus(isActive: Boolean, callback: ShiftChangeCallback? = null) {
        coroutineScope.launch {
            try {
                // Update local storage immediately
                saveShiftStatusLocally(isActive)

                // Handle taximeter operations
                if (isActive) {
                    handleShiftStart(callback)
                } else {
                    handleShiftEnd(callback)
                }

            } catch (e: Exception) {
                Log.e("ShiftHandler", "Failed to set shift status: ${e.message}")
                callback?.onShiftChanged(
                    isActive,
                    false,
                    "Failed to update shift: ${e.localizedMessage}"
                )
            }
        }
    }

    /**
     * Handle shift start operations
     */
    private suspend fun handleShiftStart(callback: ShiftChangeCallback?) {
        withContext(Dispatchers.Main) {
            if (taxiModelAgent != null) {
                val firstName = sharedPreferencesManager.getString("firstName", "")
                val id = sharedPreferencesManager.getString("id", "")

                taxiModelAgent?.openShift(id, firstName)

                val message = "Shift started successfully"
                Log.d("ShiftHandler", message)
                callback?.onShiftChanged(isActive = true, success = true, message = message)

                // Request shift details after a delay
                coroutineScope.launch {
                    kotlinx.coroutines.delay(2500)
                    requestShiftDetails()
                }
            } else {
                val errorMessage = "Taximeter not available"
                Log.e("ShiftHandler", errorMessage)

                // Revert local changes
                saveShiftStatusLocally(false)
                callback?.onShiftChanged(isActive = true, success = false, message = errorMessage)
            }
        }
    }

    /**
     * Handle shift end operations
     */
    private suspend fun handleShiftEnd(callback: ShiftChangeCallback?) {
        withContext(Dispatchers.Main) {
            // Force driver offline when shift ends (dependency rule)
            onlineStatusHandler.forceOffline(object : OnlineStatusHandler.StatusChangeCallback {
                override fun onStatusChanged(
                    isOnline: Boolean,
                    success: Boolean,
                    message: String?
                ) {
                    if (!success) {
                        Log.w(
                            "ShiftHandler",
                            "Failed to set offline status when ending shift: $message"
                        )
                    }
                }
            })

            if (taxiModelAgent != null) {
                taxiModelAgent?.closeShift()

                val message = "Shift ended successfully"
                Log.d("ShiftHandler", message)
                callback?.onShiftChanged(isActive = false, success = true, message = message)

                // Request shift details and last closed shift details after a delay
                coroutineScope.launch {
                    kotlinx.coroutines.delay(2500)
                    requestShiftDetails()
                    taxiModelAgent?.askLastClosedShiftDetails()
                }
            } else {
                val errorMessage = "Taximeter not available"
                Log.e("ShiftHandler", errorMessage)

                // Still consider this a success since we forced offline and updated local storage
                callback?.onShiftChanged(
                    isActive = false,
                    success = true,
                    message = "Shift ended (taximeter unavailable)"
                )
            }
        }
    }

    /**
     * Request current shift details from taximeter
     */
    fun requestShiftDetails() {
        taximeterManager?.askFullShiftDetails(true)
        Log.d("ShiftHandler", "Requested shift details")
    }

    /**
     * Send shift information to backend API
     * @param shiftInfo the shift information to send
     * @param callback optional callback for result notification
     */
    fun sendShiftInfo(shiftInfo: ShiftInfo, callback: ShiftInfoCallback? = null) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val authToken = "Bearer ${sharedPreferencesManager.getString("token", "")}"

                Api.retrofitService.sendShiftInfo(authToken, shiftInfo)
                    .enqueue(object : Callback<ResponseBody> {
                        override fun onResponse(
                            call: Call<ResponseBody>,
                            response: Response<ResponseBody>
                        ) {
                            coroutineScope.launch {
                                handleShiftInfoResponse(response, callback)
                            }
                        }

                        override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                            coroutineScope.launch {
                                handleShiftInfoFailure(t, callback)
                            }
                        }
                    })
            } catch (e: Exception) {
                Log.e("ShiftHandler", "Failed to send shift info: ${e.message}")
                coroutineScope.launch {
                    callback?.onShiftInfoSent(
                        false,
                        "Failed to send shift information: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Handle shift info API response
     */
    private suspend fun handleShiftInfoResponse(
        response: Response<ResponseBody>,
        callback: ShiftInfoCallback?
    ) {
        withContext(Dispatchers.Main) {
            if (response.isSuccessful) {
                val message = "Shift information sent successfully"
                Log.d("ShiftHandler", message)
                callback?.onShiftInfoSent(true, message)
            } else {
                val errorMessage = "Failed to send shift information: ${response.message()}"
                Log.e("ShiftHandler", errorMessage)
                callback?.onShiftInfoSent(false, errorMessage)
            }
        }
    }

    /**
     * Handle shift info API failure
     */
    private suspend fun handleShiftInfoFailure(t: Throwable, callback: ShiftInfoCallback?) {
        withContext(Dispatchers.Main) {
            val errorMessage = "Network error: ${t.localizedMessage}"
            Log.e("ShiftHandler", "Shift info API failure: ${t.message}")
            callback?.onShiftInfoSent(false, errorMessage)
        }
    }

    /**
     * Save shift status to local storage
     */
    private fun saveShiftStatusLocally(isActive: Boolean) {
        sharedPreferencesManager.saveBoolean(shiftStatusKey, isActive)
        Log.d("ShiftHandler", "Shift status saved locally: $isActive")
    }

    /**
     * Clear stored shift status (useful for logout)
     */
    fun clearShiftStatus() {
        sharedPreferencesManager.removeKey(shiftStatusKey)
        Log.d("ShiftHandler", "Shift status cleared")
    }

    /**
     * Get current shift information for display
     */
    fun getShiftDisplayInfo(): ShiftDisplayInfo {
        val isActive = isShiftActive()
        val statusText = if (isActive) "Active" else "Inactive"
        return ShiftDisplayInfo(isActive, statusText)
    }

    /**
     * Data class for shift display information
     */
    data class ShiftDisplayInfo(
        val isActive: Boolean,
        val statusText: String
    )
}
