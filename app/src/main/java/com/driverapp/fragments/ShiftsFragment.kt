package com.driverapp.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.digitax.android.libcomtax2.taximeter.events.OldTripsShiftsSearchCompleteResponseListener
import com.digitax.android.libcomtax2.taximeter.messages.DisplayExtendedStatusResponse
import com.digitax.android.libcomtax2.taximeter.messages.FullShiftDetailsResponse
import com.digitax.android.libcomtax2.taximeter.messages.LastClosedShiftDetailsResponse
import com.digitax.android.libcomtax2.taximeter.messages.OldTripsShiftsSearchCompleteResponse
import com.digitax.android.libcomtax2.taximeter.objects.ExtendedStatus
import com.driverapp.R
import com.driverapp.utils.DigitaxTaximeterInitializer
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.utils.TaxiModelAgent
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShiftsFragment : Fragment(),
    DisplayExtendedStatusListener,
    FullShiftDetailsResponseListener,
    LastClosedShiftDetailsResponseListener,
    OldTripsShiftsSearchCompleteResponseListener{

    private lateinit var sharedPreferencesManager: SharedPreferencesManager
    private lateinit var offlineLayout: LinearLayout
    private lateinit var onlineLayout: LinearLayout
    private lateinit var offlineIcon: ImageView
    private lateinit var onlineIcon: ImageView
    private lateinit var offlineText: TextView
    private lateinit var onlineText: TextView
    private lateinit var shiftNo: TextView
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
        }

        offlineLayout = view.findViewById(R.id.offline_layout)
        onlineLayout = view.findViewById(R.id.online_layout)
        offlineIcon = view.findViewById(R.id.offline_icon)
        onlineIcon = view.findViewById(R.id.online_icon)
        offlineText = view.findViewById(R.id.offline_text)
        onlineText = view.findViewById(R.id.online_text)
        shiftNo = view.findViewById(R.id.shiftNo)

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
        } else {
            showSnackbar(getString(R.string.you_are_offline))
            taxiModelAgentt?.closeShift()
            //Do not need to print, since it is automatically printed
            //taxiModelAgentt?.printLastShiftReport()

            Handler(Looper.getMainLooper()).postDelayed({
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
                    taximeterManager.OnOldTripsShiftsSearchCompleteResponseReceived.registerListener(this@ShiftsFragment)
                }

                override fun onConnectionStatusChanged(connected: Boolean) {
                    // Optional UI feedback
                }
            })
    }

    override fun onDisplayExtendedStatus(p0: Any?, displayExtendedStatusResponse: DisplayExtendedStatusResponse?) {
        exStat = displayExtendedStatusResponse?.extendedStatusData
        shiftNo.text = "TURNI: " + (exStat?.ShiftNumber ?: "N/A").toString()
    }

    override fun onFullShiftDetailsResponse(p0: Any?, fullShiftDetailsResponse: FullShiftDetailsResponse?) {
        //Log.d("ShiftsFragment", "Full Shift Details: ${fullShiftDetailsResponse?.fullShiftDetails?.ShiftInfoStart}")
    }

    override fun onLastClosedShiftDetailsResponse(p0: Any?, lastClosedResponse: LastClosedShiftDetailsResponse?) {
        //Log.d("LastClosed", "Shift Details: ${lastClosedResponse?.fullShiftDetails.toString()}")
        //
        //val request = OldTripShiftRequest(
        //    false,
        //    lastClosedResponse?.fullShiftDetails?.ShiftInfoEnd?.TripsQuantity?.toByte() ?: 0,
        //    0,
        //    lastClosedResponse?.fullShiftDetails?.ShiftStartDate,
        //);
        //
        //taxiModelAgentt?.askOldTrips(true, request)
    }

    override fun onOldTripsShiftsSearchCompleteResponse(p0: Any?, p1: OldTripsShiftsSearchCompleteResponse?) {
        //val json = p1?.body?.toString(Charsets.UTF_8)
        //Log.d("OldTripsShifts", "JSON body: $json")
        //val size = p1?.body?.size
        //Log.d("OldTripsShifts", "size: $size")
        //TODO() // Handle the response as needed, e.g., also add api request later
    }
}