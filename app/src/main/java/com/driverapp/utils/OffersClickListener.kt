package com.driverapp.utils

import android.widget.Button
import com.driverapp.models.Zone

interface OffersClickListener {
    fun onStartEndButtonClick(zoneOffer: Zone, acceptButton: Button)
}