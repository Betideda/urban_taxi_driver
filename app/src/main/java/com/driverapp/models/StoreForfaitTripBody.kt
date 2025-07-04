package com.driverapp.models

data class StoreForFaitTripBody(
    val taximeter_shift_id: Int?,
    val taximeter_trip_id: Int?,
    val offer_id: Int,

    val pickup_address: String?,
    val pickup_address_lat: Double?,
    val pickup_address_lng: Double?,
)
