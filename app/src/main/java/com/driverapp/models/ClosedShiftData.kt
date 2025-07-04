package com.driverapp.models

data class ClosedShiftData(
    val fareAmount: Long?,
    val driverId: Long?,
    val vehicleId: String?,
    val shiftStartDate: String?,  // formatted date string
    val shiftEndDate: String?     // formatted date string
)