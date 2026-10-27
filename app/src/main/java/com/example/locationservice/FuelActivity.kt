package com.example.locationservice

import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
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
import com.example.locationservice.databinding.ActivityFuelBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class FuelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFuelBinding
    private lateinit var apiClient: ApiClient
    private var carsList = listOf<CarItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFuelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddFuel.setOnClickListener { showAddFuelDialog() }

        loadFuel()
    }

    private fun loadFuel() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE
        binding.containerFuel.removeAllViews()

        lifecycleScope.launch {
            val carsResult = apiClient.getCars()
            carsList = carsResult.getOrDefault(emptyList())

            val result = apiClient.getFuel()
            binding.progressBar.visibility = View.GONE

            result.onSuccess { fuelList ->
                if (fuelList.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                } else {
                    fuelList.forEach { item ->
                        addFuelView(item)
                    }
                }
            }.onFailure { err ->
                Toast.makeText(this@FuelActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                binding.tvEmptyState.apply {
                    text = "Failed to load fuel records: ${err.message}"
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun addFuelView(item: FuelItem) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            elevation = 2f
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val carLabel = item.carDescription ?: item.carLicensePlate ?: "Car #${item.carId}"
        val tvCar = TextView(this).apply {
            text = carLabel
            textSize = 16f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val formattedDate = try {
            val d = apiClient.parseUtcDate(item.timestampUtc)
            val outFmt = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
            if (d != null) outFmt.format(d) else item.timestampUtc ?: "N/A"
        } catch (e: Exception) {
            item.timestampUtc ?: "N/A"
        }

        val tvDate = TextView(this).apply {
            text = formattedDate
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 2, 0, 4)
        }

        val tvStats = TextView(this).apply {
            val costStr = String.format(Locale.US, "$%.2f", item.totalValue ?: 0.0)
            val litStr = String.format(Locale.US, "%.2f L", item.liters ?: 0.0)
            text = "Total: $costStr | Volume: $litStr"
            textSize = 14f
            setTextColor(Color.parseColor("#059669"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        textLayout.addView(tvCar)
        textLayout.addView(tvDate)
        textLayout.addView(tvStats)

        if ((item.totalKilometers ?: 0.0) > 0.0) {
            val tvEfficiency = TextView(this).apply {
                val km = String.format(Locale.US, "%.2f km", item.totalKilometers)
                val kml = String.format(Locale.US, "%.2f km/L", item.kilometerPerLiter ?: 0.0)
                val pkm = String.format(Locale.US, "$%.4f/km", item.pricePerKilometer ?: 0.0)
                text = "Traveled: $km • $kml • $pkm"
                textSize = 12f
                setTextColor(Color.parseColor("#475569"))
            }
            textLayout.addView(tvEfficiency)
        }

        val actionsCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        val btnCalculate = Button(this).apply {
            text = "Calculate"
            textSize = 11f
            setTextColor(Color.parseColor("#2563EB"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { calculateFuelEfficiency(item) }
        }

        val btnDelete = Button(this).apply {
            text = "Delete"
            textSize = 11f
            setTextColor(Color.parseColor("#EF4444"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { confirmDeleteFuel(item) }
        }

        actionsCol.addView(btnCalculate)
        actionsCol.addView(btnDelete)

        card.addView(textLayout)
        card.addView(actionsCol)

        binding.containerFuel.addView(card)
    }

    private fun calculateFuelEfficiency(item: FuelItem) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val res = apiClient.calculateFuel(item.id)
            binding.progressBar.visibility = View.GONE
            res.onSuccess {
                Toast.makeText(this@FuelActivity, "Calculated fuel efficiency!", Toast.LENGTH_SHORT).show()
                loadFuel()
            }.onFailure { err ->
                Toast.makeText(this@FuelActivity, "Calculate failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAddFuelDialog() {
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
            adapter = ArrayAdapter(this@FuelActivity, android.R.layout.simple_spinner_dropdown_item, names)
        }

        val totalCostInput = EditText(this).apply {
            hint = "Total Cost in $ (e.g. 65.50)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val litersInput = EditText(this).apply {
            hint = "Volume in Liters (e.g. 42.0)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val calendar = Calendar.getInstance()
        val dateDisplayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dateInput = EditText(this).apply {
            setText(dateDisplayFmt.format(calendar.time))
            isFocusable = false
            setOnClickListener {
                DatePickerDialog(
                    this@FuelActivity,
                    { _, year, month, dayOfMonth ->
                        calendar.set(year, month, dayOfMonth)
                        setText(dateDisplayFmt.format(calendar.time))
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        }

        layout.addView(TextView(this).apply { text = "Vehicle:"; setTextColor(Color.parseColor("#334155")) })
        layout.addView(carSpinner)
        layout.addView(TextView(this).apply { text = "Total Cost ($):"; setTextColor(Color.parseColor("#334155")); setPadding(0, 12, 0, 0) })
        layout.addView(totalCostInput)
        layout.addView(TextView(this).apply { text = "Liters (L):"; setTextColor(Color.parseColor("#334155")); setPadding(0, 12, 0, 0) })
        layout.addView(litersInput)
        layout.addView(TextView(this).apply { text = "Date (Tap to change):"; setTextColor(Color.parseColor("#334155")); setPadding(0, 12, 0, 0) })
        layout.addView(dateInput)

        AlertDialog.Builder(this)
            .setTitle("Log Fuel Fill-Up")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val cost = totalCostInput.text.toString().toDoubleOrNull() ?: 0.0
                val liters = litersInput.text.toString().toDoubleOrNull() ?: 0.0

                if (cost <= 0.0 || liters <= 0.0) {
                    Toast.makeText(this, "Please enter valid cost and volume", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val selectedCar = carsList[carSpinner.selectedItemPosition]
                val utcDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(calendar.time)

                lifecycleScope.launch {
                    val res = apiClient.addFuel(
                        carId = selectedCar.id,
                        timestampUtc = utcDate,
                        totalValue = cost,
                        liters = liters
                    )
                    res.onSuccess {
                        Toast.makeText(this@FuelActivity, "Fuel entry saved", Toast.LENGTH_SHORT).show()
                        loadFuel()
                    }.onFailure { err ->
                        Toast.makeText(this@FuelActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteFuel(item: FuelItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Fuel Entry")
            .setMessage("Are you sure you want to delete this fuel record?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val res = apiClient.deleteFuel(item.id)
                    res.onSuccess {
                        Toast.makeText(this@FuelActivity, "Deleted", Toast.LENGTH_SHORT).show()
                        loadFuel()
                    }.onFailure { err ->
                        Toast.makeText(this@FuelActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
