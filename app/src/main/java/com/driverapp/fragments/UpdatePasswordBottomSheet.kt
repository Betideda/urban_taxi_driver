package com.driverapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import com.driverapp.R
import com.driverapp.utils.SharedPreferencesManager
import com.driverapp.networkApi.Api
import com.driverapp.networkApi.models.UpdatePasswordBody
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import okhttp3.ResponseBody

class UpdatePasswordBottomSheet(
) : BottomSheetDialogFragment() {
    private lateinit var sharedPreferencesManager: SharedPreferencesManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.update_password_bottomsheet, container, false)
        sharedPreferencesManager = SharedPreferencesManager(requireContext())
        val etCurrent = view.findViewById<EditText>(R.id.etCurrentPassword)
        val etNew = view.findViewById<EditText>(R.id.etNewPassword)
        val etConfirm = view.findViewById<EditText>(R.id.etConfirmPassword)
        val btnUpdate = view.findViewById<Button>(R.id.btnConfirmUpdate)

        btnUpdate.setOnClickListener {
            val body = UpdatePasswordBody(
                current_password = etCurrent.text.toString(),
                new_password = etNew.text.toString(),
                new_password_confirmation = etConfirm.text.toString()
            )
            val token = "Bearer ${sharedPreferencesManager.getString("token", "")}"

            Api.retrofitService.updatePassword(token, body).enqueue(
                object : retrofit2.Callback<ResponseBody> {
                    override fun onResponse(
                        call: retrofit2.Call<ResponseBody>,
                        response: retrofit2.Response<ResponseBody>
                    ) {
                        if (response.isSuccessful) {
                            Snackbar.make(view, "Password updated successfully!", Snackbar.LENGTH_SHORT).show()
                            dismiss()
                        } else {
                            val errorMessage = response.errorBody()?.string()
                            Snackbar.make(view, errorMessage.toString(), Snackbar.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(
                        call: retrofit2.Call<ResponseBody>,
                        t: Throwable
                    ) {
                        Snackbar.make(view, t.message.toString(), Snackbar.LENGTH_SHORT).show()
                    }
                }
            )
            dismiss()
        }

        return view
    }
}
