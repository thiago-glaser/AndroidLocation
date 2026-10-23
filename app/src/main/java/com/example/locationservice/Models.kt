package com.example.locationservice

import com.google.gson.annotations.SerializedName

// Car models
data class CarItem(
    @SerializedName("ID") val id: String,
    @SerializedName("DESCRIPTION") val description: String?,
    @SerializedName("LICENSE_PLATE") val licensePlate: String?
)

data class CarsResponse(
    val success: Boolean,
    val cars: List<CarItem>?,
    val error: String?
)

// Bluetooth models
data class BluetoothItem(
    @SerializedName("ID") val id: String,
    @SerializedName("NAME") val name: String?,
    @SerializedName("DESCRIPTION") val description: String?,
    @SerializedName("ADDRESS") val address: String?,
    @SerializedName("CAR_ID") val carId: String?,
    @SerializedName("CAR_DESCRIPTION") val carDescription: String?
)

data class BluetoothResponse(
    val success: Boolean,
    val bluetooth: List<BluetoothItem>?,
    val error: String?
)

// Session models
data class SessionItem(
    val id: String,
    @SerializedName("device_id", alternate = ["deviceId"]) val deviceId: String?,
    @SerializedName("car_id", alternate = ["carId"]) val carId: String?,
    @SerializedName("start_time", alternate = ["startTime"]) val startTime: String?,
    @SerializedName("end_time", alternate = ["endTime"]) val endTime: String?,
    val type: String?,
    val distance: Double?,
    val cost: Double?,
    val description: String?,
    @SerializedName("location_start", alternate = ["locationStart"]) val locationStart: String?,
    @SerializedName("location_end", alternate = ["locationEnd"]) val locationEnd: String?
)

data class PaginationInfo(
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 20,
    val totalPages: Int = 1
)

data class SessionsResponse(
    val success: Boolean,
    val data: List<SessionItem>?,
    val pagination: PaginationInfo?,
    val error: String?
)

// Fuel models
data class FuelItem(
    val id: String,
    val carId: String?,
    val timestampUtc: String?,
    val totalValue: Double?,
    val liters: Double?,
    val totalKilometers: Double?,
    val kilometerPerLiter: Double?,
    val pricePerKilometer: Double?,
    val carLicensePlate: String?,
    val carDescription: String?,
    val hasReceipt: Boolean?
)

data class FuelResponse(
    val success: Boolean,
    val fuel: List<FuelItem>?,
    val error: String?
)

// Generic API response
data class ApiResponse(
    val success: Boolean,
    val message: String?,
    val error: String?
)
