package com.driverapp.networkApi.models

data class LoginBody(
    val email: String,
    val password: String,
    val user_type: String,
)

data class LoginResponse(
    val data: Data
)

data class Data(
    val token: String,
    val user: User
)

data class User(
    val id: Int,
    val role_id: Int,
    val user_type: Int,
    val user_type_label: String,
    val status: Int,
    val is_online: Boolean,
    val first_name: String,
    val last_name: String,
    val phone_number: String?,
    val email: String,
    val locale: String,
    val address: String?,
    val created_at: String,
    val updated_at: String
)

data class UpdatePasswordBody(
    val current_password: String,
    val new_password: String,
    val new_password_confirmation: String
)

data class SetLocationBody(
    val latitude: Double,
    val longitude: Double
)

data class GenericResponse(
    val success: Boolean,
    val message: String
)

data class OnlineStatusBody(
    val isOnline: Int // 1 for online, 0 for offline
)

data class TaximeterStatusBody(
    val taximeter_status: String
)

data class ProfileResponse(
    val data: UserProfile
)

data class PickupTripAddressBody(
    val taximeter_shift_id: Int?,
    val taximeter_trip_id: Int?,
    val pickup_address: String?,
    val pickup_address_lat: Double?,
    val pickup_address_lng: Double?,
)
data class DropOffTripAddressBody(
    val drop_off_address: String?,
    val drop_off_address_lat: Double?,
    val drop_off_address_lng: Double?,
    val distance: Float?,
)

data class UserProfile(
    val id: Int,
    val role_id: Int,
    val user_type: Int,
    val user_type_label: String,
    val status: Int,
    val is_online: Boolean,
    val first_name: String,
    val last_name: String,
    val phone_number: String?, // nullable
    val email: String,
    val locale: String,
    val address: String?, // nullable
    val created_at: String,
    val updated_at: String,
    val role: UserRole,
    val current_location: CurrentLocation // nullable
)

data class CurrentLocation(
    val latitude: Double,
    val longitude: Double,
    val time: String
)

data class UserRole(
    val id: Int,
    val title: String,
    val status: Int,
    val background_color: String,
    val text_color: String
)
