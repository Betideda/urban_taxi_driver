package com.driverapp.models

data class OffersData (
    val data: List<Zone>
)
data class Zone(
    val id: Int,
    val title: String,
    val description: String?,
    val status: Int,
   /* val start_polygon: Polygon,
    val end_polygon: Polygon,*/
    val price: String,
    val created_at: String,
    val updated_at: String
)

data class Polygon(
    val type: String,
    val coordinates: List<List<List<Double>>>
)