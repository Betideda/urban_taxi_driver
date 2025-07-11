package com.driverapp.models

class LocationData (
    var name: String, // Stores the human-readable address name for the current location
    var lat: Double, // Stores the latitude of the current location
    var lng: Double, // Stores the longitude of the current location
) {
    fun setCoordinates(latitude: Double, longitude: Double) {
        this.lat = latitude
        this.lng = longitude
    }
}
