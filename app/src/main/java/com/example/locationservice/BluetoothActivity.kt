package com.example.locationservice

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivityBluetoothBinding
import kotlinx.coroutines.launch

class BluetoothActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBluetoothBinding
    private lateinit var apiClient: ApiClient
    private var carsList = listOf<CarItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBluetoothBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { loadDevices() }

        loadDevices()
    }

    private fun loadDevices() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE
        binding.containerDevices.removeAllViews()

        lifecycleScope.launch {
            // First load cars so we know names
            val carsResult = apiClient.getCars()
            carsList = carsResult.getOrDefault(emptyList())

            val result = apiClient.getBluetooth()
            binding.progressBar.visibility = View.GONE

            result.onSuccess { devices ->
                if (devices.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                } else {
                    devices.forEach { device ->
                        addDeviceView(device)
                    }
                }
            }.onFailure { err ->
                Toast.makeText(this@BluetoothActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                binding.tvEmptyState.apply {
                    text = "Failed to load Bluetooth devices: ${err.message}"
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun addDeviceView(device: BluetoothItem) {
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

        val tvName = TextView(this).apply {
            text = device.name ?: "Unknown Device"
            textSize = 16f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val tvAddress = TextView(this).apply {
            text = "MAC: " + (device.address ?: "N/A")
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 4, 0, 0)
        }

        val assignedCarName = device.carDescription
            ?: carsList.find { it.id == device.carId }?.description
            ?: if (!device.carId.isNullOrEmpty()) "Car #${device.carId}" else "Not assigned"

        val tvCar = TextView(this).apply {
            text = "Linked Car: $assignedCarName"
            textSize = 13f
            setTextColor(if (device.carId.isNullOrEmpty()) Color.parseColor("#EF4444") else Color.parseColor("#059669"))
            setPadding(0, 4, 0, 0)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        textLayout.addView(tvName)
        textLayout.addView(tvAddress)
        textLayout.addView(tvCar)

        val btnAssign = Button(this).apply {
            text = "Assign Car"
            textSize = 12f
            setTextColor(Color.parseColor("#4F46E5"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showAssignCarDialog(device) }
        }

        card.addView(textLayout)
        card.addView(btnAssign)

        binding.containerDevices.addView(card)
    }

    private fun showAssignCarDialog(device: BluetoothItem) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val options = mutableListOf("None (Unlink)")
        carsList.forEach { car ->
            options.add(car.description ?: car.licensePlate ?: "Car #${car.id}")
        }

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@BluetoothActivity, android.R.layout.simple_spinner_dropdown_item, options)
        }

        // Set initial selection
        if (!device.carId.isNullOrEmpty()) {
            val idx = carsList.indexOfFirst { it.id == device.carId }
            if (idx >= 0) {
                spinner.setSelection(idx + 1)
            }
        }

        layout.addView(TextView(this).apply {
            text = "Select vehicle for auto-tracking when connected to ${device.name}:"
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 0, 0, 16)
        })
        layout.addView(spinner)

        AlertDialog.Builder(this)
            .setTitle("Link Car to Bluetooth")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val selectedPos = spinner.selectedItemPosition
                val newCarId = if (selectedPos == 0) null else carsList[selectedPos - 1].id

                lifecycleScope.launch {
                    val res = apiClient.updateBluetooth(
                        id = device.id,
                        carId = newCarId,
                        name = device.name ?: "",
                        address = device.address ?: "",
                        description = device.description ?: ""
                    )
                    res.onSuccess {
                        Toast.makeText(this@BluetoothActivity, "Linked car updated", Toast.LENGTH_SHORT).show()
                        loadDevices()
                    }.onFailure { err ->
                        Toast.makeText(this@BluetoothActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
