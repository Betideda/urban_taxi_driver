package com.driverapp.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.digitax.android.libcomtax2.taximeter.events.DisplayExtendedStatusListener
import com.digitax.android.libcomtax2.taximeter.events.FullShiftDetailsResponseListener
import com.digitax.android.libcomtax2.taximeter.events.LastClosedShiftDetailsResponseListener
import com.digitax.android.libcomtax2.taximeter.messages.DisplayExtendedStatusResponse
import com.digitax.android.libcomtax2.taximeter.messages.FullShiftDetailsResponse
import com.digitax.android.libcomtax2.taximeter.messages.LastClosedShiftDetailsResponse
import com.digitax.android.libcomtax2.taximeter.objects.ExtendedStatus
import com.driverapp.R
import com.driverapp.networkApi.Api
import com.driverapp.networkApi.models.ShiftInfo
import com.driverapp.utils.DigitaxTaximeterInitializer
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.utils.TaxiModelAgent
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import okhttp3.ResponseBody

class ShiftsFragment : Fragment(),
    DisplayExtendedStatusListener,
    FullShiftDetailsResponseListener,
    LastClosedShiftDetailsResponseListener {

    // region Class Member Variables
    private lateinit var sharedPreferencesManager: SharedPreferencesManager

    // UI Components
    private lateinit var offlineLayout: LinearLayout
    private lateinit var onlineLayout: LinearLayout
    private lateinit var offlineIcon: ImageView
    private lateinit var onlineIcon: ImageView
    private lateinit var offlineText: TextView
    private lateinit var onlineText: TextView
    private lateinit var shiftNumberText: TextView
    private lateinit var tripCountText: TextView
    private lateinit var totalFareText: TextView

    // Taximeter Components
    private var extendedStatus: ExtendedStatus? = null
    private var taximeterManager: TaximeterManager? = null
    private var taxiModelAgent: TaxiModelAgent? = null

    // State Management
    private var isTaximeterInitialized = false
    private var isShiftOnline = false

    // Authentication
    private val authToken: String
        get() = "Bearer ${sharedPreferencesManager.getString("token", "")}"

    // endregion

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_shifts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeComponents()
        setupUI(view)
        initializeTaximeter()
    }

    /**
     * Initialize core components
     */
    private fun initializeComponents() {
        sharedPreferencesManager = SharedPreferencesManager(requireContext())
    }

    /**
     * Setup UI components and event listeners
     */
    private fun setupUI(view: View) {
        // Initialize UI elements
        offlineLayout = view.findViewById(R.id.offline_layout)
        onlineLayout = view.findViewById(R.id.online_layout)
        offlineIcon = view.findViewById(R.id.offline_icon)
        onlineIcon = view.findViewById(R.id.online_icon)
        offlineText = view.findViewById(R.id.offline_text)
        onlineText = view.findViewById(R.id.online_text)
        shiftNumberText = view.findViewById(R.id.shiftNo)
        tripCountText = view.findViewById(R.id.tripCount)
        totalFareText = view.findViewById(R.id.totalFare)

        // Set click listeners
        offlineLayout.setOnClickListener {
            handleOfflineClick()
        }

        onlineLayout.setOnClickListener {
            handleOnlineClick()
        }
    }

    /**
     * Initialize taximeter connection
     */
    private fun initializeTaximeter() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                initializeDigitaxTaximeter()
            } catch (e: Exception) {
                Log.e("ShiftsFragment", "Failed to initialize taximeter: ${e.message}")
                withContext(Dispatchers.Main) {
                    showSnackbar("Failed to initialize taximeter connection")
                }
            }
        }
    }

    /**
     * Handle offline button click
     */
    private fun handleOfflineClick() {
        updateUIState(isOnline = false)
        setShift(false)
    }

    /**
     * Handle online button click
     */
    private fun handleOnlineClick() {
        updateUIState(isOnline = true)
        setShift(true)
    }

    /**
     * Update UI state for online/offline status
     */
    private fun updateUIState(isOnline: Boolean) {
        if (isOnline) {
            // Set offline to grey
            offlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.grey))
            offlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))

            // Set online to black
            onlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.black))
            onlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
        } else {
            // Set offline to black
            offlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.black))
            offlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))

            // Set online to grey
            onlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.grey))
            onlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))
        }
    }

    /**
     * Set shift status and handle taximeter operations
     */
    private fun setShift(onlineStatus: Boolean) {
        if (!isTaximeterInitialized) {
            showSnackbar("Taximeter not initialized")
            return
        }

        isShiftOnline = onlineStatus

        if (onlineStatus) {
            handleShiftOpen()
        } else {
            handleShiftClose()
        }
    }

    /**
     * Handle shift opening
     */
    private fun handleShiftOpen() {
        val firstName = sharedPreferencesManager.getString("firstName", "")
        val id = sharedPreferencesManager.getString("id", "")

        taxiModelAgent?.openShift(id, firstName)
        showSnackbar(getString(R.string.you_are_online))

        // Request updated shift details after a delay
        Handler(Looper.getMainLooper()).postDelayed({
            requestShiftDetails()
        }, 2500)
    }

    /**
     * Handle shift closing
     */
    private fun handleShiftClose() {
        showSnackbar(getString(R.string.you_are_offline))
        taxiModelAgent?.closeShift()

        // Request updated shift details and last closed shift details after a delay
        Handler(Looper.getMainLooper()).postDelayed({
            requestShiftDetails()
            taxiModelAgent?.askLastClosedShiftDetails()
        }, 2500)
    }

    /**
     * Request current shift details
     */
    private fun requestShiftDetails() {
        taximeterManager?.askFullShiftDetails(true)
    }

    /**
     * Initialize taximeter with proper callback handling
     */
    private fun initializeDigitaxTaximeter() {
        DigitaxTaximeterInitializer(requireContext(), requireActivity())
            .initialize(object : DigitaxTaximeterInitializer.Callback {
                override fun onInitialized(
                    taximeterManager: TaximeterManager,
                    taxiModelAgent: TaxiModelAgent
                ) {
                    lifecycleScope.launch {
                        handleTaximeterInitialized(taximeterManager, taxiModelAgent)
                    }
                }

                override fun onConnectionStatusChanged(connected: Boolean) {
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
        withContext(Dispatchers.Main) {
            this@ShiftsFragment.taximeterManager = taximeterManager
            this@ShiftsFragment.taxiModelAgent = taxiModelAgent
            isTaximeterInitialized = true

            // Register listeners
            taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@ShiftsFragment)
            taximeterManager.OnFullShiftDetailsResponseReceived.registerListener(this@ShiftsFragment)
            taximeterManager.OnLastClosedShiftDetailsResponseReceived.registerListener(this@ShiftsFragment)

            // Request initial shift details
            requestShiftDetails()

            Log.d("ShiftsFragment", "Taximeter initialized successfully")
        }
    }

    /**
     * Handle taximeter connection status changes
     */
    private suspend fun handleConnectionStatusChange(connected: Boolean) {
        withContext(Dispatchers.Main) {
            if (!connected) {
                isTaximeterInitialized = false
                extendedStatus = null
            }
            Log.d("ShiftsFragment", "Taximeter connection: $connected")
        }
    }

    /**
     * Send shift information to backend API
     */
    private fun sendShiftInfo(shiftInfo: ShiftInfo) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Api.retrofitService.sendShiftInfo(authToken, shiftInfo)
                    .enqueue(object : Callback<ResponseBody> {
                        override fun onResponse(
                            call: Call<ResponseBody>,
                            response: Response<ResponseBody>
                        ) {
                            lifecycleScope.launch {
                                handleShiftInfoResponse(response)
                            }
                        }

                        override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                            lifecycleScope.launch {
                                handleShiftInfoFailure(t)
                            }
                        }
                    })
            } catch (e: Exception) {
                Log.e("ShiftsFragment", "Failed to send shift info: ${e.message}")
                lifecycleScope.launch {
                    showSnackbar("Failed to send shift information")
                }
            }
        }
    }

    /**
     * Handle shift info API response
     */
    private suspend fun handleShiftInfoResponse(response: Response<ResponseBody>) {
        withContext(Dispatchers.Main) {
            if (response.isSuccessful) {
                Log.d("ShiftsFragment", "Shift info sent successfully")
                showSnackbar("Shift information sent successfully")
            } else {
                Log.e("ShiftsFragment", "Failed to send shift info: ${response.message()}")
                showSnackbar("Failed to send shift information: ${response.message()}")
            }
        }
    }

    /**
     * Handle shift info API failure
     */
    private suspend fun handleShiftInfoFailure(t: Throwable) {
        withContext(Dispatchers.Main) {
            Log.e("ShiftsFragment", "Shift info API failure: ${t.message}")
            showSnackbar("Network error: ${t.localizedMessage}")
        }
    }

    /**
     * Show snackbar message
     */
    private fun showSnackbar(message: String) {
        activity?.runOnUiThread {
            Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
        }
    }

    // region Taximeter Event Listeners

    override fun onDisplayExtendedStatus(
        sender: Any?,
        displayExtendedStatusResponse: DisplayExtendedStatusResponse?
    ) {
        extendedStatus = displayExtendedStatusResponse?.extendedStatusData
        Log.d("ShiftsFragment", "Extended status received")
    }

    override fun onFullShiftDetailsResponse(sender: Any?, response: FullShiftDetailsResponse?) {
        response?.let { shiftResponse ->
            val shiftDetails = shiftResponse.fullShiftDetails

            // Calculate totals
            val totalTripsCount = (shiftDetails?.ShiftInfoEnd?.TripsQuantity?.toInt() ?: 0) -
                    (shiftDetails?.ShiftInfoStart?.TripsQuantity?.toInt() ?: 0)
            val totalFareAmount = (shiftDetails?.ShiftInfoEnd?.TotalAmount?.toDouble() ?: 0.0) -
                    (shiftDetails?.ShiftInfoStart?.TotalAmount?.toDouble() ?: 0.0)

            // Update UI
            activity?.runOnUiThread {
                shiftNumberText.text = "TURNI: ${shiftDetails?.ShiftConsecutiveNumber ?: "N/A"}"
                tripCountText.text = "UDHETIMET: $totalTripsCount"
                totalFareText.text = "TOTALI: $totalFareAmount"
            }

            Log.d(
                "ShiftsFragment",
                "Full shift details updated - Trips: $totalTripsCount, Fare: $totalFareAmount"
            )
        }
    }

    override fun onLastClosedShiftDetailsResponse(
        sender: Any?,
        response: LastClosedShiftDetailsResponse?
    ) {
        response?.let { shiftResponse ->
            val shiftDetails = shiftResponse.fullShiftDetails
            val startDetails = shiftDetails?.ShiftInfoStart
            val endDetails = shiftDetails?.ShiftInfoEnd

            // Create ShiftInfo object
            val shiftInfo = ShiftInfo(
                // General Information
                taximeterShiftId = (shiftDetails?.ShiftConsecutiveNumber ?: "N/A").toString(),

                // Start Info
                startInfoTripsQuantity = (startDetails?.TripsQuantity ?: "N/A").toString(),
                startInfoUnitsQuantity = (startDetails?.UnitsQuantity ?: "N/A").toString(),
                startInfoTotalDistance = (startDetails?.TotalDistance ?: "N/A").toString(),
                startInfoHiredDistance = (startDetails?.HiredDistance ?: "N/A").toString(),
                startInfoForHireDistance = (startDetails?.ForHireDistance ?: "N/A").toString(),
                startInfoBlackTripDistance = (startDetails?.BlackTripDistance ?: "N/A").toString(),
                startInfoWaitingTime = (startDetails?.WaitingTime ?: "N/A").toString(),
                startInfoFareAmount = (startDetails?.FareAmount ?: "N/A").toString(),
                startInfoExtrasAmount = (startDetails?.ExtrasAmount ?: "N/A").toString(),
                startInfoCreditCardAmount = (startDetails?.CreditCardAmount ?: "N/A").toString(),
                startInfoTaxAmount = (startDetails?.TaxAmount ?: "N/A").toString(),
                startInfoTipsAmount = (startDetails?.TipsAmount ?: "N/A").toString(),

                // End Info
                endInfoTripsQuantity = (endDetails?.TripsQuantity ?: "N/A").toString(),
                endInfoUnitsQuantity = (endDetails?.UnitsQuantity ?: "N/A").toString(),
                endInfoTotalDistance = (endDetails?.TotalDistance ?: "N/A").toString(),
                endInfoHiredDistance = (endDetails?.HiredDistance ?: "N/A").toString(),
                endInfoForHireDistance = (endDetails?.ForHireDistance ?: "N/A").toString(),
                endInfoBlackTripDistance = (endDetails?.BlackTripDistance ?: "N/A").toString(),
                endInfoWaitingTime = (endDetails?.WaitingTime ?: "N/A").toString(),
                endInfoFareAmount = (endDetails?.FareAmount ?: "N/A").toString(),
                endInfoExtrasAmount = (endDetails?.ExtrasAmount ?: "N/A").toString(),
                endInfoCreditCardAmount = (endDetails?.CreditCardAmount ?: "N/A").toString(),
                endInfoTaxAmount = (endDetails?.TaxAmount ?: "N/A").toString(),
                endInfoTipsAmount = (endDetails?.TipsAmount ?: "N/A").toString(),

                // Timestamps
                shiftStartedAt = (shiftDetails?.ShiftStartDate ?: "N/A").toString(),
                shiftEndedAt = (shiftDetails?.ShiftEndDate ?: "N/A").toString()
            )

            // Send shift info to backend
            sendShiftInfo(shiftInfo)

            Log.d("ShiftsFragment", "Last closed shift details processed and sent to backend")
        }
    }

    // endregion

    override fun onDestroy() {
        super.onDestroy()
        // Unregister listeners
        taximeterManager?.OnDisplayExtendedStatusReceived?.unregisterListener(this)
        taximeterManager?.OnFullShiftDetailsResponseReceived?.unregisterListener(this)
        taximeterManager?.OnLastClosedShiftDetailsResponseReceived?.unregisterListener(this)

        // Clear references
        taximeterManager = null
        taxiModelAgent = null

        Log.d("ShiftsFragment", "Fragment destroyed and cleaned up")
    }
}
