package com.driverapp.utils

import com.driverapp.networkApi.models.Trip

interface MyTripsButtonClickListener {
    fun onAcceptStartEndButtonClick(trip:Trip)
    fun onOpenInMapsClick(trip:Trip)
}