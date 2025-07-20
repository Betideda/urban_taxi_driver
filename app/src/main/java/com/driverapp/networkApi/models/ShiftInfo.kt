package com.driverapp.networkApi.models

data class ShiftInfo(
    // General Information
    val taximeter_shift_id: String,
    // Start Info
    val start_info_trips_quantity: String,
    val start_info_units_quantity: String,
    val start_info_total_distance: String,
    val start_info_hired_distance: String,
    val start_info_for_hire_distance: String,
    val start_info_black_trip_distance: String,
    val start_info_waiting_time: String,
    val start_info_fare_amount: String,
    val start_info_extras_amount: String,
    val start_info_credit_card_amount: String,
    val start_info_tax_amount: String,
    val start_info_tips_amount: String,
    // End Info
    val end_info_trips_quantity: String,
    val end_info_units_quantity: String,
    val end_info_total_distance: String,
    val end_info_hired_distance: String,
    val end_info_for_hire_distance: String,
    val end_info_black_trip_distance: String,
    val end_info_waiting_time: String,
    val end_info_fare_amount: String,
    val end_info_extras_amount: String,
    val end_info_credit_card_amount: String,
    val end_info_tax_amount: String,
    val end_info_tips_amount: String,
    // Timestamps
    val shift_started_at: String,
    val shift_ended_at: String,
)
