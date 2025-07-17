package com.driverapp.networkApi

import com.driverapp.models.MyTripListData
import com.driverapp.models.OffersData
import com.driverapp.models.StoreForFaitTripBody
import com.driverapp.networkApi.models.DropOffTripAddressBody
import com.driverapp.networkApi.models.GenericResponse
import com.driverapp.networkApi.models.LoginBody
import com.driverapp.networkApi.models.LoginResponse
import com.driverapp.networkApi.models.MyTripData
import com.driverapp.networkApi.models.OnlineStatusBody
import com.driverapp.networkApi.models.PickupTripAddressBody
import com.driverapp.networkApi.models.ProfileResponse
import com.driverapp.networkApi.models.SetLocationBody
import com.driverapp.networkApi.models.ShiftInfo
import com.driverapp.networkApi.models.TaximeterStatusBody
import com.driverapp.networkApi.models.UpdatePasswordBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface ApiServices {

    @POST("/api/v1/auth/login")
    fun login(
        @Body user: LoginBody
    ): Call<LoginResponse>

    @POST("/api/v1/auth/logout")
    fun logout(
        @Header("Authorization") token: String
    ): Call<ResponseBody>

    @GET("/api/v1/profile/details")
    fun getProfileDetails(
        @Header("Authorization") token: String
    ): Call<ProfileResponse>

    @PUT("/api/v1/profile/password/update")
    @FormUrlEncoded
    fun updatePassword(
        @Header("Authorization") token: String,
        @Body updatePassword: UpdatePasswordBody
    ): Call<ResponseBody>

    @POST("/api/v1/profile/location/set")
    fun setLocation(
        @Header("Authorization") token: String,
        @Body setLocation: SetLocationBody
    ): Call<GenericResponse>

    @POST("/api/v1/profile/online-status/set")
    fun onlineStatus(
        @Header("Authorization") token: String,
        @Body onlineStatusBody: OnlineStatusBody
    ): Call<ResponseBody>

    @POST("/api/v1/profile/taximeter-status/set")
    fun taximeterStatus(
        @Header("Authorization") token: String,
        @Body taximeterStatusBody: TaximeterStatusBody
    ): Call<ResponseBody>

    @POST("/api/v1/my-trips/{tripId}/accept")
    fun acceptTrip(
        @Header("Authorization") token: String,
        @Path("tripId") tripId: String
    ): Call<MyTripData>

    @POST("/api/v1/my-trips/{tripId}/start")
    fun startTrip(
        @Header("Authorization") token: String,
        @Path("tripId") tripId: String,
        @Body tripAddressBody: PickupTripAddressBody
    ): Call<MyTripData>

    @POST("/api/v1/my-trips/{tripId}/complete")
    fun completeTrip(
        @Header("Authorization") token: String,
        @Path("tripId") tripId: String,
        @Body tripAddressBody: DropOffTripAddressBody
    ): Call<MyTripData>

    @POST("/api/v1/my-trips/store-forfait-trip")
    fun requestStoreForFaitTrip(
        @Header("Accept") accept: String,
        @Header("Authorization") token: String,
        @Body storeFroFaitTripBody: StoreForFaitTripBody
    ): Call<MyTripData>

    @POST("/api/v1/broadcast-events/{broadcastEventId}/mark-as-received")
    fun markAsReceived(
        @Header("Authorization") token: String,
        @Path("broadcastEventId") broadcastEventId: String,
    ): Call<ResponseBody>

    @POST("api/v1/my-shifts/current/close")
    fun sendShiftInfo(
        @Header("Authorization") token: String,
        @Body shiftInfo: ShiftInfo
    ): Call<ResponseBody>

    @GET("/api/v1/my-trips/{tripId}")
    fun getShowTripDetails(
        @Header("Authorization") token: String,
        @Path("tripId") tripId: Int
    ): Call<MyTripData>


    @GET("/api/v1/my-trips")
    fun myTrips(
        @Header("Authorization") token: String,
        // @Path("all") tripId: Int
    ): Call<MyTripListData>

    @GET("/api/v1/offers")
    fun getOffers(
        @Header("Authorization") token: String,
        // @Path("all") tripId: Int
    ): Call<OffersData>
}