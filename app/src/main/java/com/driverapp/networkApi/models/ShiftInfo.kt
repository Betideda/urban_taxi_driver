package com.driverapp.networkApi.models

data class ShiftInfo(
    // General Information
    val taximeterShiftId: String,
    // Start Info
    val startInfoTripsQuantity: String,
    val startInfoUnitsQuantity: String,
    val startInfoTotalDistance: String,
    val startInfoHiredDistance: String,
    val startInfoForHireDistance: String,
    val startInfoBlackTripDistance: String,
    val startInfoWaitingTime: String,
    val startInfoFareAmount: String,
    val startInfoExtrasAmount: String,
    val startInfoCreditCardAmount: String,
    val startInfoTaxAmount: String,
    val startInfoTipsAmount: String,
    // End Info
    val endInfoTripsQuantity: String,
    val endInfoUnitsQuantity: String,
    val endInfoTotalDistance: String,
    val endInfoHiredDistance: String,
    val endInfoForHireDistance: String,
    val endInfoBlackTripDistance: String,
    val endInfoWaitingTime: String,
    val endInfoFareAmount: String,
    val endInfoExtrasAmount: String,
    val endInfoCreditCardAmount: String,
    val endInfoTaxAmount: String,
    val endInfoTipsAmount: String,
    // Timestamps
    val shiftStartedAt: String,
    val shiftEndedAt: String,
)