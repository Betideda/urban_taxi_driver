package com.driverapp.models

import com.driverapp.networkApi.models.Trip

interface TripUpdateListener {
    fun onTripReceived(trip: Trip, assigned: Boolean)
}

object TripEventManager {

    private var lastTrip: Trip? = null
    private var assigned: Boolean = false

    private val listeners = mutableSetOf<TripUpdateListener>()

    fun notifyTripReceived(trip: Trip, assigned: Boolean) {
        lastTrip = trip
        listeners.forEach { it.onTripReceived(trip,assigned) }
    }

    fun addListener(listener: TripUpdateListener) {
        listeners.add(listener)
        lastTrip?.let { listener.onTripReceived(it, assigned) }
    }

    fun removeListener(listener: TripUpdateListener) {
        listeners.remove(listener)
    }
}
