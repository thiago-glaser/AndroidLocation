package com.example.locationservice

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivityBluetoothBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Locale

class BluetoothActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBluetoothBinding
    private lateinit var apiClient: ApiClient
    private var carsList = listOf<CarItem>()
    private var allDevices = listOf<BluetoothItem>()

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        renderDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBluetoothBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { loadDevices() }
        binding.switchOnlyPaired.setOnCheckedChangeListener { _, _ -> renderDevices() }
        binding.btnCleanUnknown.setOnClickListener { confirmCleanUnknownDevices() }

        checkBluetoothPermissions()
        loadDevices()
    }

    private fun checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    private fun getPhoneBondedAddresses(): Set<String> {
        return try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: return emptySet()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return emptySet()
                }
            }

            adapter.bondedDevices?.mapNotNull { it.address?.trim()?.uppercase(Locale.ROOT) }?.toSet() ?: emptySet()
        } catch (e: Exception) {
            Log.e("BluetoothActivity", "Failed to get bonded devices", e)
            emptySet()
        }
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
                allDevices = devices
                renderDevices()
            }.onFailure { err ->
                Toast.makeText(this@BluetoothActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                binding.tvEmptyState.apply {
                    text = "Failed to load Bluetooth devices: ${err.message}"
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun renderDevices() {
        binding.containerDevices.removeAllViews()
        val onlyPaired = binding.switchOnlyPaired.isChecked
        val bondedAddresses = getPhoneBondedAddresses()

        val filtered = if (onlyPaired) {
            allDevices.filter { device ->
                val addr = device.address?.trim()?.uppercase(Locale.ROOT) ?: ""
                val name = device.name?.trim() ?: ""
                val isUnknown = name.isEmpty() || name.equals("Unknown Device", ignoreCase = true)

                val isBonded = bondedAddresses.isNotEmpty() && bondedAddresses.contains(addr)
                val isAssigned = !device.carId.isNullOrEmpty()

                if (bondedAddresses.isNotEmpty()) {
                    isBonded || isAssigned
                } else {
                    // Fallback if permission not yet granted: hide unassigned unknown devices
                    !isUnknown || isAssigned
                }
            }
        } else {
            allDevices
        }

        if (filtered.isEmpty()) {
            binding.tvEmptyState.text = if (onlyPaired) {
                getString(R.string.bt_empty_paired)
            } else {
                getString(R.string.bt_empty_all)
            }
            binding.tvEmptyState.visibility = View.VISIBLE
        } else {
            binding.tvEmptyState.visibility = View.GONE
            filtered.forEach { device ->
                addDeviceView(device)
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
            text = device.name?.takeIf { it.isNotBlank() } ?: "Unknown Device"
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTypeface(null, Typeface.BOLD)
        }

        val tvAddress = TextView(this).apply {
            text = "MAC: " + (device.address ?: "N/A")
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
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
            setTypeface(null, Typeface.BOLD)
        }

        textLayout.addView(tvName)
        textLayout.addView(tvAddress)
        textLayout.addView(tvCar)

        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        val btnAssign = Button(this).apply {
            text = "Assign Car"
            textSize = 12f
            setTextColor(Color.parseColor("#4F46E5"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(12, 0, 12, 0)
            setOnClickListener { showAssignCarDialog(device) }
        }

        val btnDelete = Button(this).apply {
            text = "Delete"
            textSize = 12f
            setTextColor(Color.parseColor("#DC2626"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(12, 0, 12, 0)
            setOnClickListener { confirmDeleteDevice(device) }
        }

        buttonsLayout.addView(btnAssign)
        buttonsLayout.addView(btnDelete)

        card.addView(textLayout)
        card.addView(buttonsLayout)

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

        if (!device.carId.isNullOrEmpty()) {
            val idx = carsList.indexOfFirst { it.id == device.carId }
            if (idx >= 0) {
                spinner.setSelection(idx + 1)
            }
        }

        layout.addView(TextView(this).apply {
            text = "Select vehicle for auto-tracking when connected to ${device.name}:"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, 0, 0, 16)
        })
        layout.addView(spinner)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bt_link_car_title)
            .setView(layout)
            .setPositiveButton(R.string.common_save) { _, _ ->
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
                        Toast.makeText(this@BluetoothActivity, getString(R.string.bt_link_car_success), Toast.LENGTH_SHORT).show()
                        loadDevices()
                    }.onFailure { err ->
                        Toast.makeText(this@BluetoothActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmDeleteDevice(device: BluetoothItem) {
        val displayName = device.name?.takeIf { it.isNotBlank() } ?: "Unknown Device"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bt_delete_device_title)
            .setMessage(getString(R.string.bt_delete_device_msg, displayName, device.address ?: "N/A"))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                lifecycleScope.launch {
                    val res = apiClient.deleteBluetooth(device.id)
                    res.onSuccess {
                        Toast.makeText(this@BluetoothActivity, getString(R.string.bt_device_deleted), Toast.LENGTH_SHORT).show()
                        loadDevices()
                    }.onFailure { err ->
                        Toast.makeText(this@BluetoothActivity, "Failed to delete: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmCleanUnknownDevices() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bt_delete_unknown_title)
            .setMessage(getString(R.string.bt_delete_unknown_msg))
            .setPositiveButton(R.string.bt_btn_delete_unknown) { _, _ ->
                lifecycleScope.launch {
                    val res = apiClient.cleanUnknownBluetooth()
                    res.onSuccess {
                        Toast.makeText(this@BluetoothActivity, getString(R.string.bt_unknown_cleaned), Toast.LENGTH_SHORT).show()
                        loadDevices()
                    }.onFailure { err ->
                        Toast.makeText(this@BluetoothActivity, "Failed to clean: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
