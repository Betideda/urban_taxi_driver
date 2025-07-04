package com.driverapp.utils

import android.util.Log
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.digitax.android.libcomtax2.taximeter.objects.GPSInfo
import com.digitax.android.libcomtax2.taximeter.objects.OldTripShiftRequest
import com.digitax.android.libcomtax2.taximeter.objects.RecordSearchTypeEnum
import java.util.Calendar

class TaxiModelAgent(private val taximeterManager: TaximeterManager) {

    fun askLockStatus() {
        taximeterManager.askLockStatus()
    }

    fun setLockStatus(encryptedData: ByteArray) {
        taximeterManager.setLockStatus(encryptedData)
    }

    fun setGpsData(info: GPSInfo) = taximeterManager.setGPSData(info)

    fun triggerGps(period: Byte) = taximeterManager.setGPSDataTrigger(period)

    fun sendGpsInfoAnswer(fix: Byte, lat: Int, lon: Int) =
        taximeterManager.sendGpsInfoRequestAnswer(fix, lat, lon)

    fun askOldTrips(back: Boolean, request: OldTripShiftRequest) {
        taximeterManager.askOldTrips(back, request)
    }

    fun askOldShifts(back: Boolean, request: OldTripShiftRequest){
        taximeterManager.askOldShifts(back, request)
    }

    fun askOldRecords(
        quick: Boolean,
        maxRec: Byte,
        recFilter: Int,
        recType: RecordSearchTypeEnum,
        dateFilter: Calendar
    ) = taximeterManager.askOldRecords(quick, maxRec, recFilter, recType, dateFilter)

    fun printLastShiftReport() {
        taximeterManager.printLastShiftReport()
    }

    fun printLastTripTicket() {
        taximeterManager.printLastTripTicket()
    }

    fun stopTransmission() {
        taximeterManager.stopTransmission()
    }

    fun askTaximeterStatus() {
        return taximeterManager.askTaximeterStatus()
    }

    fun closeShift() = taximeterManager.closeShift()

    fun askLastClosedShiftDetails() {
        taximeterManager.askLastClosedShiftDetails()
    }

    fun askOpenedShiftDetails() {
        taximeterManager.askOpenedShiftDetails()
    }

    fun askCurrentFareAmount() {
        Log.d("TaxiModelAgent", "askCurrentFareAmount called")
        taximeterManager.askCurrentFareAmount()
    }

    fun askFullShiftDetails(currentShift: Boolean) =
        taximeterManager.askFullShiftDetails(currentShift)

    fun startForfaitTrip(fareAmount: String) {
        val safeAmount = fareAmount.padStart(8, '0')
        if (safeAmount.length != 8) {
            Log.e("TaxiModelAgent", "Invalid fare amount format: $fareAmount")
            return
        }
        taximeterManager.startForfaitTrip(safeAmount)
    }

    fun openShift(driverNumber: String, driverName: String? = null) {
        if (driverName != null)
            taximeterManager.openShift(driverNumber, driverName)
        else
            taximeterManager.openShift(driverNumber)
    }
}
