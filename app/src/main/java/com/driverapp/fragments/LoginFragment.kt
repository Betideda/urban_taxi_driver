package com.driverapp.fragments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.driverapp.activity.MainActivity
import com.driverapp.R
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.services.LocationPusherService
import com.driverapp.networkApi.Api
import com.driverapp.networkApi.models.LoginBody
import com.driverapp.networkApi.models.LoginResponse
import com.driverapp.services.TripPusherService
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class LoginFragment : Fragment() {
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var loginBtn: Button
    private var emailStr: String = ""
    private var passwordStr: String = ""
    private lateinit var progressBar: View
    private lateinit var sharedPreferencesManager: SharedPreferencesManager
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_login, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.post {
            checkAndRequestPermissions()
            checkBluetoothPermissionIfNeeded()
        }
        sharedPreferencesManager = SharedPreferencesManager(requireContext())
        email = view.findViewById(R.id.email)
        password = view.findViewById(R.id.password)
        progressBar = view.findViewById(R.id.progressBar)
        loginBtn = view.findViewById(R.id.loginBtn)
        loginBtn.setOnClickListener {
            emailStr = email.text.toString()
            passwordStr = password.text.toString()

            loginLogic(emailStr, passwordStr)
        }
    }

    private fun checkBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireActivity(), Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    1001
                )
            }
        }
    }

    private fun loginLogic(emailStr: String, passwordStr: String) {

        if (emailStr.isEmpty() || passwordStr.isEmpty()) {
            alert(getString(R.string.empty), getString(R.string.empty_fields))

        } else {
            requestForLogin(emailStr, passwordStr)
        }
    }

    private fun requestForLogin(emailStr: String, passwordStr: String) {
        progressBar.visibility = View.VISIBLE
        loginBtn.isEnabled = false

        val bodyModel = LoginBody(emailStr, passwordStr, "driver")
        Api.retrofitService.login(bodyModel).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {

                progressBar.visibility = View.GONE
                loginBtn.isEnabled = true

                if (response.isSuccessful && response.body() != null) {
                    val loginResponse = response.body()!!.data
                    val token = loginResponse.token
                    val firstName = loginResponse.user.first_name
                    val id = loginResponse.user.id
                    sharedPreferencesManager.saveString("token", token)
                    sharedPreferencesManager.saveString("firstName", firstName)
                    sharedPreferencesManager.saveString("id", id.toString())

                    val intentTripService = Intent(requireContext(), TripPusherService::class.java)
                    val intentLocationService = Intent(requireContext(), LocationPusherService::class.java)
                    requireContext().startService(intentTripService)
                    requireContext().startService(intentLocationService)
                    startActivity(Intent(requireContext(), MainActivity::class.java))
                } else {
                    alert(getString(R.string.wrong), getString(R.string.wrong_email_password))
                }
            }

            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                loginBtn.isEnabled = true
                alert(getString(R.string.wrong), getString(R.string.wrong_data, t.message))
            }
        })
    }

    private fun alert(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun checkAndRequestPermissions() {
        val permissionsList = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE
        )

        // Add notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsList.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Optional: Add FOREGROUND_SERVICE_LOCATION only if you actually use it and it exists
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
            permissionsList.add("android.permission.FOREGROUND_SERVICE_LOCATION") // This is not an official constant; double-check if you really need this
        }

        val missingPermissions = permissionsList.filter {
            ActivityCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                missingPermissions.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }


    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                loginBtn.isEnabled = true
            } else {
                loginBtn.isEnabled = false
                alert("Denied", "Permission denied")
            }
        }
    }

}
