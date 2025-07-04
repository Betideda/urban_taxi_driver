package com.driverapp.utils

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.digitax.protocols.Bluetooth.BluetoothDevicePaired
import com.digitax.protocols.Bluetooth.BluetoothManagerDigitax
import com.digitax.protocols.DataSource.BleTaxDataSource
import com.digitax.protocols.ILoggerHandler

class DigitaxTaximeterInitializer(
    private val context: Context,
    private val activity: Activity,
) {
    interface Callback {
        fun onInitialized(
            taximeterManager: TaximeterManager,
            taxiModelAgent: TaxiModelAgent
        )

        fun onConnectionStatusChanged(connected: Boolean)
    }

    fun initialize(callback: Callback) {
        val btManager = BluetoothManagerDigitax(context)

        if (!BluetoothManagerDigitax.CheckPermissions(activity)) {
            activity.runOnUiThread {
                Toast.makeText(context, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
            }
            return
        }

        BluetoothManagerDigitax.CheckBluetoothIsOn(activity)

        val pairedDevices: List<BluetoothDevicePaired>? = btManager.PairedDigitaxDevicesGet()
        val btDevice = pairedDevices?.firstOrNull()?.Device

        if (btDevice == null) {
            activity.runOnUiThread {
                Toast.makeText(context, "No paired Digitax devices found", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val dataSource = BleTaxDataSource(context, btDevice)
        val loggerHandler = ILoggerHandler {  }
        val taximeterManager = btManager.BleTaximeterDataSourceGet(loggerHandler, dataSource)
        val taxiModelAgent = TaxiModelAgent(taximeterManager)

        dataSource.ConnectionStatusChangedListenerAdd { _, connected ->
            activity.runOnUiThread {
                Toast.makeText(context, "Connected: $connected", Toast.LENGTH_SHORT).show()
                callback.onConnectionStatusChanged(connected)
            }
        }

        callback.onInitialized(taximeterManager, taxiModelAgent)
        dataSource.ProtocolStart()
    }
}
