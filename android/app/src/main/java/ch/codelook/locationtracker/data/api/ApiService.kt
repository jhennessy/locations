package ch.codelook.locationtracker.data.api

import ch.codelook.locationtracker.data.api.models.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // Auth
    @POST("api/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    @POST("api/register")
    suspend fun register(@Body request: RegisterRequest): TokenResponse

    @POST("api/change-password")
    suspend fun changePassword(@Body request: ChangePasswordRequest): Map<String, String>

    // Devices
    @GET("api/devices")
    suspend fun getDevices(): List<DeviceInfo>

    @POST("api/devices")
    suspend fun createDevice(@Body request: DeviceCreateRequest): DeviceInfo

    @DELETE("api/devices/{deviceId}")
    suspend fun deleteDevice(@Path("deviceId") deviceId: Int): Response<Unit>

    // Locations
    @POST("api/locations")
    suspend fun uploadLocations(@Body batch: LocationBatch): BatchResponse

    // Visits
    @GET("api/visits/{deviceId}")
    suspend fun getVisits(
        @Path("deviceId") deviceId: Int,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): List<VisitInfo>

    // Places
    @GET("api/places/frequent")
    suspend fun getFrequentPlaces(@Query("limit") limit: Int = 20): List<PlaceInfo>

    // Positions
    @POST("api/positions")
    suspend fun updatePositions(@Body batch: PositionBatch): PositionUpdateResponse

    @GET("api/positions")
    suspend fun getAllPositions(): List<ServerPosition>

    @POST("api/positions/relay")
    suspend fun relayPositions(@Body batch: RelayBatch): RelayResponse

    // Logout
    @POST("api/logout")
    suspend fun logout(): Map<String, String>
}
