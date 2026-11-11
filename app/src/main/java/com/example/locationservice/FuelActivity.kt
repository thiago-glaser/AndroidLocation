package com.example.locationservice

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivityFuelBinding
import com.example.locationservice.databinding.DialogAddFuelBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class FuelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFuelBinding
    private lateinit var apiClient: ApiClient
    private var carsList = listOf<CarItem>()
    private var isImperial = false

    // State for attached receipt in the Add Fuel dialog
    private var attachedReceiptBytes: ByteArray? = null
    private var attachedReceiptFileName: String? = null
    private var attachedReceiptMime: String? = null
    private var attachedReceiptBitmap: Bitmap? = null
    private var currentDialogBinding: DialogAddFuelBinding? = null

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            attachedReceiptBytes = stream.toByteArray()
            attachedReceiptFileName = "receipt_${System.currentTimeMillis()}.jpg"
            attachedReceiptMime = "image/jpeg"
            attachedReceiptBitmap = bitmap
            updateReceiptPreviewInDialog()
        }
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            loadReceiptFromUri(uri)
        }
    }

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
            isImperial = apiClient.isImperialUnits()
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
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
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
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 2, 0, 4)
        }

        val tvStats = TextView(this).apply {
            val costStr = String.format(Locale.US, "$%.2f", item.totalValue ?: 0.0)
            val litStr = UnitConverter.formatVolume(item.liters ?: 0.0, isImperial)
            text = getString(R.string.fuel_card_stats, costStr, litStr)
            textSize = 14f
            setTextColor(Color.parseColor("#059669"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        textLayout.addView(tvCar)
        textLayout.addView(tvDate)
        textLayout.addView(tvStats)

        if ((item.totalKilometers ?: 0.0) > 0.0) {
            val tvEfficiency = TextView(this).apply {
                val km = UnitConverter.formatDistance(item.totalKilometers ?: 0.0, isImperial)
                val kml = UnitConverter.formatEfficiency(item.kilometerPerLiter ?: 0.0, isImperial)
                val pkm = UnitConverter.formatPricePerDistance(item.pricePerKilometer ?: 0.0, isImperial, 4)
                text = getString(R.string.fuel_card_traveled, km, kml, pkm)
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
            textLayout.addView(tvEfficiency)
        }

        val actionsCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        if (item.hasReceipt == true) {
            val btnReceipt = Button(this).apply {
                text = getString(R.string.fuel_btn_receipt)
                textSize = 11f
                setTextColor(Color.parseColor("#059669"))
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(8, 0, 8, 0)
                setOnClickListener { showReceiptDialog(item) }
            }
            actionsCol.addView(btnReceipt)
        }

        val btnCalculate = Button(this).apply {
            text = getString(R.string.fuel_btn_calculate)
            textSize = 11f
            setTextColor(Color.parseColor("#2563EB"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(8, 0, 8, 0)
            setOnClickListener { calculateFuelEfficiency(item) }
        }

        val btnDelete = Button(this).apply {
            text = getString(R.string.common_delete)
            textSize = 11f
            setTextColor(Color.parseColor("#EF4444"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(8, 0, 8, 0)
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
            Toast.makeText(this, getString(R.string.trips_register_car_first), Toast.LENGTH_LONG).show()
            return
        }

        // Reset attached receipt state
        attachedReceiptBytes = null
        attachedReceiptFileName = null
        attachedReceiptMime = null
        attachedReceiptBitmap = null

        val dialogBinding = DialogAddFuelBinding.inflate(layoutInflater)
        currentDialogBinding = dialogBinding

        // Populate cars spinner
        val names = carsList.map { it.description ?: it.licensePlate ?: "Car #${it.id}" }
        dialogBinding.spinnerVehicle.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)

        // Volume label according to unit preference
        dialogBinding.tilVolume.hint = if (isImperial) getString(R.string.fuel_hint_volume_gal) else getString(R.string.fuel_hint_volume_l)

        // Date and Time selection
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

        dialogBinding.etDate.setText(dateFormat.format(calendar.time))
        dialogBinding.etTime.setText(timeFormat.format(calendar.time))

        dialogBinding.etDate.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    calendar.set(Calendar.YEAR, year)
                    calendar.set(Calendar.MONTH, month)
                    calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    dialogBinding.etDate.setText(dateFormat.format(calendar.time))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        dialogBinding.etTime.setOnClickListener {
            val is24 = android.text.format.DateFormat.is24HourFormat(this)
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)
                    dialogBinding.etTime.setText(timeFormat.format(calendar.time))
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                is24
            ).show()
        }

        dialogBinding.btnSetNow.setOnClickListener {
            calendar.time = Date()
            dialogBinding.etDate.setText(dateFormat.format(calendar.time))
            dialogBinding.etTime.setText(timeFormat.format(calendar.time))
        }

        // Receipt photo / file attachments
        dialogBinding.btnTakePhoto.setOnClickListener {
            takePhotoLauncher.launch(null)
        }

        dialogBinding.btnChooseFile.setOnClickListener {
            pickFileLauncher.launch("*/*")
        }

        dialogBinding.btnRemoveReceipt.setOnClickListener {
            attachedReceiptBytes = null
            attachedReceiptFileName = null
            attachedReceiptMime = null
            attachedReceiptBitmap = null
            updateReceiptPreviewInDialog()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fuel_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.common_save, null) // Overridden below to validate inputs
            .setNegativeButton(R.string.common_cancel) { _, _ ->
                currentDialogBinding = null
            }
            .setOnDismissListener {
                currentDialogBinding = null
            }
            .create()

        dialog.show()

        // Override positive button click to validate inputs without closing on error
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val costStr = dialogBinding.etTotalCost.text.toString().trim()
            val volumeStr = dialogBinding.etVolume.text.toString().trim()

            val cost = costStr.toDoubleOrNull()
            val volume = volumeStr.toDoubleOrNull()

            if (cost == null || cost <= 0.0) {
                dialogBinding.etTotalCost.error = "Please enter a valid cost"
                dialogBinding.etTotalCost.requestFocus()
                return@setOnClickListener
            }

            if (volume == null || volume <= 0.0) {
                dialogBinding.etVolume.error = "Please enter a valid volume"
                dialogBinding.etVolume.requestFocus()
                return@setOnClickListener
            }

            val selectedCar = carsList[dialogBinding.spinnerVehicle.selectedItemPosition]
            val utcDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(calendar.time)

            val receiptBytesToUpload = attachedReceiptBytes
            val receiptFileNameToUpload = attachedReceiptFileName
            val receiptMimeToUpload = attachedReceiptMime

            dialog.dismiss()
            currentDialogBinding = null

            binding.progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                val res = apiClient.addFuel(
                    carId = selectedCar.id,
                    timestampUtc = utcDate,
                    totalValue = cost,
                    liters = UnitConverter.displayToLiters(volume, isImperial),
                    receiptBytes = receiptBytesToUpload,
                    receiptFileName = receiptFileNameToUpload,
                    receiptMime = receiptMimeToUpload
                )
                binding.progressBar.visibility = View.GONE
                res.onSuccess {
                    Toast.makeText(this@FuelActivity, getString(R.string.fuel_saved_success), Toast.LENGTH_SHORT).show()
                    loadFuel()
                }.onFailure { err ->
                    Toast.makeText(this@FuelActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadReceiptFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                attachedReceiptBytes = bytes
                attachedReceiptMime = contentResolver.getType(uri) ?: "image/jpeg"

                var displayName = "receipt_${System.currentTimeMillis()}"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && cursor.moveToFirst()) {
                        displayName = cursor.getString(nameIdx) ?: displayName
                    }
                }
                attachedReceiptFileName = displayName

                if (attachedReceiptMime?.startsWith("image/") == true) {
                    attachedReceiptBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else {
                    attachedReceiptBitmap = null
                }

                updateReceiptPreviewInDialog()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateReceiptPreviewInDialog() {
        val db = currentDialogBinding ?: return
        if (attachedReceiptBytes != null) {
            db.layoutReceiptPreview.visibility = View.VISIBLE
            db.tvReceiptName.text = attachedReceiptFileName ?: "receipt"
            val kb = (attachedReceiptBytes!!.size / 1024).coerceAtLeast(1)
            db.tvReceiptSize.text = "$kb KB • Ready to upload"

            if (attachedReceiptBitmap != null) {
                db.ivReceiptThumb.setImageBitmap(attachedReceiptBitmap)
            } else if (attachedReceiptMime == "application/pdf") {
                db.ivReceiptThumb.setImageResource(android.R.drawable.ic_menu_agenda)
            } else {
                db.ivReceiptThumb.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        } else {
            db.layoutReceiptPreview.visibility = View.GONE
        }
    }

    private fun showReceiptDialog(item: FuelItem) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val res = apiClient.getFuelReceipt(item.id)
            binding.progressBar.visibility = View.GONE
            res.onSuccess { (bytes, mime) ->
                if (mime?.startsWith("image/") == true) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        val iv = ImageView(this@FuelActivity).apply {
                            setImageBitmap(bitmap)
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setPadding(20, 20, 20, 20)
                        }
                        val scroll = ScrollView(this@FuelActivity).apply {
                            addView(iv)
                        }
                        MaterialAlertDialogBuilder(this@FuelActivity)
                            .setTitle(R.string.fuel_receipt_dialog_title)
                            .setView(scroll)
                            .setPositiveButton(R.string.common_close, null)
                            .show()
                        return@launch
                    }
                }

                // If PDF or other file type: Save to cache and launch external viewer via FileProvider
                try {
                    val ext = if (mime == "application/pdf") "pdf" else "jpg"
                    val file = File(cacheDir, "fuel_receipt_${item.id}.$ext")
                    file.writeBytes(bytes)
                    val uri = FileProvider.getUriForFile(this@FuelActivity, "$packageName.fileprovider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime ?: "application/octet-stream")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Open Fuel Receipt"))
                } catch (e: Exception) {
                    Toast.makeText(this@FuelActivity, "Cannot open receipt: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }.onFailure { err ->
                Toast.makeText(this@FuelActivity, "Failed to load receipt: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeleteFuel(item: FuelItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fuel_delete_title)
            .setMessage(R.string.fuel_delete_msg)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                lifecycleScope.launch {
                    val res = apiClient.deleteFuel(item.id)
                    res.onSuccess {
                        Toast.makeText(this@FuelActivity, getString(R.string.common_deleted), Toast.LENGTH_SHORT).show()
                        loadFuel()
                    }.onFailure { err ->
                        Toast.makeText(this@FuelActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
