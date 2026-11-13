package com.example.locationservice

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivityCarsBinding
import kotlinx.coroutines.launch

class CarsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCarsBinding
    private lateinit var apiClient: ApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCarsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddCar.setOnClickListener { showAddCarDialog() }

        loadCars()
    }

    private fun loadCars() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE
        binding.containerCars.removeAllViews()

        lifecycleScope.launch {
            val result = apiClient.getCars()
            binding.progressBar.visibility = View.GONE

            result.onSuccess { cars ->
                if (cars.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                } else {
                    cars.forEach { car ->
                        addCarView(car)
                    }
                }
            }.onFailure { err ->
                Toast.makeText(this@CarsActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                binding.tvEmptyState.apply {
                    text = "Failed to load cars: ${err.message}"
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun addCarView(car: CarItem) {
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

        val tvTitle = TextView(this).apply {
            text = car.description ?: "Car #${car.id}"
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val tvPlate = TextView(this).apply {
            text = getString(R.string.cars_license_plate_label, car.licensePlate ?: "N/A")
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 4, 0, 0)
        }

        textLayout.addView(tvTitle)
        textLayout.addView(tvPlate)

        val btnDelete = Button(this).apply {
            text = getString(R.string.common_delete)
            textSize = 12f
            setTextColor(Color.parseColor("#EF4444"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { confirmDeleteCar(car) }
        }

        card.addView(textLayout)
        card.addView(btnDelete)

        binding.containerCars.addView(card)
    }

    private fun showAddCarDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val descInput = EditText(this).apply {
            hint = getString(R.string.cars_hint_desc)
        }
        val plateInput = EditText(this).apply {
            hint = getString(R.string.cars_hint_plate)
        }

        layout.addView(TextView(this).apply { text = "Description"; setTextColor(ContextCompat.getColor(context, R.color.text_primary)) })
        layout.addView(descInput)
        layout.addView(TextView(this).apply { text = "License Plate"; setTextColor(ContextCompat.getColor(context, R.color.text_primary)); setPadding(0, 16, 0, 0) })
        layout.addView(plateInput)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cars_dialog_add_title)
            .setView(layout)
            .setPositiveButton(R.string.common_add) { _, _ ->
                val desc = descInput.text.toString().trim()
                val plate = plateInput.text.toString().trim()
                if (desc.isEmpty() && plate.isEmpty()) {
                    Toast.makeText(this, getString(R.string.cars_toast_enter_info), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    val res = apiClient.addCar(desc, plate)
                    res.onSuccess {
                        Toast.makeText(this@CarsActivity, getString(R.string.cars_toast_added), Toast.LENGTH_SHORT).show()
                        loadCars()
                    }.onFailure { err ->
                        val msg = err.message ?: "Failed to add car"
                        if (msg.contains("limit", ignoreCase = true) || msg.contains("upgrade", ignoreCase = true) || msg.contains("Pro", ignoreCase = true)) {
                            MaterialAlertDialogBuilder(this@CarsActivity)
                                .setTitle("Upgrade to Pro")
                                .setMessage(getString(R.string.subscription_free_vehicle_limit_reached))
                                .setPositiveButton(R.string.subscription_title) { _, _ ->
                                    startActivity(Intent(this@CarsActivity, SubscriptionActivity::class.java))
                                }
                                .setNegativeButton(R.string.common_cancel, null)
                                .show()
                        } else {
                            Toast.makeText(this@CarsActivity, "Failed: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmDeleteCar(car: CarItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cars_dialog_delete_title)
            .setMessage(getString(R.string.cars_dialog_delete_msg, car.description ?: car.licensePlate))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                lifecycleScope.launch {
                    val res = apiClient.deleteCar(car.id)
                    res.onSuccess {
                        Toast.makeText(this@CarsActivity, getString(R.string.cars_toast_deleted), Toast.LENGTH_SHORT).show()
                        loadCars()
                    }.onFailure { err ->
                        Toast.makeText(this@CarsActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
