package com.example.locationservice

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ReportsSummary(
    val totalDistanceKm: Double = 0.0,
    val personalDistanceKm: Double = 0.0,
    val businessDistanceKm: Double = 0.0,
    val totalCost: Double = 0.0,
    val personalCost: Double = 0.0,
    val businessCost: Double = 0.0,
    val totalDurationHours: Double = 0.0,
    val personalDurationHours: Double = 0.0,
    val businessDurationHours: Double = 0.0,
    val totalTrips: Int = 0,
    val personalTrips: Int = 0,
    val businessTrips: Int = 0,
    val hasProjected: Boolean = false,
    val sessions: List<SessionItem> = emptyList()
)

data class MonthlyReportItem(
    val yearMonth: String,
    val displayMonth: String,
    val carDescription: String?,
    val totalDistanceKm: Double,
    val personalDistanceKm: Double,
    val businessDistanceKm: Double,
    val totalDurationHours: Double,
    val totalCost: Double,
    val personalCost: Double,
    val businessCost: Double,
    val tripCount: Int,
    val hasProjected: Boolean
)

data class FuelSummary(
    val totalCost: Double = 0.0,
    val totalLiters: Double = 0.0,
    val refuelCount: Int = 0,
    val avgPricePerLiter: Double = 0.0,
    val avgKmPerLiter: Double = 0.0,
    val avgPricePerKm: Double = 0.0,
    val fuelLogs: List<FuelItem> = emptyList()
)

class ApiClient(private val context: Context) {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://travel-access.ddns.net/api"
    private val db = LocationDatabase.getDatabase(context)

    private suspend fun getApiKey(): String {
        return withContext(Dispatchers.IO) {
            db.settingDao().getSetting("api_key")?.value ?: ""
        }
    }

    private suspend fun <T> executeRequest(
        requestBuilder: Request.Builder,
        parser: (String) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(Exception("API Key not configured. Please set your API key in Settings."))
        }

