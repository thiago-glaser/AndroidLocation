package com.example.locationservice

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class OtherExpenseAllocation(
    val expenseTypeName: String,
    val total: Double = 0.0,
    val personal: Double = 0.0,
    val business: Double = 0.0
)

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
    val sessions: List<SessionItem> = emptyList(),
    val otherExpenses: List<OtherExpenseAllocation> = emptyList()
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

    suspend fun updateSessionMetrics(
        sessionId: String,
        cost: Double,
        distance: Double,
        timeTraveled: Double,
        valueConfirmed: String
    ): Result<Boolean> {
        val payload = JSONObject().apply {
            put("id", sessionId)
            put("cost", cost)
            put("distance", distance)
            put("timeTraveled", timeTraveled)
            put("valueConfirmed", valueConfirmed)
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

    suspend fun calculateFuel(id: String): Result<Boolean> {
        val emptyBody = "".toRequestBody(jsonMediaType)
        val req = Request.Builder().url("$baseUrl/user/fuel/$id/calculate").post(emptyBody)
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

    // --- GPS & DISTANCE CALCULATION HELPERS (Matching TravelAccess Web) ---
    fun parseUtcDate(dateVal: String?): Date? {
        if (dateVal.isNullOrBlank()) return null
        return try {
            var clean = dateVal.trim()
            if (!clean.contains("T")) clean = clean.replace(" ", "T")
            clean = clean.removeSuffix("Z").substringBefore("+")

            val parts = clean.split(".")
            val baseStr = parts[0]
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val baseDate = sdf.parse(baseStr) ?: return null
            val ms = if (parts.size > 1) {
                parts[1].take(3).padEnd(3, '0').toLongOrNull() ?: 0L
            } else 0L
            Date(baseDate.time + ms)
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0 // Earth's radius in meters (matches web reports/page.js)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun filterLocationsByDistance(
        locations: List<GpsLocationPoint>,
        minDistance: Double = 10.0
    ): List<GpsLocationPoint> {
        if (locations.size <= 1) return locations
        val filtered = mutableListOf(locations[0])
        for (i in 1 until locations.size) {
            val lastFiltered = filtered.last()
            val distance = calculateDistanceMeters(
                lastFiltered.lat,
                lastFiltered.lng,
                locations[i].lat,
                locations[i].lng
            )
            if (distance >= minDistance) {
                filtered.add(locations[i])
            }
        }
        return filtered
    }

    private fun calculateTotalDistanceMeters(locations: List<GpsLocationPoint>): Double {
        if (locations.size <= 1) return 0.0
        var totalDistance = 0.0
        for (i in 1 until locations.size) {
            totalDistance += calculateDistanceMeters(
                locations[i - 1].lat,
                locations[i - 1].lng,
                locations[i].lat,
                locations[i].lng
            )
        }
        return totalDistance
    }

    // --- REPORTS ---
    suspend fun getReports(
        carId: String? = null,
        year: String? = null,
        month: String? = null,
        type: String? = null,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Result<ReportsSummary> = withContext(Dispatchers.IO) {
        var url = "$baseUrl/sessions?page=1&limit=10000"
        if (!carId.isNullOrEmpty()) url += "&carId=$carId"
        if (!year.isNullOrEmpty() && year != "Any Year") url += "&year=$year"
        if (!month.isNullOrEmpty() && month != "Any Month") url += "&month=$month"
        if (!type.isNullOrEmpty() && type != "All Types") url += "&type=$type"
        try {
            val tz = TimeZone.getDefault().id
            url += "&tz=$tz"
        } catch (_: Exception) {}

        val sessionsRes = executeRequest(Request.Builder().url(url).get()) { body ->
            gson.fromJson(body, SessionsResponse::class.java)
        }

        val sessionsResp = sessionsRes.getOrElse { return@withContext Result.failure(it) }
        if (!sessionsResp.success) {
            return@withContext Result.failure(Exception(sessionsResp.error ?: "Failed to fetch sessions"))
        }

        val sessionsList = sessionsResp.data ?: emptyList()
        if (sessionsList.isEmpty()) {
            return@withContext Result.success(ReportsSummary())
        }

        // Fetch fuel logs and other expenses in parallel (same as web reports/page.js & reports/monthly/page.js)
        val fuelData = coroutineScope {
            val fuelDeferred = async { getFuel().getOrDefault(emptyList()) }
            fuelDeferred.await()
        }
        val otherExpensesData = getExpenses().getOrDefault(emptyList())

        val totalSessionsCount = sessionsList.size
        onProgress?.invoke(0, totalSessionsCount)

        // Pre-group fuel logs by carId sorted ascending by timestampUtc
        val fuelByCar = fuelData
            .filter { !it.carId.isNullOrEmpty() }
            .groupBy { it.carId!!.trim() }
            .mapValues { (_, logs) ->
                logs.sortedBy { parseUtcDate(it.timestampUtc)?.time ?: 0L }
            }

        val batchSize = 5
        val processed = mutableListOf<SessionItem>()
        val unzonedUtcFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        for (i in sessionsList.indices step batchSize) {
            val batch = sessionsList.subList(i, minOf(i + batchSize, sessionsList.size))
            val batchResults = coroutineScope {
                batch.map { session ->
                    async(Dispatchers.IO) {
                        try {
                            val start = parseUtcDate(session.startTime)
                            val end = parseUtcDate(session.endTime) ?: Date()

                            var distanceKm = session.distance ?: 0.0
                            var durationHours = session.timeTraveled ?: 0.0
                            var sessionCost = session.cost ?: 0.0
                            var valueConfirmed = session.valueConfirmed ?: "N"

                            // 1. Fetch GPS data per session if distance or duration is missing/zero
                            if (distanceKm <= 0.0 || durationHours <= 0.0) {
                                val startStr = (session.startTime ?: "").trim().removeSuffix("Z")
                                val endStr = if (!session.endTime.isNullOrEmpty()) {
                                    session.endTime.trim().removeSuffix("Z")
                                } else {
                                    unzonedUtcFmt.format(end)
                                }

                                if (start != null && startStr.isNotEmpty()) {
                                    val gpsRes = getGpsData(startStr, endStr, session.deviceId)
                                    if (gpsRes.isSuccess) {
                                        val sessionLocations = (gpsRes.getOrNull() ?: emptyList())
                                            .sortedBy { parseUtcDate(it.date)?.time ?: 0L }

                                        val filteredLocs = filterLocationsByDistance(sessionLocations, 10.0)
                                        val distanceMeters = calculateTotalDistanceMeters(filteredLocs)
                                        val durationMs = (end.time - start.time).coerceAtLeast(0L)

                                        distanceKm = distanceMeters / 1000.0
                                        durationHours = durationMs / (1000.0 * 60.0 * 60.0)

                                        // Junk session filtering: If no distance AND very short duration, exclude it
                                        if (distanceMeters < 1.0 && durationMs < 1000L) {
                                            return@async null
                                        }
                                    } else {
                                        distanceKm = 0.0
                                        durationHours = 0.0
                                    }
                                }
                            }

                            // 2. Re-evaluate cost if not confirmed or if distance was missing/recalculated
                            val needsCostEval = valueConfirmed == "N" || session.distance == null || session.distance <= 0.0
                            if (needsCostEval) {
                                var sessionPricePerKm = 0.0
                                val carKey = session.carId?.trim() ?: ""
                                val carFuelLogs = fuelByCar[carKey] ?: emptyList()

                                if (carFuelLogs.isNotEmpty()) {
                                    val validLogs = carFuelLogs.filter { (it.pricePerKilometer ?: 0.0) > 0.0 }

                                    // Efficiency for session S is stored in fuel record F2 that comes AT OR AFTER session S end
                                    val applicableLog = carFuelLogs.find { f ->
                                        val fTime = parseUtcDate(f.timestampUtc)?.time ?: 0L
                                        fTime >= end.time
                                    }

                                    if (applicableLog != null && (applicableLog.pricePerKilometer ?: 0.0) > 0.0) {
                                        sessionPricePerKm = applicableLog.pricePerKilometer ?: 0.0
                                        valueConfirmed = "Y"
                                    } else if (validLogs.isNotEmpty()) {
                                        sessionPricePerKm = if (applicableLog == null) {
                                            validLogs.last().pricePerKilometer ?: 0.0
                                        } else {
                                            validLogs.first().pricePerKilometer ?: 0.0
                                        }
                                        valueConfirmed = "N"
                                    }
                                }

                                sessionCost = distanceKm * sessionPricePerKm

                                // Persist updated metrics back to server if session has ended
                                if (!session.endTime.isNullOrEmpty()) {
                                    try {
                                        updateSessionMetrics(
                                            sessionId = session.id,
                                            cost = sessionCost,
                                            distance = distanceKm,
                                            timeTraveled = durationHours,
                                            valueConfirmed = valueConfirmed
                                        )
                                    } catch (_: Exception) {}
                                }
                            }

                            session.copy(
                                distance = distanceKm,
                                timeTraveled = durationHours,
                                cost = sessionCost,
                                valueConfirmed = valueConfirmed
                            )
                        } catch (_: Exception) {
                            session.copy(
                                distance = session.distance ?: 0.0,
                                timeTraveled = session.timeTraveled ?: 0.0,
                                cost = session.cost ?: 0.0
                            )
                        }
                    }
                }.awaitAll()
            }

            processed.addAll(batchResults.filterNotNull())
            onProgress?.invoke(minOf(totalSessionsCount, i + batchSize), totalSessionsCount)
        }

        // 3. Calculate Totals and Breakdown
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

        for (s in processed) {
            val d = s.distance ?: 0.0
            val c = s.cost ?: 0.0
            val dur = s.timeTraveled ?: 0.0

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
            } else if (s.type == "P") {
                personalDist += d
                personalCost += c
                personalDuration += dur
                personalTrips++
            } else {
                personalDist += d
                personalCost += c
                personalDuration += dur
                personalTrips++
            }
        }

        // 4. Calculate Other Expenses proportional allocation (matching reports/monthly/page.js)
        val localCal = Calendar.getInstance()
        val reportYears: Set<String> = if (!year.isNullOrEmpty() && year != "Any Year") {
            setOf(year)
        } else {
            processed.mapNotNull { s ->
                parseUtcDate(s.startTime)?.let { d ->
                    localCal.time = d
                    localCal.get(Calendar.YEAR).toString()
                }
            }.toSet()
        }

        val expenseTotalsByType = linkedMapOf<String, Double>()
        for (exp in otherExpensesData) {
            val matchesCar = if (!carId.isNullOrEmpty()) {
                exp.carId?.trim() == carId.trim()
            } else true

            val expYear = parseUtcDate(exp.expenseDate)?.let { d ->
                localCal.time = d
                localCal.get(Calendar.YEAR).toString()
            } ?: exp.expenseDate?.take(4)

            val matchesYear = expYear != null && reportYears.contains(expYear)
            if (matchesCar && matchesYear) {
                val typeName = exp.expenseTypeName?.takeIf { it.isNotBlank() } ?: "Other"
                expenseTotalsByType[typeName] = (expenseTotalsByType[typeName] ?: 0.0) + (exp.amount ?: 0.0)
            }
        }

        val otherExpensesAllocations = expenseTotalsByType.map { (typeName, totalAmt) ->
            OtherExpenseAllocation(
                expenseTypeName = typeName,
                total = totalAmt,
                personal = if (totalDist > 0.0) totalAmt * (personalDist / totalDist) else 0.0,
                business = if (totalDist > 0.0) totalAmt * (businessDist / totalDist) else 0.0
            )
        }

        Result.success(
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
                totalTrips = processed.size,
                personalTrips = personalTrips,
                businessTrips = businessTrips,
                hasProjected = hasProjected,
                sessions = processed,
                otherExpenses = otherExpensesAllocations
            )
        )
    }

    suspend fun getFuelSummary(carId: String? = null, year: String? = null): Result<FuelSummary> {
        return getFuel().map { allFuel ->
            var filtered = allFuel
            if (!carId.isNullOrEmpty()) {
                filtered = filtered.filter { it.carId?.trim() == carId.trim() }
            }
            if (!year.isNullOrEmpty() && year != "Any Year") {
                val localCal = Calendar.getInstance()
                filtered = filtered.filter {
                    val d = parseUtcDate(it.timestampUtc)
                    if (d != null) {
                        localCal.time = d
                        localCal.get(Calendar.YEAR).toString() == year
                    } else {
                        (it.timestampUtc ?: "").startsWith(year)
                    }
                }
            }
            filtered = filtered.sortedByDescending { parseUtcDate(it.timestampUtc)?.time ?: 0L }

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
