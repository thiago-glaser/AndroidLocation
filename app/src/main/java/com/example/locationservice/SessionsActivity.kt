package com.example.locationservice

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivitySessionsBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SessionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionsBinding
    private lateinit var apiClient: ApiClient
    private var carsList = listOf<CarItem>()
    private var currentPage = 1
    private var totalPages = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddSession.setOnClickListener { showAddSessionDialog() }

        setupFilters()
        setupPagination()

        loadCarsAndSessions()
    }

    private fun setupFilters() {
        val years = mutableListOf("Any Year")
        for (y in 2024..2040) {
            years.add(y.toString())
        }
        binding.spinnerYearFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, years)

        binding.btnApplyFilters.setOnClickListener {
            currentPage = 1
            loadSessions()
        }
    }

    private fun setupPagination() {
        binding.btnPrevPage.setOnClickListener {
            if (currentPage > 1) {
                currentPage--
                loadSessions()
            }
        }
        binding.btnNextPage.setOnClickListener {
            if (currentPage < totalPages) {
                currentPage++
                loadSessions()
            }
        }
        updatePaginationButtons()
    }

    private fun updatePaginationButtons() {
        val hasPrev = currentPage > 1
        val hasNext = currentPage < totalPages

        binding.btnPrevPage.isEnabled = hasPrev
        binding.btnPrevPage.backgroundTintList = ColorStateList.valueOf(
            if (hasPrev) Color.parseColor("#2563EB") else Color.parseColor("#E2E8F0")
        )
        binding.btnPrevPage.setTextColor(
            if (hasPrev) Color.WHITE else Color.parseColor("#64748B")
        )

        binding.btnNextPage.isEnabled = hasNext
        binding.btnNextPage.backgroundTintList = ColorStateList.valueOf(
            if (hasNext) Color.parseColor("#2563EB") else Color.parseColor("#E2E8F0")
        )
        binding.btnNextPage.setTextColor(
            if (hasNext) Color.WHITE else Color.parseColor("#64748B")
        )
    }

    private fun loadCarsAndSessions() {
        lifecycleScope.launch {
            val carsRes = apiClient.getCars()
            carsList = carsRes.getOrDefault(emptyList())

            val carNames = mutableListOf("All Cars")
            carsList.forEach { car ->
                carNames.add(car.description ?: car.licensePlate ?: "Car #${car.id}")
            }
            binding.spinnerCarFilter.adapter = ArrayAdapter(
                this@SessionsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                carNames
            )

            loadSessions()
        }
    }

    private fun loadSessions() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE
        binding.containerSessions.removeAllViews()

        val selectedCarIdx = binding.spinnerCarFilter.selectedItemPosition
        val selectedCarId = if (selectedCarIdx > 0 && selectedCarIdx <= carsList.size) {
            carsList[selectedCarIdx - 1].id
        } else null

        val selectedYear = binding.spinnerYearFilter.selectedItem?.toString()

        lifecycleScope.launch {
            val result = apiClient.getSessions(
                page = currentPage,
                limit = 20,
                carId = selectedCarId,
                year = selectedYear
            )
            binding.progressBar.visibility = View.GONE

            result.onSuccess { resp ->
                val sessions = resp.data ?: emptyList()
                if (sessions.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                } else {
                    sessions.forEach { session ->
                        addSessionView(session)
                    }
                }

                val pag = resp.pagination
                if (pag != null) {
                    currentPage = pag.page
                    totalPages = pag.totalPages.coerceAtLeast(1)
                    binding.tvPageInfo.text = "Page $currentPage / $totalPages"
                    updatePaginationButtons()
                }
            }.onFailure { err ->
                Toast.makeText(this@SessionsActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                binding.tvEmptyState.apply {
                    text = "Failed to load trips: ${err.message}"
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun parseDate(dateStr: String?): String {
        if (dateStr.isNullOrEmpty()) return "Active Now"
        return try {
            val cleanStr = dateStr.substringBefore("Z").substringBefore(".")
            val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val outFmt = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
            val date = inFmt.parse(cleanStr)
            if (date != null) outFmt.format(date) else dateStr
        } catch (e: Exception) {
            dateStr
        }
    }

    private fun addSessionView(session: SessionItem) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            elevation = 2f
            setPadding(24, 20, 24, 20)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                openSessionMap(session)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        // Top Row: Car Title & Type Badge
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(this).apply {
            text = session.description ?: carsList.find { it.id == session.carId }?.description ?: "Car #${session.carId}"
            textSize = 16f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val isBusiness = session.type == "B"
        val tvBadge = TextView(this).apply {
            text = if (isBusiness) "Business" else "Personal"
            textSize = 12f
            setPadding(20, 8, 20, 8)
            setTextColor(if (isBusiness) Color.parseColor("#B45309") else Color.parseColor("#1D4ED8"))
            background = GradientDrawable().apply {
                setColor(if (isBusiness) Color.parseColor("#FEF3C7") else Color.parseColor("#DBEAFE"))
                cornerRadius = 24f
            }
            setOnClickListener {
                toggleType(session)
            }
        }

        topRow.addView(tvTitle)
        topRow.addView(tvBadge)

        // Middle: Times
        val tvTimes = TextView(this).apply {
            val start = parseDate(session.startTime)
            val end = parseDate(session.endTime)
            text = "Start: $start\nEnd: $end"
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 8, 0, 8)
        }

        // Stats: Distance & Cost
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvStats = TextView(this).apply {
            val dist = String.format(Locale.US, "%.2f km", session.distance ?: 0.0)
            val cost = String.format(Locale.US, "$%.2f", session.cost ?: 0.0)
            text = "Distance: $dist | Cost: $cost"
            textSize = 14f
            setTextColor(Color.parseColor("#059669"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        statsRow.addView(tvStats)

        // Active End Session Button
        if (session.endTime.isNullOrEmpty()) {
            val btnEnd = Button(this).apply {
                text = "End Trip"
                textSize = 12f
                setTextColor(Color.parseColor("#DC2626"))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { endSession(session) }
            }
            statsRow.addView(btnEnd)
        }

        // Delete Button
        val btnDelete = Button(this).apply {
            text = "Delete"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { confirmDeleteSession(session) }
        }
        statsRow.addView(btnDelete)

        // Map route hint
        val tvMapHint = TextView(this).apply {
            text = "🗺️ Tap card to view route on OpenStreetMap"
            textSize = 11f
            setTextColor(Color.parseColor("#3B82F6"))
            setPadding(0, 4, 0, 0)
        }

        card.addView(topRow)
        card.addView(tvTimes)
        card.addView(statsRow)
        card.addView(tvMapHint)

        binding.containerSessions.addView(card)
    }

    private fun openSessionMap(session: SessionItem) {
        val carTitle = session.description ?: carsList.find { it.id == session.carId }?.description ?: "Car #${session.carId ?: ""}"
        val intent = Intent(this, SessionMapActivity::class.java).apply {
            putExtra("SESSION_ID", session.id)
            putExtra("DEVICE_ID", session.deviceId)
            putExtra("START_TIME", session.startTime)
            putExtra("END_TIME", session.endTime)
            putExtra("CAR_TITLE", carTitle)
            putExtra("DISTANCE", session.distance ?: 0.0)
            putExtra("COST", session.cost ?: 0.0)
        }
        startActivity(intent)
    }

    private fun toggleType(session: SessionItem) {
        val nextType = if (session.type == "B") "P" else "B"
        lifecycleScope.launch {
            val res = apiClient.toggleSessionType(session.id, nextType)
            res.onSuccess {
                Toast.makeText(this@SessionsActivity, "Trip type updated", Toast.LENGTH_SHORT).show()
                loadSessions()
            }.onFailure { err ->
                Toast.makeText(this@SessionsActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun endSession(session: SessionItem) {
        lifecycleScope.launch {
            val res = apiClient.endSession(session.id, session.deviceId)
            res.onSuccess {
                Toast.makeText(this@SessionsActivity, "Trip ended", Toast.LENGTH_SHORT).show()
                loadSessions()
            }.onFailure { err ->
                Toast.makeText(this@SessionsActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeleteSession(session: SessionItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Trip")
            .setMessage("Are you sure you want to delete this trip record?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val res = apiClient.deleteSession(session.id)
                    res.onSuccess {
                        Toast.makeText(this@SessionsActivity, "Trip deleted", Toast.LENGTH_SHORT).show()
                        loadSessions()
                    }.onFailure { err ->
                        Toast.makeText(this@SessionsActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddSessionDialog() {
        if (carsList.isEmpty()) {
            Toast.makeText(this, "Please register a car first in Manage Cars", Toast.LENGTH_LONG).show()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val carSpinner = Spinner(this).apply {
            val names = carsList.map { it.description ?: it.licensePlate ?: "Car #${it.id}" }
            adapter = ArrayAdapter(this@SessionsActivity, android.R.layout.simple_spinner_dropdown_item, names)
        }

        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SessionsActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Personal", "Business"))
        }

        val utcNow = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val startInput = EditText(this).apply {
            hint = "Start UTC (e.g. 2026-10-23T15:00:00.000Z)"
            setText(utcNow)
        }

        val endInput = EditText(this).apply {
            hint = "End UTC (Optional)"
        }

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_ID"

        layout.addView(TextView(this).apply { text = "Car:"; setTextColor(Color.parseColor("#334155")) })
        layout.addView(carSpinner)
        layout.addView(TextView(this).apply { text = "Type:"; setTextColor(Color.parseColor("#334155")); setPadding(0, 12, 0, 0) })
        layout.addView(typeSpinner)
        layout.addView(TextView(this).apply { text = "Start Time (UTC):"; setTextColor(Color.parseColor("#334155")); setPadding(0, 12, 0, 0) })
        layout.addView(startInput)
        layout.addView(TextView(this).apply { text = "End Time (UTC - Optional):"; setTextColor(Color.parseColor("#334155")); setPadding(0, 12, 0, 0) })
        layout.addView(endInput)

        AlertDialog.Builder(this)
            .setTitle("Add Trip / Session")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val selectedCar = carsList[carSpinner.selectedItemPosition]
                val type = if (typeSpinner.selectedItemPosition == 0) "P" else "B"
                val start = startInput.text.toString().trim()
                val end = endInput.text.toString().trim().ifEmpty { null }

                if (start.isEmpty()) {
                    Toast.makeText(this, "Start time is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    val res = apiClient.addSession(
                        deviceId = deviceId,
                        carId = selectedCar.id,
                        startUtc = start,
                        endUtc = end,
                        type = type
                    )
                    res.onSuccess {
                        Toast.makeText(this@SessionsActivity, "Session added", Toast.LENGTH_SHORT).show()
                        loadSessions()
                    }.onFailure { err ->
                        Toast.makeText(this@SessionsActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