        val request = requestBuilder
            .header("X-API-Key", apiKey)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Result.success(parser(bodyStr))
                } else {
                    val errMsg = try {
                        JSONObject(bodyStr).optString("error", "HTTP error ${response.code}")
                    } catch (e: Exception) {
                        "HTTP error ${response.code}: ${response.message}"
                    }
                    Result.failure(Exception(errMsg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- CARS ---
    suspend fun getCars(): Result<List<CarItem>> {
        val req = Request.Builder().url("$baseUrl/user/cars").get()
        return executeRequest(req) { body ->
            gson.fromJson(body, CarsResponse::class.java).cars ?: emptyList()
        }
    }

    suspend fun addCar(description: String, licensePlate: String): Result<Boolean> {
        val payload = JSONObject().apply {
            put("description", description)
            put("licensePlate", licensePlate)
        }.toString()
        val req = Request.Builder().url("$baseUrl/user/cars").post(payload.toRequestBody(jsonMediaType))
        return executeRequest(req) { body ->
            gson.fromJson(body, ApiResponse::class.java).success
        }
    }

    suspend fun deleteCar(carId: String): Result<Boolean> {
        val req = Request.Builder().url("$baseUrl/user/cars?carId=$carId").delete()
        return executeRequest(req) { body ->
            gson.fromJson(body, ApiResponse::class.java).success
        }
    }

    // --- BLUETOOTH ---
    suspend fun getBluetooth(): Result<List<BluetoothItem>> {
        val req = Request.Builder().url("$baseUrl/user/bluetooth").get()
        return executeRequest(req) { body ->
            gson.fromJson(body, BluetoothResponse::class.java).bluetooth ?: emptyList()
        }
    }

    suspend fun updateBluetooth(id: String, carId: String?, name: String, address: String, description: String): Result<Boolean> {
        val payload = JSONObject().apply {
            put("id", id)
            put("carId", carId ?: "")
            put("name", name)
            put("address", address)
            put("description", description)
        }.toString()
        val req = Request.Builder().url("$baseUrl/user/bluetooth").patch(payload.toRequestBody(jsonMediaType))
        return executeRequest(req) { body ->
            gson.fromJson(body, ApiResponse::class.java).success
        }
    }

    // --- SESSIONS ---
    suspend fun getSessions(
        page: Int = 1,
        limit: Int = 20,
        carId: String? = null,
        year: String? = null,
        month: String? = null,
        type: String? = null
    ): Result<SessionsResponse> {
        var url = "$baseUrl/sessions?page=$page&limit=$limit"
        if (!carId.isNullOrEmpty()) url += "&carId=$carId"
        if (!year.isNullOrEmpty() && year != "Any Year") url += "&year=$year"
        if (!month.isNullOrEmpty() && month != "Any Month") url += "&month=$month"
        if (!type.isNullOrEmpty() && type != "All Types") url += "&type=$type"
        try {
            val tz = TimeZone.getDefault().id
            url += "&tz=$tz"
        } catch (_: Exception) {}

        val req = Request.Builder().url(url).get()
        return executeRequest(req) { body ->
            gson.fromJson(body, SessionsResponse::class.java)
        }
    }

    suspend fun toggleSessionType(sessionId: String, newType: String): Result<Boolean> {
        val payload = JSONObject().apply {
            put("id", sessionId)
            put("type", newType)
        }.toString()
        val req = Request.Builder().url("$baseUrl/sessions").patch(payload.toRequestBody(jsonMediaType))
        return executeRequest(req) { body ->
            gson.fromJson(body, ApiResponse::class.java).success
        }
    }

    suspend fun endSession(sessionId: String, deviceId: String?): Result<Boolean> {
        val utcNow = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val payload = JSONObject().apply {
            put("id", sessionId)
            put("device_id", deviceId ?: LocationLoggingService.getDeviceAndroidId(context))
            put("timestamp_utc", utcNow)
        }.toString()
        val req = Request.Builder().url("$baseUrl/Session/end-session").post(payload.toRequestBody(jsonMediaType))
        return executeRequest(req) { body ->
            gson.fromJson(body, ApiResponse::class.java).success
        }
    }

    suspend fun deleteSession(sessionId: String): Result<Boolean> {
        val req = Request.Builder().url("$baseUrl/sessions?id=$sessionId").delete()
        return executeRequest(req) { body ->
            gson.fromJson(body, ApiResponse::class.java).success
        }
    }

    suspend fun addSession(
        deviceId: String,
        carId: String,
        startUtc: String,
        endUtc: String?,
        type: String
    ): Result<Boolean> {
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("carId", carId)
            put("startUtc", startUtc)
            put("sessionType", type)
            if (!endUtc.isNullOrEmpty()) {
                put("endUtc", endUtc)
            }
        }.toString()
        val req = Request.Builder().url("$baseUrl/sessions").post(payload.toRequestBody(jsonMediaType))
        return executeRequest(req) { body ->
            gson.fromJson(body, ApiResponse::class.java).success
        }
    }

    // --- FUEL ---
    suspend fun getFuel(): Result<List<FuelItem>> {
        val req = Request.Builder().url("$baseUrl/user/fuel").get()
        return executeRequest(req) { body ->
            gson.fromJson(body, FuelResponse::class.java).fuel ?: emptyList()
        }
    }

    suspend fun addFuel(carId: String, timestampUtc: String, totalValue: Double, liters: Double): Result<Boolean> {
        val formBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("carId", carId)
            .addFormDataPart("timestampIso", timestampUtc)
            .addFormDataPart("totalValue", totalValue.toString())
            .addFormDataPart("liters", liters.toString())
            .build()
        val req = Request.Builder().url("$baseUrl/user/fuel").post(formBody)
        return executeRequest(req) { body ->
            gson.fromJson(body, ApiResponse::class.java).success
        }
    }

    suspend fun deleteFuel(id: String): Result<Boolean> {
        val req = Request.Builder().url("$baseUrl/user/fuel?id=$id").delete()
        return executeRequest(req) { body ->
            gson.fromJson(body, ApiResponse::class.java).success
        }
    }

    // --- OTHER EXPENSES ---
    suspend fun getExpenses(): Result<List<ExpenseItem>> {
        val req = Request.Builder().url("$baseUrl/user/other-expenses").get()
        return executeRequest(req) { body ->
            gson.fromJson(body, ExpensesResponse::class.java).expenses ?: emptyList()
        }
    }

    suspend fun addExpense(
        carId: String,
        expenseTypeId: String,
        expenseDateIso: String,
        amount: Double,
        description: String
    ): Result<Boolean> {
        val formBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("carId", carId)
            .addFormDataPart("expenseTypeId", expenseTypeId)
            .addFormDataPart("expenseDateIso", expenseDateIso)
            .addFormDataPart("amount", amount.toString())
            .addFormDataPart("description", description)
            .build()
        val req = Request.Builder().url("$baseUrl/user/other-expenses").post(formBody)
        return executeRequest(req) { body ->
            gson.fromJson(body, ApiResponse::class.java).success
        }
    }

    suspend fun deleteExpense(id: String): Result<Boolean> {
        val req = Request.Builder().url("$baseUrl/user/other-expenses?id=$id").delete()
        return executeRequest(req) { body ->
            gson.fromJson(body, ApiResponse::class.java).success
        }
    }

    // --- EXPENSE TYPES ---
    suspend fun getExpenseTypes(): Result<List<ExpenseTypeItem>> {
        val req = Request.Builder().url("$baseUrl/user/expense-types").get()
        return executeRequest(req) { body ->
            gson.fromJson(body, ExpenseTypesResponse::class.java).types ?: emptyList()
        }
    }

    suspend fun addExpenseType(name: String, description: String = ""): Result<Boolean> {
        val payload = JSONObject().apply {
            put("name", name)
            put("description", description)
        }.toString()
        val req = Request.Builder().url("$baseUrl/user/expense-types").post(payload.toRequestBody(jsonMediaType))
        return executeRequest(req) { body ->
            gson.fromJson(body, ApiResponse::class.java).success
        }
    }

    suspend fun deleteExpenseType(id: String): Result<Boolean> {
        val req = Request.Builder().url("$baseUrl/user/expense-types?id=$id").delete()
        return executeRequest(req) { body ->
            gson.fromJson(body, ApiResponse::class.java).success
        }
    }


    // --- REPORTS ---
    suspend fun getReports(
        carId: String? = null,
        year: String? = null,
        month: String? = null,
        type: String? = null
    ): Result<ReportsSummary> {
        var url = "$baseUrl/sessions?page=1&limit=5000"
        if (!carId.isNullOrEmpty()) url += "&carId=$carId"
        if (!year.isNullOrEmpty() && year != "Any Year") url += "&year=$year"
        if (!month.isNullOrEmpty() && month != "Any Month") url += "&month=$month"
        if (!type.isNullOrEmpty() && type != "All Types") url += "&type=$type"
        try {
            val tz = TimeZone.getDefault().id
            url += "&tz=$tz"
        } catch (_: Exception) {}

        val req = Request.Builder().url(url).get()
        return executeRequest(req) { body ->
            val resp = gson.fromJson(body, SessionsResponse::class.java)
            val items = resp.data ?: emptyList()

            var totalDist = 0.0
            var personalDist = 0.0
            var businessDist = 0.0
            var totalCost = 0.0
            var personalCost = 0.0
            var businessCost = 0.0
            var totalDuration = 0.0
            var personalDuration = 0.0
            var businessDuration = 0.0
            var personalTrips = 0
            var businessTrips = 0
            var hasProjected = false

            for (s in items) {
                val d = s.distance ?: 0.0
                val c = s.cost ?: 0.0
                var dur = s.timeTraveled ?: 0.0
                if (dur <= 0.0 && !s.startTime.isNullOrEmpty()) {
                    try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }
                        val cleanStart = s.startTime.replace("Z", "")
                        val startD = sdf.parse(cleanStart)
                        val endD = if (!s.endTime.isNullOrEmpty()) {
                            sdf.parse(s.endTime.replace("Z", ""))
                        } else Date()
                        if (startD != null && endD != null) {
                            val diffMs = (endD.time - startD.time).coerceAtLeast(0)
                            dur = diffMs / (1000.0 * 60 * 60)
                        }
                    } catch (_: Exception) {}
                }

                totalDist += d
                totalCost += c
                totalDuration += dur

                if (s.valueConfirmed == "N") {
                    hasProjected = true
                }

                if (s.type == "B") {
                    businessDist += d
                    businessCost += c
                    businessDuration += dur
                    businessTrips++
                } else {
                    personalDist += d
                    personalCost += c
                    personalDuration += dur
                    personalTrips++
                }
            }

            ReportsSummary(
                totalDistanceKm = totalDist,
                personalDistanceKm = personalDist,
                businessDistanceKm = businessDist,
                totalCost = totalCost,
                personalCost = personalCost,
                businessCost = businessCost,
                totalDurationHours = totalDuration,
                personalDurationHours = personalDuration,
                businessDurationHours = businessDuration,
                totalTrips = items.size,
                personalTrips = personalTrips,
                businessTrips = businessTrips,
                hasProjected = hasProjected,
                sessions = items
            )
        }
    }

    suspend fun getFuelSummary(carId: String? = null, year: String? = null): Result<FuelSummary> {
        return getFuel().map { allFuel ->
            var filtered = allFuel
            if (!carId.isNullOrEmpty()) {
                filtered = filtered.filter { it.carId == carId }
            }
            if (!year.isNullOrEmpty() && year != "Any Year") {
                filtered = filtered.filter {
                    val dateStr = it.timestampUtc ?: ""
                    dateStr.startsWith(year)
                }
            }
            filtered = filtered.sortedByDescending { it.timestampUtc ?: "" }

            val totalCost = filtered.sumOf { it.totalValue ?: 0.0 }
            val totalLiters = filtered.sumOf { it.liters ?: 0.0 }
            val refuelCount = filtered.size

            val efficiencyLogs = filtered.filter { (it.totalKilometers ?: 0.0) > 0.0 }
            val totalKmForEff = efficiencyLogs.sumOf { it.totalKilometers ?: 0.0 }
            val totalLitersForEff = efficiencyLogs.sumOf { it.liters ?: 0.0 }
            val totalCostForEff = efficiencyLogs.sumOf { it.totalValue ?: 0.0 }

            val avgKmPerLiter = if (totalLitersForEff > 0) totalKmForEff / totalLitersForEff else 0.0
            val avgPricePerKm = if (totalKmForEff > 0) totalCostForEff / totalKmForEff else 0.0
            val avgPricePerLiter = if (totalLiters > 0) totalCost / totalLiters else 0.0

            FuelSummary(
                totalCost = totalCost,
                totalLiters = totalLiters,
                refuelCount = refuelCount,
                avgPricePerLiter = avgPricePerLiter,
                avgKmPerLiter = avgKmPerLiter,
                avgPricePerKm = avgPricePerKm,
                fuelLogs = filtered
            )
        }
    }

    // --- GPS DATA ---
    suspend fun getGpsData(startDate: String, endDate: String, deviceId: String? = null): Result<List<GpsLocationPoint>> {
        var url = "$baseUrl/gps-data?startDate=$startDate&endDate=$endDate"
        if (!deviceId.isNullOrEmpty()) {
            url += "&deviceId=$deviceId"
        }
        val req = Request.Builder().url(url).get()
        return executeRequest(req) { body ->
            gson.fromJson(body, GpsDataResponse::class.java).data ?: emptyList()
        }
    }
}

