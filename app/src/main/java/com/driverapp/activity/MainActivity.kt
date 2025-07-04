package com.driverapp.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.digitax.android.libcomtax2.taximeter.TaximeterManager
import com.digitax.protocols.ILoggerHandler
import com.digitax.protocols.LogEventArgs
import com.driverapp.R
import com.driverapp.utils.TaxiModelAgent
import com.driverapp.fragments.TripsFragment
import com.driverapp.fragments.DashboardFragment
import com.driverapp.fragments.SettingsProfileFragment
import com.driverapp.fragments.ShiftsFragment
import com.driverapp.fragments.TaxiControlFragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity(), ILoggerHandler {
    private var taximeterManager: TaximeterManager? = null
    private var taxiModelAgent: TaxiModelAgent? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tripId = intent.getIntExtra("trip_id", -1)
        if (tripId != -1) {
            // Notification clicked — open TripsFragment
            loadFragment(TripsFragment())
            val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
            bottomNav.selectedItemId = R.id.nav_bookings // Optional: update UI selection
        } else {
            // Default fragment
            loadFragment(DashboardFragment())
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> loadFragment(DashboardFragment())
                R.id.nav_bookings -> loadFragment(TripsFragment())
                R.id.nav_shifts -> loadFragment(ShiftsFragment())
                R.id.nav_profile -> loadFragment(SettingsProfileFragment())
                //R.id.tax_manager -> loadFragment(TaxiControlFragment())
            }
            true
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    override fun Log(lea: LogEventArgs?) {
        println("Digitax Log: ${lea?.LogLevelGet()}")
    }
}