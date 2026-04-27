package com.example.locationservice

import com.google.gson.annotations.SerializedName

data class SessionResponse(
    val success: Boolean,
    val data: List<Session>?,
    val pagination: Pagination?
)

data class Pagination(
    val total: Int,
    val page: Int,
    val limit: Int,
    val totalPages: Int
)

data class Session(
    val id: String,
    @SerializedName("device_id", alternate = ["deviceId"])
    val deviceId: String?,
    @SerializedName("car_id", alternate = ["carId"])
    val carId: String?,
    @SerializedName("start_time", alternate = ["startTime"])
    val startTime: String?,
    @SerializedName("end_time", alternate = ["endTime"])
    val endTime: String?,
    @SerializedName("type")
    val type: String?,
    val distance: Double?,
    val cost: Double?,
    val timeTraveled: Double?,
    val description: String?
)
