package com.driverapp.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.location.LocationManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.digitax.protocols.Bluetooth.BluetoothDevicePaired
import com.digitax.protocols.Bluetooth.BluetoothManagerDigitax
import com.digitax.protocols.DataSource.BleTaxDataSource
import com.digitax.protocols.ILoggerHandler
import com.digitax.protocols.LogEventArgs
import com.driverapp.R
import com.driverapp.services.LocationPusherService
import com.driverapp.services.TripPusherService
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.utils.TaxiModelAgent

class SplashScreenActivity : AppCompatActivity(), ILoggerHandler {

    private lateinit var sharedPreferencesManager: SharedPreferencesManager
    private var hasStartedNextActivity = false
    private var taximeterManager: TaximeterManager? = null
    private var taxiModelAgent: TaxiModelAgent? = null

    companion object {
        private const val REQUEST_CODE_BLUETOOTH_CONNECT = 102
        private const val REQUEST_CODE_LOCATION_PERMISSION = 103
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.splash_screen)

        sharedPreferencesManager = SharedPreferencesManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_CODE_BLUETOOTH_CONNECT
            )
            return
        }

        checkLocationPermissionAndProceed()
    }

    private fun checkLocationPermissionAndProceed() {
        if (!isLocationPermissionGranted()) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_CODE_LOCATION_PERMISSION
            )
        } else {
            proceedWithLocationEnabledCheck()
        }
    }

    private fun isLocationPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun proceedWithLocationEnabledCheck() {
        if (!isLocationEnabled()) {
            showEnableLocationDialog()
        } else {
            initBluetoothAndTaximeter()
        }
    }

    private fun initBluetoothAndTaximeter() {
        Thread {
            // Step 1: Check if Bluetooth is supported
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                runOnUiThread {
                    Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
                    proceedToNextActivity()
                }
                return@Thread
            }

            // Step 2: Initialize BluetoothManagerDigitax
            val btManager = BluetoothManagerDigitax(this)

            // Step 3: Check permissions
            if (!BluetoothManagerDigitax.CheckPermissions(this)) {
                runOnUiThread {
                    Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
                }
            }

            // Step 4: Ensure Bluetooth is turned on
            BluetoothManagerDigitax.CheckBluetoothIsOn(this)

            // Step 5: Get paired Digitax devices
            val pairedDevices: List<BluetoothDevicePaired>? = btManager.PairedDigitaxDevicesGet()

            if (pairedDevices.isNullOrEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "No Digitax paired device found", Toast.LENGTH_LONG).show()
                    proceedToNextActivity()
                }
                return@Thread
            }

            // Step 6: Get the first paired device
            val btDevice = pairedDevices.firstOrNull()?.Device
            if (btDevice == null) {
                runOnUiThread {
                    Toast.makeText(this, "No Bluetooth device found", Toast.LENGTH_LONG).show()
                    proceedToNextActivity()
                }
                return@Thread
            }

            // Step 7: Set up taximeter data source
            val dataSource = BleTaxDataSource(this, btDevice)
            btManager.BleTaximeterDataSourceGet(this, dataSource)?.let { manager ->
                taximeterManager = manager
                taxiModelAgent = TaxiModelAgent(manager)

                // Set up connection listener
                dataSource.ConnectionStatusChangedListenerAdd { _, connected ->
                    runOnUiThread {
                        Toast.makeText(this, "Connected: $connected", Toast.LENGTH_SHORT).show()
                    }
                }

                // Start protocol
                dataSource.ProtocolStart()
            } ?: run {
                runOnUiThread {
                    Toast.makeText(this, "Failed to initialize taximeter", Toast.LENGTH_LONG).show()
                }
            }

            // Step 8: Proceed regardless of outcome
            runOnUiThread {
                proceedToNextActivity()
            }
        }.start()
    }



    private fun proceedToNextActivity() {
        Handler(Looper.getMainLooper()).postDelayed({
            val token = sharedPreferencesManager.getString("token", "")
            if (token.isNotEmpty()) {
                startService(Intent(this, LocationPusherService::class.java))
                startService(Intent(this, TripPusherService::class.java))
                startActivity(Intent(this, MainActivity::class.java))
            } else {
                startActivity(Intent(this, LoginActivity::class.java))
            }
            finish()
        }, 1000)
    }

    private fun showEnableLocationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Enable Location")
            .setMessage("Location services are required. Do you want to enable them?")
            .setPositiveButton("YES") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setCancelable(false)
            .show()
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_CODE_BLUETOOTH_CONNECT -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    checkLocationPermissionAndProceed()
                } else {
                    Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_LONG).show()
                    finish()
                }
            }

            REQUEST_CODE_LOCATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    proceedWithLocationEnabledCheck()
                } else {
                    Toast.makeText(this, "Location permission denied", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun Log(p0: LogEventArgs?) {
        android.util.Log.d("Digitax Log", "Log Level: ${p0?.LogLevelGet()}")
    }
}
