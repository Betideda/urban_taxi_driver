package com.driverapp.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.digitax.android.libcomtax2.taximeter.events.CurrentFareResponseListener
import com.digitax.android.libcomtax2.taximeter.events.DisplayExtendedStatusListener
import com.digitax.android.libcomtax2.taximeter.events.LastClosedShiftDetailsResponseListener
import com.digitax.android.libcomtax2.taximeter.events.OldTripsShiftsSearchCompleteResponseListener
import com.digitax.android.libcomtax2.taximeter.events.TaximeterStatusResponseListener
import com.digitax.android.libcomtax2.taximeter.messages.CurrentFareResponse
import com.digitax.android.libcomtax2.taximeter.messages.DisplayExtendedStatusResponse
import com.digitax.android.libcomtax2.taximeter.messages.LastClosedShiftDetailsResponse
import com.digitax.android.libcomtax2.taximeter.messages.OldTripsShiftsSearchCompleteResponse
import com.digitax.android.libcomtax2.taximeter.messages.TaximeterStatusResponse
import com.digitax.android.libcomtax2.taximeter.objects.ExtendedStatus
import com.digitax.android.libcomtax2.taximeter.objects.OldTripShiftRequest
import com.digitax.android.libutility.concurrent.ThreadUtility.runOnUiThread
import com.digitax.protocols.ILoggerHandler
import com.digitax.protocols.LogEventArgs
import com.driverapp.R
import com.driverapp.models.ClosedShiftData
import com.driverapp.networkApi.Api
import com.driverapp.networkApi.models.TaximeterStatusBody
import com.driverapp.utils.DigitaxTaximeterInitializer
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.utils.TaxiModelAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TaxiControlFragment : Fragment(), ILoggerHandler, DisplayExtendedStatusListener,
    OldTripsShiftsSearchCompleteResponseListener, LastClosedShiftDetailsResponseListener,
    CurrentFareResponseListener, TaximeterStatusResponseListener {

    private var taximeterManagerr: TaximeterManager? = null
    private var agent: TaxiModelAgent? = null
    var exStat: ExtendedStatus? = null
    private lateinit var sharedPreferencesManager: SharedPreferencesManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_taxi_control, container, false)
        sharedPreferencesManager = SharedPreferencesManager(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            initializeDigitaxTaximeter()
        }

        val btnStartTrip = view.findViewById<Button>(R.id.btnStartTrip)
        val btnEndTrip = view.findViewById<Button>(R.id.btnEndTrip)
        val askOldTrips = view.findViewById<Button>(R.id.askOldTrips)
        val askCurrentFareAmount = view.findViewById<Button>(R.id.askCurrentFareAmount)
        val btnStatus = view.findViewById<Button>(R.id.btnTaximeterStatus)
        val etFareAmount = view.findViewById<EditText>(R.id.etFareAmount)
        val askLastClosedShiftDetails = view.findViewById<Button>(R.id.askLastClosedShiftDetails)

        btnStartTrip.setOnClickListener {
            try {
                agent?.startForfaitTrip(etFareAmount.text.toString()) // becomes 00005000
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Trip start failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                Log.e("TaxiControl", "Error starting trip", e)
            }
        }

        btnEndTrip.setOnClickListener {
            agent?.printLastTripTicket()
            Toast.makeText(requireContext(), "Ticket is printing...!", Toast.LENGTH_SHORT).show()
            //DONE
        }

        askOldTrips.setOnClickListener {
            Log.d("TaxiControl", "OldTrips request started")
            val cal = Calendar.getInstance().apply {
                add(Calendar.MONTH, -2)
            }
            val request = OldTripShiftRequest(true, 20.toByte(), 14, cal)
            agent?.askOldTrips(true, request)
        }

        askLastClosedShiftDetails.setOnClickListener {
            //agent?.askLastClosedShiftDetails()
        }

        askCurrentFareAmount.setOnClickListener {
            agent?.askCurrentFareAmount()
            val currentFareAmount = exStat?.CurrentFareAmount
            Toast.makeText(
                requireContext(),
                "Current Fare Amount: ${currentFareAmount ?: "N/A"}",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnStatus.setOnClickListener {
            Toast.makeText(
                requireContext(),
                "Status: ${exStat?.StatusCode ?: "N/A"}",
                Toast.LENGTH_SHORT
            ).show()
        }

        return view
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
                    agent = taxiModelAgent

                    taximeterManager.OnDisplayExtendedStatusReceived.registerListener(this@TaxiControlFragment)
                    taximeterManager.OnOldTripsShiftsSearchCompleteResponseReceived.registerListener(
                        this@TaxiControlFragment
                    )
                    taximeterManager.OnLastClosedShiftDetailsResponseReceived.registerListener(this@TaxiControlFragment)
                    taximeterManager.OnCurrentFareResponseReceived.registerListener(this@TaxiControlFragment)
                    taximeterManager.OnTaximeterStatusResponseReceived.registerListener(this@TaxiControlFragment)
                }

                override fun onConnectionStatusChanged(connected: Boolean) {
                    // Optional UI feedback
                }
            })
    }

    override fun Log(lea: LogEventArgs?) {
        println("Digitax Log: ${lea?.LogLevelGet().toString()}")
    }

    override fun onDisplayExtendedStatus(
        p0: Any?,
        displayExtendedStatusResponse: DisplayExtendedStatusResponse?
    ) {
        exStat = displayExtendedStatusResponse?.extendedStatusData
        Log.e("TaxiControl", "StatusCode: ${exStat?.StatusCode}")
        Log.e("TaxiControl", "DriverID: ${exStat?.DriverID}")
        Log.e("TaxiControl", "CurrentFareAmount: ${exStat?.CurrentFareAmount}")
        Log.e("TaxiControl", "ShiftNumber: ${exStat?.ShiftNumber}")

    }

    override fun onOldTripsShiftsSearchCompleteResponse(
        p0: Any?,
        response: OldTripsShiftsSearchCompleteResponse?
    ) {

        Log.d("OldTripsShifts", "Destination value: " + response?.destination?.value.toString())


        val json = response?.body?.toString(Charsets.UTF_8)
        Log.d("OldTripsShifts", "JSON body: $json")
    }

    override fun onLastClosedShiftDetailsResponse(
        p0: Any?,
        lastClosedShiftDetailsResponse: LastClosedShiftDetailsResponse?
    ) {
        val dateFormat = SimpleDateFormat("dd MMMM yyyy HH:mm:ss", Locale.getDefault())

        val fareAmount =
            lastClosedShiftDetailsResponse?.fullShiftDetails?.ShiftInfoStart?.FareAmount
        val driverId = lastClosedShiftDetailsResponse?.fullShiftDetails?.DriverID
        val vehicleId = lastClosedShiftDetailsResponse?.fullShiftDetails?.VehicleID
        val startDateFormatted =
            lastClosedShiftDetailsResponse?.fullShiftDetails?.ShiftStartDate?.let {
                dateFormat.format(it.time)
            }
        val endDateFormatted = lastClosedShiftDetailsResponse?.fullShiftDetails?.ShiftEndDate?.let {
            dateFormat.format(it.time)
        }
        val closedShiftData = ClosedShiftData(
            fareAmount = fareAmount,
            driverId = driverId,
            vehicleId = vehicleId,
            shiftStartDate = startDateFormatted,
            shiftEndDate = endDateFormatted
        )
        val message = """
        Driver ID: $driverId
        Vehicle ID: $vehicleId
        Fare Amount: ${fareAmount ?: "N/A"}
        Shift Started: ${startDateFormatted ?: "N/A"}
        Shift Ended: ${endDateFormatted ?: "N/A"}
    """.trimIndent()
        requireActivity().runOnUiThread {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Last Closed Shift Details")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onCurrentFareResponse(p0: Any?, fare: CurrentFareResponse?) {
        //this here is triggered when it has been started a new trip and is Hired status
        //then we get what is the current fare amount
        runOnUiThread {
            Log.d(
                "CUSTOM_LOG",
                "Current Fare: " + (fare?.currentFareAmount.toString())
            )
        }
    }

    override fun onTaximeterStatusResponse(p0: Any?, status: TaximeterStatusResponse?) {
        runOnUiThread {
            val taxiStatus = status?.taximeterStatus.toString()
            Log.d("CUSTOM_LOG", "Status: $taxiStatus")

            val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"

            val body = TaximeterStatusBody(taxiStatus)

            Api.retrofitService.taximeterStatus(token, body)
                .enqueue(object : Callback<ResponseBody> {
                    override fun onResponse(
                        call: Call<ResponseBody>,
                        response: Response<ResponseBody>
                    ) {
                        if (response.isSuccessful) {
                            Log.d("TaxiControl", "Taximeter status sent successfully: $taxiStatus")
                            activity?.runOnUiThread {
                                Toast.makeText(
                                    requireContext(),
                                    "Taximeter status sent successfully",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            Log.e(
                                "TaxiControl",
                                "Failed to send taximeter status: ${response.errorBody()?.string()}"
                            )
                            activity?.runOnUiThread {
                                Toast.makeText(
                                    requireContext(),
                                    "Failed to send taximeter status: ${
                                        response.errorBody()?.string()
                                    }",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                        Log.e("TaxiControl", "Error sending taximeter status: ${t.message}")
                        activity?.runOnUiThread {
                            Toast.makeText(
                                requireContext(),
                                "Error sending taximeter status: ${t.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                })

        }
    }
}
