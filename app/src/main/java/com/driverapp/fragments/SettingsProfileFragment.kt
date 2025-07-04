package com.driverapp.fragments

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.driverapp.activity.LoginActivity
import com.driverapp.R
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.networkApi.Api
import com.driverapp.networkApi.models.ProfileResponse
import com.google.android.material.snackbar.Snackbar
import okhttp3.ResponseBody

class SettingsProfileFragment : Fragment() {
    lateinit var tvName: TextView
    lateinit var tvEmail: TextView
    lateinit var tvRole: TextView
    lateinit var tvStatus: TextView
    lateinit var avatarText: TextView
    lateinit var btnLogout: Button
    lateinit var btnUpdatePassword: Button
    private lateinit var sharedPreferencesManager: SharedPreferencesManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_profile_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sharedPreferencesManager = SharedPreferencesManager(requireContext())

        requestForProfileDetails(sharedPreferencesManager)

        tvName = view.findViewById(R.id.tvName)
        tvEmail = view.findViewById(R.id.tvEmail)
        avatarText = view.findViewById(R.id.avatarText)
        tvRole = view.findViewById(R.id.tvRole)
        tvStatus = view.findViewById(R.id.tvStatus)
        btnUpdatePassword = view.findViewById(R.id.btnUpdatePassword)
        btnLogout = view.findViewById(R.id.btnLogout)
        btnLogout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.confirm_logout))
                .setMessage(getString(R.string.are_you_sure_you_want_to_logout))
                .setPositiveButton(getString(R.string.yes)) { dialog, _ ->
                    dialog.dismiss()
                    requestLogout(sharedPreferencesManager)
                }
                .setNegativeButton(getString(R.string.no)) { dialog, _ ->
                    dialog.dismiss()
                }
                .create()
                .show()
        }
        btnUpdatePassword.setOnClickListener {
            val bottomSheet = UpdatePasswordBottomSheet()
            bottomSheet.show(parentFragmentManager, "UpdatePasswordBottomSheet")
        }

    }

    private fun requestLogout(sharedPreferencesManager: SharedPreferencesManager) {
        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"

        Api.retrofitService.logout(token).enqueue(
            object : retrofit2.Callback<ResponseBody> {
                override fun onResponse(
                    call: retrofit2.Call<ResponseBody>,
                    response: retrofit2.Response<ResponseBody>
                ) {
                    if (response.isSuccessful) {
                        sharedPreferencesManager.clear()
                        showSnackbar("Logout successful")
                        startActivity(Intent(requireActivity(), LoginActivity::class.java))
                        requireActivity().finish()
                    } else {
                        showSnackbar("Failed to logout!")// Handle failure
                    }
                }

                override fun onFailure(
                    call: retrofit2.Call<ResponseBody>,
                    t: Throwable
                ) {
                    showSnackbar("Server error: " + t.localizedMessage)// Handle failure
                }
            }
        )
    }

    private fun requestForProfileDetails(sharedPreferencesManager: SharedPreferencesManager) {

        val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"
        Api.retrofitService.getProfileDetails(token).enqueue(
            object : retrofit2.Callback<ProfileResponse> {
                override fun onResponse(
                    call: retrofit2.Call<ProfileResponse>,
                    response: retrofit2.Response<ProfileResponse>
                ) {
                    if (response.isSuccessful) {
                        val profile = response.body()?.data
                        if (profile != null) {
                            val initials = "${profile.first_name.firstOrNull()?.uppercaseChar() ?: ""}${profile.last_name.firstOrNull()?.uppercaseChar() ?: ""}"
                            avatarText.text = initials
                            tvName.text = profile.first_name + " " + profile.last_name
                            tvEmail.text = profile.email

                            tvRole.text = profile.role.title

                            val backgroundColor = profile.role.background_color //String
                            val textColor = profile.role.text_color //String
                            try {
                                tvRole.setBackgroundColor(Color.parseColor(backgroundColor))
                                tvRole.setTextColor(Color.parseColor(textColor))
                            } catch (e: IllegalArgumentException) {
                                e.printStackTrace()
                                // fallback colors if needed
                                tvRole.setBackgroundColor(Color.GRAY)
                                tvRole.setTextColor(Color.WHITE)
                            }


                            tvStatus.text = if (profile.is_online) "Online" else "Offline"
                        }
                    } else {
                        showSnackbar("Failed to set profile data!")// Handle failure

                    }
                }

                override fun onFailure(
                    call: retrofit2.Call<ProfileResponse>,
                    t: Throwable
                ) {
                    showSnackbar("Server error: " + t.localizedMessage)// Handle failure
                }
            }
        )
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
    }
}