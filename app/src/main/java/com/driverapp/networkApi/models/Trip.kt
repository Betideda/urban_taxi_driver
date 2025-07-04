package com.driverapp.networkApi.models

data class MyTripData(
    val data: Trip

)
data class Trip(
    val can_accept: Boolean,
    val can_start: Boolean,
    val can_complete: Boolean,
    val id: Int,
    val creator_id: Int,
    val customer_id: Int?,
    val driver_id: Int?,
    val requested_vehicle_category_id: Int?,
    val vehicle_category_id: Int?,
    val vehicle_id: Int?,
    val payment_type_id: Int?,
    val currency_id: Int,
    val serial: String,
    val trip_status: String,
    val trip_status_label: String,
    val requested_pickup_address: String,
    val requested_pickup_address_lat: Double,
    val requested_pickup_address_lng: Double,
    val requested_drop_off_address: String,
    val requested_drop_off_address_lat: Double,
    val requested_drop_off_address_lng: Double,
    val requested_distance: String,
    val pickup_address: String?,
    val pickup_address_lat: Double?,
    val pickup_address_lng: Double?,
    val drop_off_address: String?,
    val drop_off_address_lat: Double?=null,
    val drop_off_address_lng: Double?,
    val distance: String? = null,
    val sub_total: String,
    val discount: String,
    val total: String,
    val requested_pickup_at: String?,
    val picked_up_at: String?,
    val dropped_of_at: String?,
    val created_at: String,
    val updated_at: String
)

