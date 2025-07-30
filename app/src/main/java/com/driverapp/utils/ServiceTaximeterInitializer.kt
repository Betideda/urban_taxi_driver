package com.driverapp.utils

import android.content.Context
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.digitax.protocols.Bluetooth.BluetoothDevicePaired
import com.digitax.protocols.Bluetooth.BluetoothManagerDigitax
import com.digitax.protocols.DataSource.BleTaxDataSource
import com.digitax.protocols.ILoggerHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ServiceTaximeterInitializer(
    private val context: Context,
) {
    interface Callback {
        fun onInitialized(
            taximeterManager: TaximeterManager,
            taxiModelAgent: TaxiModelAgent
        )

        fun onConnectionStatusChanged(connected: Boolean)
    }

    suspend fun initialize(callback: Callback) {
        withContext(Dispatchers.IO) {
            val btManager = BluetoothManagerDigitax(context)

            val pairedDevices: List<BluetoothDevicePaired>? = btManager.PairedDigitaxDevicesGet()
            val btDevice = pairedDevices?.firstOrNull()?.Device


            val dataSource = BleTaxDataSource(context, btDevice)
            val loggerHandler = ILoggerHandler { }
            val taximeterManager = btManager.BleTaximeterDataSourceGet(loggerHandler, dataSource)
            val taxiModelAgent = TaxiModelAgent(taximeterManager)


            callback.onInitialized(taximeterManager, taxiModelAgent)
            dataSource.ProtocolStart()
        }
    }
}