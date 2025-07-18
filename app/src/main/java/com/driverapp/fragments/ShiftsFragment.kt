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
import com.driverapp.networkApi.models.ShiftInfo
import com.driverapp.utils.DigitaxTaximeterInitializer
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.utils.TaxiModelAgent
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShiftsFragment : Fragment(),
    DisplayExtendedStatusListener,
    FullShiftDetailsResponseListener,
    LastClosedShiftDetailsResponseListener {

    private lateinit var sharedPreferencesManager: SharedPreferencesManager
    private lateinit var offlineLayout: LinearLayout
    private lateinit var onlineLayout: LinearLayout
    private lateinit var offlineIcon: ImageView
    private lateinit var onlineIcon: ImageView
    private lateinit var offlineText: TextView
    private lateinit var onlineText: TextView
    private lateinit var shiftNo: TextView
    private lateinit var tripCount: TextView
    private lateinit var totalFare: TextView
    private var exStat: ExtendedStatus? = null
    private var taximeterManagerr: TaximeterManager? = null
    private var taxiModelAgentt: TaxiModelAgent? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_shifts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedPreferencesManager = SharedPreferencesManager(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            initializeDigitaxTaximeter()
            taximeterManagerr?.askFullShiftDetails(true)
        }

        offlineLayout = view.findViewById(R.id.offline_layout)
        onlineLayout = view.findViewById(R.id.online_layout)
        offlineIcon = view.findViewById(R.id.offline_icon)
        onlineIcon = view.findViewById(R.id.online_icon)
        offlineText = view.findViewById(R.id.offline_text)
        onlineText = view.findViewById(R.id.online_text)
        shiftNo = view.findViewById(R.id.shiftNo)
        tripCount = view.findViewById(R.id.tripCount)
        totalFare = view.findViewById(R.id.totalFare)

        offlineLayout.setOnClickListener {
            // Set offline to black
            offlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.black))
            offlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))

            // Set online to grey
            onlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.grey))
            onlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))
            setShift(false)
        }
        onlineLayout.setOnClickListener {
            // Set offline to grey
            offlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.grey))
            offlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))

            // Set online to black
            onlineIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.black))
            onlineText.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            setShift(true)
        }
    }

    private fun setShift(onlineStatus: Boolean) {
        if (onlineStatus) {
            val firstname = sharedPreferencesManager.getString("firstName", "")
            val id = sharedPreferencesManager.getString("id", "")
            taxiModelAgentt?.openShift(id, firstname)
            showSnackbar(getString(R.string.you_are_online))

            Handler(Looper.getMainLooper()).postDelayed({
                taxiModelAgentt?.askFullShiftDetails(true)
            }, 2500)
        } else {
            showSnackbar(getString(R.string.you_are_offline))
            taxiModelAgentt?.closeShift()
            //Do not need to print, since it is automatically printed
            //taxiModelAgentt?.printLastShiftReport()

            Handler(Looper.getMainLooper()).postDelayed({
                taxiModelAgentt?.askFullShiftDetails(true)
                taxiModelAgentt?.askLastClosedShiftDetails()
            }, 2500)
        }
    }

    private fun showSnackbar(message: String) {
        activity?.runOnUiThread {
            Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
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
                    taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@ShiftsFragment)
                    taximeterManager.OnFullShiftDetailsResponseReceived.registerListener(this@ShiftsFragment)
                    taximeterManager.OnLastClosedShiftDetailsResponseReceived.registerListener(this@ShiftsFragment)
                }

                override fun onConnectionStatusChanged(connected: Boolean) {
                    // Optional UI feedback
                }
            })
    }

    override fun onDisplayExtendedStatus(
        p0: Any?,
        displayExtendedStatusResponse: DisplayExtendedStatusResponse?
    ) {
        exStat = displayExtendedStatusResponse?.extendedStatusData
    }

    override fun onFullShiftDetailsResponse(p0: Any?, response: FullShiftDetailsResponse?) {
        val totalTripsCount = (response?.fullShiftDetails?.ShiftInfoEnd?.TripsQuantity?.toInt()
            ?: 0) - (response?.fullShiftDetails?.ShiftInfoStart?.TripsQuantity?.toInt() ?: 0)
        val totalFareAmount = (response?.fullShiftDetails?.ShiftInfoEnd?.TotalAmount?.toDouble()
            ?: 0.0) - (response?.fullShiftDetails?.ShiftInfoStart?.TotalAmount?.toDouble() ?: 0.0)
        shiftNo.setText(
            "TURNI: " + (response?.fullShiftDetails?.ShiftConsecutiveNumber ?: "N/A").toString()
        )
        tripCount.setText("UDHETIMET: " + totalTripsCount)
        totalFare.setText("TOTALI: " + totalFareAmount)
        Log.d("ShiftsFragment", "Full Shift Details: ${response?.fullShiftDetails?.ShiftInfoStart}")
    }

    override fun onLastClosedShiftDetailsResponse(
        p0: Any?,
        response: LastClosedShiftDetailsResponse?
    ) {
        val details = response?.fullShiftDetails
        val startDetails = response?.fullShiftDetails?.ShiftInfoStart
        val endDetails = response?.fullShiftDetails?.ShiftInfoEnd
        val shiftInformation = ShiftInfo(
            // General Information
            (details?.ShiftConsecutiveNumber ?: "N/A").toString(),
            // Starter Information
            (startDetails?.TripsQuantity ?: "N/A").toString(),
            (startDetails?.UnitsQuantity ?: "N/A").toString(),
            (startDetails?.TotalDistance ?: "N/A").toString(),
            (startDetails?.HiredDistance ?: "N/A").toString(),
            (startDetails?.ForHireDistance ?: "N/A").toString(),
            (startDetails?.BlackTripDistance ?: "N/A").toString(),
            (startDetails?.WaitingTime ?: "N/A").toString(),
            (startDetails?.FareAmount ?: "N/A").toString(),
            (startDetails?.ExtrasAmount ?: "N/A").toString(),
            (startDetails?.CreditCardAmount ?: "N/A").toString(),
            (startDetails?.TaxAmount ?: "N/A").toString(),
            (startDetails?.TipsAmount ?: "N/A").toString(),
            // End Information
            (endDetails?.TripsQuantity ?: "N/A").toString(),
            (endDetails?.UnitsQuantity ?: "N/A").toString(),
            (endDetails?.TotalDistance ?: "N/A").toString(),
            (endDetails?.HiredDistance ?: "N/A").toString(),
            (endDetails?.ForHireDistance ?: "N/A").toString(),
            (endDetails?.BlackTripDistance ?: "N/A").toString(),
            (endDetails?.WaitingTime ?: "N/A").toString(),
            (endDetails?.FareAmount ?: "N/A").toString(),
            (endDetails?.ExtrasAmount ?: "N/A").toString(),
            (endDetails?.CreditCardAmount ?: "N/A").toString(),
            (endDetails?.TaxAmount ?: "N/A").toString(),
            (endDetails?.TipsAmount ?: "N/A").toString(),
            // Timestamps
            (details?.ShiftStartDate ?: "N/A").toString(),
            (details?.ShiftEndDate ?: "N/A").toString(),
        )
    }

}