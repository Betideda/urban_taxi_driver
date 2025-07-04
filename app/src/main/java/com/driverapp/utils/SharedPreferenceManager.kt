package com.driverapp.utils

import android.content.Context
import android.content.SharedPreferences
import com.driverapp.networkApi.models.Trip
import com.google.gson.Gson

class SharedPreferencesManager(context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("UrbanShared", Context.MODE_PRIVATE)
    private val editor: SharedPreferences.Editor = sharedPreferences.edit()

    fun saveString(key: String, value: String) {
        editor.putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String): String {
        return sharedPreferences.getString(key, defaultValue) ?: defaultValue
    }

    fun saveInt(key: String, value: Int) {
        editor.putInt(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int): Int {
        return sharedPreferences.getInt(key, defaultValue)
    }

    fun saveBoolean(key: String, value: Boolean) {
        editor.putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }

    fun removeKey(key: String) {
        editor.remove(key).apply()
    }

    fun clear() {
        editor.clear().apply()
    }

    fun putTrip(key: String, trip: Trip) {
        val json = Gson().toJson(trip)
        sharedPreferences.edit().putString(key, json).apply()
    }

    fun getTrip(key: String): Trip? {
        val json = sharedPreferences.getString(key, null)
        return if (json != null) Gson().fromJson(json, Trip::class.java) else null
    }
}