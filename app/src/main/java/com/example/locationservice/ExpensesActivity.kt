package com.example.locationservice

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
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
import com.example.locationservice.databinding.ActivityExpensesBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ExpensesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExpensesBinding
    private lateinit var apiClient: ApiClient
    private var carsList = listOf<CarItem>()
    private var expenseTypesList = mutableListOf<ExpenseTypeItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExpensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddExpense.setOnClickListener { showAddExpenseDialog() }

        loadExpenses()
    }

    private fun loadExpenses() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE
        binding.containerExpenses.removeAllViews()

        lifecycleScope.launch {
            val carsResult = apiClient.getCars()
            carsList = carsResult.getOrDefault(emptyList())

            val typesResult = apiClient.getExpenseTypes()
            expenseTypesList = typesResult.getOrDefault(emptyList()).toMutableList()

            val result = apiClient.getExpenses()
            binding.progressBar.visibility = View.GONE

            result.onSuccess { expenses ->
                if (expenses.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                } else {
                    expenses.forEach { item ->
                        addExpenseView(item)
                    }
                }
            }.onFailure { err ->
                Toast.makeText(this@ExpensesActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                binding.tvEmptyState.apply {
                    text = "Failed to load expenses: ${err.message}"
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun addExpenseView(item: ExpenseItem) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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

        // Top Row: Vehicle name & Expense Type badge
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val carLabel = item.carDescription ?: item.carLicensePlate ?: "Car #${item.carId ?: ""}"
        val tvCar = TextView(this).apply {
            text = carLabel
            textSize = 16f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val typeLabel = item.expenseTypeName ?: "General"
        val tvTypeBadge = TextView(this).apply {
            text = typeLabel
            textSize = 12f
            setTextColor(Color.parseColor("#0D9488"))
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_badge)
            setPadding(16, 6, 16, 6)
        }

        headerRow.addView(tvCar)
        headerRow.addView(tvTypeBadge)

        // Middle Row: Description
        val tvDescription = TextView(this).apply {
            text = item.description ?: "No description"
            textSize = 14f
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 10, 0, 8)
        }

        // Bottom Row: Date, Amount, and Delete button
        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val formattedDate = try {
            val raw = item.expenseDate?.substringBefore("Z") ?: ""
            val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val outFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val d = inFmt.parse(raw)
            if (d != null) outFmt.format(d) else item.expenseDate ?: "N/A"
        } catch (e: Exception) {
            item.expenseDate ?: "N/A"
        }

        val tvDate = TextView(this).apply {
            text = formattedDate
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val costStr = String.format(Locale.US, "$%.2f", item.amount ?: 0.0)
        val tvAmount = TextView(this).apply {
            text = costStr
            textSize = 15f
            setTextColor(Color.parseColor("#0D9488"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 16, 0)
        }

        val btnDelete = Button(this).apply {
            text = "Delete"
            textSize = 12f
            setTextColor(Color.parseColor("#EF4444"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { confirmDeleteExpense(item) }
        }

        footerRow.addView(tvDate)
        footerRow.addView(tvAmount)
        footerRow.addView(btnDelete)

        card.addView(headerRow)
        card.addView(tvDescription)
        card.addView(footerRow)

        binding.containerExpenses.addView(card)
    }

    private fun showAddExpenseDialog() {
        if (carsList.isEmpty()) {
            Toast.makeText(this, "Please register a car first in Manage Cars", Toast.LENGTH_LONG).show()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        // Vehicle selector
        val carSpinner = Spinner(this).apply {
            val names = carsList.map { it.description ?: it.licensePlate ?: "Car #${it.id}" }
            adapter = ArrayAdapter(this@ExpensesActivity, android.R.layout.simple_spinner_dropdown_item, names)
        }

        // Expense Type selector (with option to add new type)
        val typeOptions = mutableListOf<String>()
        expenseTypesList.forEach { typeOptions.add(it.name) }
        typeOptions.add("+ Add new type...")

        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeOptions)
        val typeSpinner = Spinner(this).apply {
            adapter = typeAdapter
        }

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == typeOptions.size - 1) {
                    // Selected "+ Add new type..."
                    showCreateTypeDialog { newType ->
                        expenseTypesList.add(newType)
                        typeOptions.add(typeOptions.size - 1, newType.name)
                        typeAdapter.notifyDataSetChanged()
                        typeSpinner.setSelection(typeOptions.size - 2)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val amountInput = EditText(this).apply {
            hint = "Amount in $ (e.g. 45.00)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val descriptionInput = EditText(this).apply {
            hint = "Description (e.g. Oil change, Toll, Parking)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }

        val calendar = Calendar.getInstance()
        val dateDisplayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dateInput = EditText(this).apply {
            setText(dateDisplayFmt.format(calendar.time))
            isFocusable = false
            setOnClickListener {
                DatePickerDialog(
                    this@ExpensesActivity,
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
        layout.addView(TextView(this).apply { text = "Expense Type:"; setTextColor(Color.parseColor("#334155")); setPadding(0, 12, 0, 0) })
        layout.addView(typeSpinner)
        layout.addView(TextView(this).apply { text = "Amount ($):"; setTextColor(Color.parseColor("#334155")); setPadding(0, 12, 0, 0) })
        layout.addView(amountInput)
        layout.addView(TextView(this).apply { text = "Description:"; setTextColor(Color.parseColor("#334155")); setPadding(0, 12, 0, 0) })
        layout.addView(descriptionInput)
        layout.addView(TextView(this).apply { text = "Date (Tap to change):"; setTextColor(Color.parseColor("#334155")); setPadding(0, 12, 0, 0) })
        layout.addView(dateInput)

        AlertDialog.Builder(this)
            .setTitle("Log Vehicle Expense")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val amount = amountInput.text.toString().toDoubleOrNull() ?: 0.0
                val desc = descriptionInput.text.toString().trim()

                if (amount <= 0.0) {
                    Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (desc.isEmpty()) {
                    Toast.makeText(this, "Please enter a description", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val selectedCar = carsList[carSpinner.selectedItemPosition]
                val selectedTypeIndex = typeSpinner.selectedItemPosition
                if (selectedTypeIndex >= expenseTypesList.size) {
                    Toast.makeText(this, "Please select a valid expense type", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val selectedType = expenseTypesList[selectedTypeIndex]

                val isoDate = SimpleDateFormat("yyyy-MM-dd'T'12:00:00.000'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(calendar.time)

                lifecycleScope.launch {
                    val res = apiClient.addExpense(
                        carId = selectedCar.id,
                        expenseTypeId = selectedType.id,
                        expenseDateIso = isoDate,
                        amount = amount,
                        description = desc
                    )
                    res.onSuccess {
                        Toast.makeText(this@ExpensesActivity, "Expense saved", Toast.LENGTH_SHORT).show()
                        loadExpenses()
                    }.onFailure { err ->
                        Toast.makeText(this@ExpensesActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateTypeDialog(onCreated: (ExpenseTypeItem) -> Unit) {
        val input = EditText(this).apply {
            hint = "e.g. Parking, Tolls, Insurance"
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("New Expense Type")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        val result = apiClient.addExpenseType(name)
                        result.onSuccess {
                            // Reload types to get ID
                            val typesRes = apiClient.getExpenseTypes()
                            typesRes.onSuccess { freshTypes ->
                                expenseTypesList = freshTypes.toMutableList()
                                val found = freshTypes.firstOrNull { it.name.equals(name, ignoreCase = true) }
                                    ?: ExpenseTypeItem(name, name, null)
                                onCreated(found)
                                Toast.makeText(this@ExpensesActivity, "Created type: $name", Toast.LENGTH_SHORT).show()
                            }
                        }.onFailure { err ->
                            Toast.makeText(this@ExpensesActivity, "Failed to create type: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteExpense(item: ExpenseItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Expense")
            .setMessage("Are you sure you want to delete this expense record?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val res = apiClient.deleteExpense(item.id)
                    res.onSuccess {
                        Toast.makeText(this@ExpensesActivity, "Deleted", Toast.LENGTH_SHORT).show()
                        loadExpenses()
                    }.onFailure { err ->
                        Toast.makeText(this@ExpensesActivity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
