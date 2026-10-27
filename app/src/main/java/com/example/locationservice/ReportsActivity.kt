package com.example.locationservice

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivityReportsBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

class ReportsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportsBinding
    private lateinit var apiClient: ApiClient
    private var carsList = listOf<CarItem>()

    // Current report tab: 0 = Trips & Summary, 1 = Monthly Summary, 2 = Fuel Economy
    private var currentTab = 0

    // Cached report data
    private var currentReportsSummary: ReportsSummary? = null
    private var currentFuelSummary: FuelSummary? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { refreshActiveReport() }
        binding.btnShare.setOnClickListener { shareCurrentReport() }

        setupTabs()
        setupFilters()
        loadCarsAndReports()
    }

    private fun setupTabs() {
        binding.tabTrips.setOnClickListener { selectTab(0) }
        binding.tabMonthly.setOnClickListener { selectTab(1) }
        binding.tabFuel.setOnClickListener { selectTab(2) }
    }

    private fun selectTab(tabIndex: Int) {
        currentTab = tabIndex

        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_active)
        val inactiveBg = ContextCompat.getDrawable(this, R.drawable.bg_pill_inactive)
        val whiteColor = Color.WHITE
        val slateColor = Color.parseColor("#475569")

        binding.tabTrips.background = if (tabIndex == 0) activeBg else inactiveBg
        binding.tabTrips.setTextColor(if (tabIndex == 0) whiteColor else slateColor)

        binding.tabMonthly.background = if (tabIndex == 1) activeBg else inactiveBg
        binding.tabMonthly.setTextColor(if (tabIndex == 1) whiteColor else slateColor)

        binding.tabFuel.background = if (tabIndex == 2) activeBg else inactiveBg
        binding.tabFuel.setTextColor(if (tabIndex == 2) whiteColor else slateColor)

        when (tabIndex) {
            0 -> {
                // Trips & Summary
                binding.layoutMonthFilter.visibility = View.VISIBLE
                binding.layoutTypeFilter.visibility = View.VISIBLE
                binding.layoutSummaryCards.visibility = View.VISIBLE
                binding.layoutTripsContainer.visibility = View.VISIBLE
                binding.layoutMonthlyContainer.visibility = View.GONE
                binding.layoutFuelContainer.visibility = View.GONE
                loadReports()
            }
            1 -> {
                // Monthly Summary
                binding.layoutMonthFilter.visibility = View.GONE
                binding.layoutTypeFilter.visibility = View.VISIBLE
                binding.layoutSummaryCards.visibility = View.VISIBLE
                binding.layoutTripsContainer.visibility = View.GONE
                binding.layoutMonthlyContainer.visibility = View.VISIBLE
                binding.layoutFuelContainer.visibility = View.GONE
                loadReports()
            }
            2 -> {
                // Fuel Economy
                binding.layoutMonthFilter.visibility = View.GONE
                binding.layoutTypeFilter.visibility = View.GONE
                binding.cardBreakdown.visibility = View.GONE
                binding.layoutSummaryCards.visibility = View.GONE
                binding.layoutTripsContainer.visibility = View.GONE
                binding.layoutMonthlyContainer.visibility = View.GONE
                binding.layoutFuelContainer.visibility = View.VISIBLE
                loadFuelReport()
            }
        }
    }

    private fun setupFilters() {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val years = mutableListOf("Any Year")
        for (y in 2024..2040) {
            years.add(y.toString())
        }
        val yearAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, years)
        binding.spinnerYearFilter.adapter = yearAdapter
        val currentYearIdx = years.indexOf(currentYear.toString())
        if (currentYearIdx >= 0) {
            binding.spinnerYearFilter.setSelection(currentYearIdx)
        }

        val months = listOf(
            "Any Month", "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
        binding.spinnerMonthFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, months)

        val types = listOf("All Types", "Personal Only", "Business Only")
        binding.spinnerTypeFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)

        binding.btnApplyFilters.setOnClickListener {
            refreshActiveReport()
        }

        binding.btnResetFilters.setOnClickListener {
            binding.spinnerCarFilter.setSelection(0)
            binding.spinnerYearFilter.setSelection(0)
            binding.spinnerMonthFilter.setSelection(0)
            binding.spinnerTypeFilter.setSelection(0)
            refreshActiveReport()
        }
    }

    private fun loadCarsAndReports() {
        lifecycleScope.launch {
            val carsRes = apiClient.getCars()
            carsList = carsRes.getOrDefault(emptyList())

            val carNames = mutableListOf("All Cars")
            carsList.forEach { car ->
                carNames.add(car.description ?: car.licensePlate ?: "Car #${car.id}")
            }
            binding.spinnerCarFilter.adapter = ArrayAdapter(
                this@ReportsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                carNames
            )

            loadReports()
        }
    }

    private fun refreshActiveReport() {
        if (currentTab == 2) {
            loadFuelReport()
        } else {
            loadReports()
        }
    }

    private fun getSelectedCarId(): String? {
        val selectedCarIdx = binding.spinnerCarFilter.selectedItemPosition
        return if (selectedCarIdx > 0 && selectedCarIdx <= carsList.size) {
            carsList[selectedCarIdx - 1].id
        } else null
    }

    private fun getSelectedYear(): String? {
        val y = binding.spinnerYearFilter.selectedItem?.toString()
        return if (y.isNullOrEmpty() || y == "Any Year") null else y
    }

    private fun getSelectedMonthNumber(): String? {
        val monthIdx = binding.spinnerMonthFilter.selectedItemPosition
        return if (monthIdx > 0) monthIdx.toString() else null
    }

    private fun getSelectedType(): String? {
        return when (binding.spinnerTypeFilter.selectedItemPosition) {
            1 -> "P"
            2 -> "B"
            else -> null
        }
    }

    private fun loadReports() {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnApplyFilters.isEnabled = false
        binding.btnApplyFilters.text = "Processing..."

        val selectedCarId = getSelectedCarId()
        val selectedYear = getSelectedYear()
        val selectedMonth = if (currentTab == 0) getSelectedMonthNumber() else null
        val selectedType = getSelectedType()

        lifecycleScope.launch {
            val result = apiClient.getReports(
                carId = selectedCarId,
                year = selectedYear,
                month = selectedMonth,
                type = selectedType,
                onProgress = { current, total ->
                    runOnUiThread {
                        if (total > 0) {
                            val pct = ((current.toDouble() / total) * 100).roundToInt()
                            binding.btnApplyFilters.text = "$current/$total ($pct%)"
                        }
                    }
                }
            )
            binding.progressBar.visibility = View.GONE
            binding.btnApplyFilters.isEnabled = true
            binding.btnApplyFilters.text = "Generate"

            result.onSuccess { summary ->
                currentReportsSummary = summary
                displaySummaryMetrics(summary)
                displayBreakdown(summary, selectedType)

                if (currentTab == 0) {
                    displayTripsList(summary.sessions)
                } else if (currentTab == 1) {
                    displayMonthlyList(summary)
                }
            }.onFailure { err ->
                Toast.makeText(this@ReportsActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displaySummaryMetrics(summary: ReportsSummary) {
        binding.tvTotalDistance.text = String.format(Locale.US, "%.2f KM", summary.totalDistanceKm)
        binding.tvTotalDuration.text = formatDuration(summary.totalDurationHours)
        binding.tvTotalTrips.text = summary.totalTrips.toString()
        binding.tvTotalCost.text = String.format(Locale.US, "$%.2f%s", summary.totalCost, if (summary.hasProjected) "*" else "")

        binding.tvEstimatedWarning.visibility = if (summary.hasProjected) View.VISIBLE else View.GONE
    }

    private fun displayBreakdown(summary: ReportsSummary, selectedType: String?) {
        // Only display category breakdown when all types are selected
        if (selectedType != null) {
            binding.cardBreakdown.visibility = View.GONE
            return
        }
        binding.cardBreakdown.visibility = View.VISIBLE

        val totalDist = summary.totalDistanceKm
        val pDist = summary.personalDistanceKm
        val bDist = summary.businessDistanceKm

        val pDistPct = if (totalDist > 0) ((pDist / totalDist) * 100.0).roundToInt() else 0
        val bDistPct = if (totalDist > 0) ((bDist / totalDist) * 100.0).roundToInt() else 0

        // Proportional Bar for Distance
        val distPersonalParams = binding.barDistPersonal.layoutParams as LinearLayout.LayoutParams
        val distBusinessParams = binding.barDistBusiness.layoutParams as LinearLayout.LayoutParams
        distPersonalParams.weight = if (totalDist > 0) pDist.toFloat().coerceAtLeast(0.01f) else 1f
        distBusinessParams.weight = if (totalDist > 0) bDist.toFloat().coerceAtLeast(0.01f) else 1f
        binding.barDistPersonal.layoutParams = distPersonalParams
        binding.barDistBusiness.layoutParams = distBusinessParams

        binding.tvPersonalDistLegend.text = String.format(Locale.US, "🏠 Personal: %.1f km (%d%%)", pDist, pDistPct)
        binding.tvBusinessDistLegend.text = String.format(Locale.US, "💼 Business: %.1f km (%d%%)", bDist, bDistPct)

        // Time distribution
        val totalDur = summary.totalDurationHours
        val pDur = summary.personalDurationHours
        val bDur = summary.businessDurationHours

        val pDurPct = if (totalDur > 0) ((pDur / totalDur) * 100.0).roundToInt() else 0
        val bDurPct = if (totalDur > 0) ((bDur / totalDur) * 100.0).roundToInt() else 0

        val timePersonalParams = binding.barTimePersonal.layoutParams as LinearLayout.LayoutParams
        val timeBusinessParams = binding.barTimeBusiness.layoutParams as LinearLayout.LayoutParams
        timePersonalParams.weight = if (totalDur > 0) pDur.toFloat().coerceAtLeast(0.01f) else 1f
        timeBusinessParams.weight = if (totalDur > 0) bDur.toFloat().coerceAtLeast(0.01f) else 1f
        binding.barTimePersonal.layoutParams = timePersonalParams
        binding.barTimeBusiness.layoutParams = timeBusinessParams

        binding.tvPersonalTimeLegend.text = String.format(Locale.US, "🏠 Personal: %s (%d%%)", formatDuration(pDur), pDurPct)
        binding.tvBusinessTimeLegend.text = String.format(Locale.US, "💼 Business: %s (%d%%)", formatDuration(bDur), bDurPct)

        // Cost distribution
        val totalCost = summary.totalCost
        val pCost = summary.personalCost
        val bCost = summary.businessCost

        val costPersonalParams = binding.barCostPersonal.layoutParams as LinearLayout.LayoutParams
        val costBusinessParams = binding.barCostBusiness.layoutParams as LinearLayout.LayoutParams
        costPersonalParams.weight = if (totalCost > 0) pCost.toFloat().coerceAtLeast(0.01f) else 1f
        costBusinessParams.weight = if (totalCost > 0) bCost.toFloat().coerceAtLeast(0.01f) else 1f
        binding.barCostPersonal.layoutParams = costPersonalParams
        binding.barCostBusiness.layoutParams = costBusinessParams

        binding.tvPersonalCostLegend.text = String.format(Locale.US, "🏠 Personal: $%.2f", pCost)
        binding.tvBusinessCostLegend.text = String.format(Locale.US, "💼 Business: $%.2f", bCost)
    }

    private fun displayTripsList(sessions: List<SessionItem>) {
        binding.layoutTripsList.removeAllViews()
        binding.tvTripsHeader.text = "TRIP SESSIONS (${sessions.size})"

        if (sessions.isEmpty()) {
            binding.tvEmptyTrips.visibility = View.VISIBLE
            return
        }
        binding.tvEmptyTrips.visibility = View.GONE

        val displayFormat = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())

        sessions.forEach { session ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
                background = ContextCompat.getDrawable(this@ReportsActivity, R.drawable.bg_card)
                elevation = dpToPx(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dpToPx(10))
                }
            }

            // Top row: Car Title & Status Badge
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val tvTitle = TextView(this).apply {
                text = session.description ?: "Unknown Vehicle"
                textSize = 14f
                setTextColor(Color.parseColor("#0F172A"))
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val isCompleted = !session.endTime.isNullOrEmpty()
            val tvStatus = TextView(this).apply {
                text = if (isCompleted) "Completed" else "Active"
                textSize = 10f
                setTextColor(if (isCompleted) Color.parseColor("#475569") else Color.parseColor("#15803D"))
                background = ContextCompat.getDrawable(
                    this@ReportsActivity,
                    if (isCompleted) R.drawable.bg_badge else R.drawable.bg_badge_active
                )
                setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
            }

            topRow.addView(tvTitle)
            topRow.addView(tvStatus)
            card.addView(topRow)

            // Date / Time Row
            val startDate = apiClient.parseUtcDate(session.startTime)
            val endDate = apiClient.parseUtcDate(session.endTime)
            val dateText = if (startDate != null) {
                if (endDate != null) {
                    val startStr = displayFormat.format(startDate)
                    val endHourStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(endDate)
                    "$startStr → $endHourStr"
                } else {
                    displayFormat.format(startDate) + " (Ongoing)"
                }
            } else "Date unknown"

            val tvDate = TextView(this).apply {
                text = dateText
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dpToPx(3), 0, dpToPx(6))
                }
            }
            card.addView(tvDate)

            // Location if available
            val locText = when {
                !session.locationStart.isNullOrBlank() && !session.locationEnd.isNullOrBlank() ->
                    "${session.locationStart} → ${session.locationEnd}"
                !session.locationStart.isNullOrBlank() -> session.locationStart
                !session.aiDescription.isNullOrBlank() -> session.aiDescription
                else -> null
            }
            if (locText != null) {
                val tvLoc = TextView(this).apply {
                    text = "📍 $locText"
                    textSize = 11f
                    setTextColor(Color.parseColor("#475569"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, dpToPx(6))
                    }
                }
                card.addView(tvLoc)
            }

            // Stats row: Type Badge, Distance, Duration, Cost
            val statsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dpToPx(4), 0, dpToPx(8))
                }
            }

            val isBusiness = session.type == "B"
            val tvTypeBadge = TextView(this).apply {
                text = if (isBusiness) "BUSINESS" else "PERSONAL"
                textSize = 10f
                setTextColor(if (isBusiness) Color.parseColor("#B45309") else Color.parseColor("#1D4ED8"))
                paint.isFakeBoldText = true
                background = ContextCompat.getDrawable(
                    this@ReportsActivity,
                    if (isBusiness) R.drawable.bg_badge_business else R.drawable.bg_badge_personal
                )
                setPadding(dpToPx(6), dpToPx(3), dpToPx(6), dpToPx(3))
            }
            statsRow.addView(tvTypeBadge)

            val tvDistance = TextView(this).apply {
                text = String.format(Locale.US, "  •  %.2f km", session.distance ?: 0.0)
                textSize = 12f
                setTextColor(Color.parseColor("#2563EB"))
                paint.isFakeBoldText = true
            }
            statsRow.addView(tvDistance)

            var durHours = session.timeTraveled ?: 0.0
            if (durHours <= 0.0 && startDate != null) {
                val endD = endDate ?: Date()
                durHours = (endD.time - startDate.time).coerceAtLeast(0) / (1000.0 * 60 * 60)
            }
            val tvDuration = TextView(this).apply {
                text = "  •  " + formatDuration(durHours)
                textSize = 12f
                setTextColor(Color.parseColor("#16A34A"))
            }
            statsRow.addView(tvDuration)

            val costVal = session.cost ?: 0.0
            val tvCost = TextView(this).apply {
                text = String.format(Locale.US, "  •  $%.2f%s", costVal, if (session.valueConfirmed == "N") " (~Est)" else "")
                textSize = 12f
                setTextColor(if (session.valueConfirmed == "N") Color.parseColor("#D97706") else Color.parseColor("#059669"))
                paint.isFakeBoldText = true
            }
            statsRow.addView(tvCost)

            card.addView(statsRow)

            // Action Button: View on OpenStreetMap Route Map
            val btnMap = Button(this).apply {
                text = "🗺️ View Route Map"
                textSize = 12f
                isAllCaps = false
                setTextColor(Color.parseColor("#2563EB"))
                background = ContextCompat.getDrawable(this@ReportsActivity, R.drawable.bg_badge)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(36))
                setOnClickListener {
                    val intent = Intent(this@ReportsActivity, SessionMapActivity::class.java).apply {
                        putExtra("CAR_TITLE", session.description ?: "Trip Route")
                        putExtra("START_TIME", session.startTime ?: "")
                        putExtra("END_TIME", session.endTime)
                        putExtra("DEVICE_ID", session.deviceId)
                        putExtra("DISTANCE", session.distance ?: 0.0)
                    }
                    startActivity(intent)
                }
            }
            card.addView(btnMap)

            binding.layoutTripsList.addView(card)
        }
    }

    private fun displayMonthlyList(summary: ReportsSummary) {
        val sessions = summary.sessions
        binding.layoutMonthlyList.removeAllViews()

        if (sessions.isEmpty()) {
            binding.tvEmptyMonthly.visibility = View.VISIBLE
            return
        }
        binding.tvEmptyMonthly.visibility = View.GONE

        // Display Proportional Other Expenses allocations (matching web reports/monthly/page.js)
        if (summary.otherExpenses.isNotEmpty()) {
            val totalDist = summary.totalDistanceKm
            val pPct = if (totalDist > 0) ((summary.personalDistanceKm / totalDist) * 100.0).roundToInt() else 0
            val bPct = if (totalDist > 0) ((summary.businessDistanceKm / totalDist) * 100.0).roundToInt() else 0

            summary.otherExpenses.forEach { exp ->
                val expCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
                    background = ContextCompat.getDrawable(this@ReportsActivity, R.drawable.bg_card)
                    elevation = dpToPx(1).toFloat()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dpToPx(12))
                    }
                }

                val tvExpHeader = TextView(this).apply {
                    text = String.format(Locale.US, "EXPENSE ALLOCATION: %s (Total: $%.2f)", exp.expenseTypeName.uppercase(Locale.getDefault()), exp.total)
                    textSize = 11f
                    setTextColor(Color.parseColor("#64748B"))
                    paint.isFakeBoldText = true
                }
                expCard.addView(tvExpHeader)

                val expRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, dpToPx(6), 0, 0)
                    }
                }

                val tvPersonalExp = TextView(this).apply {
                    text = String.format(Locale.US, "🏠 Prop. Personal: $%.2f (%d%%)", exp.personal, pPct)
                    textSize = 13f
                    setTextColor(Color.parseColor("#4F46E5"))
                    paint.isFakeBoldText = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvBusinessExp = TextView(this).apply {
                    text = String.format(Locale.US, "💼 Prop. Business: $%.2f (%d%%)", exp.business, bPct)
                    textSize = 13f
                    setTextColor(Color.parseColor("#DB2777"))
                    paint.isFakeBoldText = true
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                expRow.addView(tvPersonalExp)
                expRow.addView(tvBusinessExp)
                expCard.addView(expRow)

                binding.layoutMonthlyList.addView(expCard)
            }
        }

        // Group sessions by Month (YYYY-MM) in local timezone (matching web reports/monthly/page.js)
        val monthKeyFormat = SimpleDateFormat("yyyy-MM", Locale.US)
        val monthDisplayFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

        val grouped = sessions.groupBy { session ->
            val start = apiClient.parseUtcDate(session.startTime)
            if (start != null) monthKeyFormat.format(start) else "Unknown"
        }.toSortedMap()

        grouped.forEach { (yearMonth, monthSessions) ->
            var monthDist = 0.0
            var pDist = 0.0
            var bDist = 0.0
            var monthCost = 0.0
            var pCost = 0.0
            var bCost = 0.0
            var monthDuration = 0.0
            var hasProjected = false

            monthSessions.forEach { s ->
                val d = s.distance ?: 0.0
                val c = s.cost ?: 0.0
                val dur = s.timeTraveled ?: 0.0
                monthDist += d
                monthCost += c
                monthDuration += dur
                if (s.valueConfirmed == "N") hasProjected = true
                if (s.type == "B") {
                    bDist += d
                    bCost += c
                } else {
                    pDist += d
                    pCost += c
                }
            }

            var displayTitle = yearMonth
            if (yearMonth != "Unknown") {
                try {
                    val d = SimpleDateFormat("yyyy-MM", Locale.US).parse(yearMonth)
                    if (d != null) displayTitle = monthDisplayFormat.format(d)
                } catch (_: Exception) {}
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
                background = ContextCompat.getDrawable(this@ReportsActivity, R.drawable.bg_card)
                elevation = dpToPx(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dpToPx(12))
                }
            }

            // Month Title & Trip Count
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val tvTitle = TextView(this).apply {
                text = displayTitle
                textSize = 16f
                setTextColor(Color.parseColor("#0F172A"))
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTripsCount = TextView(this).apply {
                text = "${monthSessions.size} trips"
                textSize = 11f
                setTextColor(Color.parseColor("#2563EB"))
                paint.isFakeBoldText = true
                background = ContextCompat.getDrawable(this@ReportsActivity, R.drawable.bg_badge_personal)
                setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
            }

            topRow.addView(tvTitle)
            topRow.addView(tvTripsCount)
            card.addView(topRow)

            // Month Key Metrics (Distance, Duration, Cost)
            val statsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dpToPx(8), 0, dpToPx(8))
                }
            }

            val tvDist = TextView(this).apply {
                text = String.format(Locale.US, "%.2f KM", monthDist)
                textSize = 14f
                setTextColor(Color.parseColor("#2563EB"))
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvDur = TextView(this).apply {
                text = formatDuration(monthDuration)
                textSize = 14f
                setTextColor(Color.parseColor("#16A34A"))
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvCost = TextView(this).apply {
                text = String.format(Locale.US, "$%.2f%s", monthCost, if (hasProjected) " (~Est)" else "")
                textSize = 14f
                setTextColor(if (hasProjected) Color.parseColor("#D97706") else Color.parseColor("#059669"))
                paint.isFakeBoldText = true
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            statsRow.addView(tvDist)
            statsRow.addView(tvDur)
            statsRow.addView(tvCost)
            card.addView(statsRow)

            // Personal vs. Business Bar for Month
            val barContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(6)).apply {
                    setMargins(0, dpToPx(4), 0, dpToPx(6))
                }
                background = ContextCompat.getDrawable(this@ReportsActivity, R.drawable.bg_badge)
            }

            val barP = View(this).apply {
                setBackgroundColor(Color.parseColor("#3B82F6"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, if (monthDist > 0) pDist.toFloat().coerceAtLeast(0.01f) else 1f)
            }
            val barB = View(this).apply {
                setBackgroundColor(Color.parseColor("#EAB308"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, if (monthDist > 0) bDist.toFloat().coerceAtLeast(0.01f) else 1f)
            }
            barContainer.addView(barP)
            barContainer.addView(barB)
            card.addView(barContainer)

            // Month Legend Breakdown
            val legendRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val tvPLegend = TextView(this).apply {
                text = String.format(Locale.US, "🏠 Personal: %.2f km ($%.2f)", pDist, pCost)
                textSize = 11f
                setTextColor(Color.parseColor("#1D4ED8"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvBLegend = TextView(this).apply {
                text = String.format(Locale.US, "💼 Business: %.2f km ($%.2f)", bDist, bCost)
                textSize = 11f
                setTextColor(Color.parseColor("#B45309"))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            legendRow.addView(tvPLegend)
            legendRow.addView(tvBLegend)
            card.addView(legendRow)

            binding.layoutMonthlyList.addView(card)
        }
    }

    private fun loadFuelReport() {
        binding.progressBar.visibility = View.VISIBLE

        val selectedCarId = getSelectedCarId()
        val selectedYear = getSelectedYear()

        lifecycleScope.launch {
            val result = apiClient.getFuelSummary(carId = selectedCarId, year = selectedYear)
            binding.progressBar.visibility = View.GONE

            result.onSuccess { summary ->
                currentFuelSummary = summary
                displayFuelSummary(summary)
            }.onFailure { err ->
                Toast.makeText(this@ReportsActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayFuelSummary(summary: FuelSummary) {
        binding.tvFuelTotalCost.text = String.format(Locale.US, "$%.2f", summary.totalCost)
        binding.tvFuelTotalLiters.text = String.format(Locale.US, "%.2f L", summary.totalLiters)
        binding.tvFuelAvgEconomy.text = String.format(Locale.US, "%.2f km/L", summary.avgKmPerLiter)
        binding.tvFuelAvgPrice.text = String.format(Locale.US, "$%.2f/L ($%.3f/km)", summary.avgPricePerLiter, summary.avgPricePerKm)

        binding.layoutFuelList.removeAllViews()

        if (summary.fuelLogs.isEmpty()) {
            binding.tvEmptyFuel.visibility = View.VISIBLE
            return
        }
        binding.tvEmptyFuel.visibility = View.GONE

        val displayFormat = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())

        summary.fuelLogs.forEach { log ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
                background = ContextCompat.getDrawable(this@ReportsActivity, R.drawable.bg_card)
                elevation = dpToPx(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dpToPx(10))
                }
            }

            // Top row: Car name & Total Cost
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val tvCar = TextView(this).apply {
                text = log.carDescription ?: log.carLicensePlate ?: "Refuel Log"
                textSize = 14f
                setTextColor(Color.parseColor("#0F172A"))
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTotalVal = TextView(this).apply {
                text = String.format(Locale.US, "$%.2f", log.totalValue ?: 0.0)
                textSize = 15f
                setTextColor(Color.parseColor("#059669"))
                paint.isFakeBoldText = true
            }

            topRow.addView(tvCar)
            topRow.addView(tvTotalVal)
            card.addView(topRow)

            // Date
            val d = apiClient.parseUtcDate(log.timestampUtc)
            val dateStr = if (d != null) displayFormat.format(d) else log.timestampUtc ?: ""
            val tvDate = TextView(this).apply {
                text = dateStr
                textSize = 11f
                setTextColor(Color.parseColor("#64748B"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dpToPx(2), 0, dpToPx(6))
                }
            }
            card.addView(tvDate)

            // Refuel stats row
            val statsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val tvLiters = TextView(this).apply {
                text = String.format(Locale.US, "%.2f L", log.liters ?: 0.0)
                textSize = 12f
                setTextColor(Color.parseColor("#2563EB"))
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvKm = TextView(this).apply {
                val km = log.totalKilometers ?: 0.0
                text = if (km > 0) String.format(Locale.US, "%.1f km", km) else "-"
                textSize = 12f
                setTextColor(Color.parseColor("#475569"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvEff = TextView(this).apply {
                val eff = log.kilometerPerLiter ?: 0.0
                text = if (eff > 0) String.format(Locale.US, "%.2f km/L", eff) else "-"
                textSize = 12f
                setTextColor(Color.parseColor("#D97706"))
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvPriceKm = TextView(this).apply {
                val pkm = log.pricePerKilometer ?: 0.0
                text = if (pkm > 0) String.format(Locale.US, "$%.3f/km", pkm) else "$-"
                textSize = 12f
                setTextColor(Color.parseColor("#059669"))
                paint.isFakeBoldText = true
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            statsRow.addView(tvLiters)
            statsRow.addView(tvKm)
            statsRow.addView(tvEff)
            statsRow.addView(tvPriceKm)
            card.addView(statsRow)

            binding.layoutFuelList.addView(card)
        }
    }

    private fun shareCurrentReport() {
        val summary = currentReportsSummary ?: return
        val carName = binding.spinnerCarFilter.selectedItem?.toString() ?: "All Cars"
        val yearName = binding.spinnerYearFilter.selectedItem?.toString() ?: "Any Year"
        val monthName = if (currentTab == 0) binding.spinnerMonthFilter.selectedItem?.toString() ?: "Any Month" else "All Months"

        val sb = StringBuilder()
        sb.appendLine("🚗 TravelAccess Report")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("Vehicle: $carName")
        sb.appendLine("Period: $monthName, $yearName")
        sb.appendLine()
        sb.appendLine("• Total Distance: ${String.format(Locale.US, "%.2f km", summary.totalDistanceKm)}")
        sb.appendLine("• Total Duration: ${formatDuration(summary.totalDurationHours)}")
        sb.appendLine("• Total Trips: ${summary.totalTrips}")
        sb.appendLine("• Total Cost: $${String.format(Locale.US, "%.2f", summary.totalCost)}")
        sb.appendLine()
        sb.appendLine("📊 Category Breakdown:")
        sb.appendLine("• Personal: ${String.format(Locale.US, "%.2f km ($%.2f)", summary.personalDistanceKm, summary.personalCost)}")
        sb.appendLine("• Business: ${String.format(Locale.US, "%.2f km ($%.2f)", summary.businessDistanceKm, summary.businessCost)}")

        if (summary.otherExpenses.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("🧾 Proportional Expenses:")
            summary.otherExpenses.forEach { exp ->
                sb.appendLine("• ${exp.expenseTypeName}: Personal $${String.format(Locale.US, "%.2f", exp.personal)} | Business $${String.format(Locale.US, "%.2f", exp.business)}")
            }
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "TravelAccess Mileage Report")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intent, "Share Report Summary"))
    }

    private fun formatDuration(hours: Double): String {
        if (hours <= 0.0) return "0h 0m 0s"
        val h = floor(hours).toLong()
        val m = floor((hours - h) * 60.0).toLong()
        val s = floor(((hours - h) * 60.0 - m) * 60.0).toLong()
        return "${h}h ${m}m ${s}s"
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
