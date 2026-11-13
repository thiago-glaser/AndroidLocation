package com.example.locationservice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivityLogcatBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogcatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogcatBinding
    private var allLogLines: List<String> = emptyList()
    private var displayedText: String = ""
    private var autoRefreshJob: Job? = null
    private var selectedFilterIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogcatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupControls()
        loadLogs(scrollToBottom = true)
    }

    override fun onResume() {
        super.onResume()
        if (binding.switchAutoRefresh.isChecked) {
            startAutoRefresh()
        }
    }

    override fun onPause() {
        super.onPause()
        stopAutoRefresh()
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        val filterOptions = listOf(
            getString(R.string.logcat_filter_all),
            getString(R.string.logcat_filter_service),
            getString(R.string.logcat_filter_boot),
            getString(R.string.logcat_filter_bluetooth),
            getString(R.string.logcat_filter_errors)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, filterOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerLogFilter.adapter = adapter
        binding.spinnerLogFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedFilterIndex = position
                applyFilterAndRender(scrollToBottom = true)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.etSearchLogs.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilterAndRender(scrollToBottom = false)
            }
        })

        binding.btnRefreshLogs.setOnClickListener {
            loadLogs(scrollToBottom = true)
        }

        binding.switchAutoRefresh.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startAutoRefresh() else stopAutoRefresh()
        }

        binding.btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Delivery Trips Logcat", displayedText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.logcat_copied_toast), Toast.LENGTH_SHORT).show()
        }

        binding.btnShareLogs.setOnClickListener {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Delivery Trips Diagnostic Logs")
                putExtra(Intent.EXTRA_TEXT, displayedText)
            }
            startActivity(Intent.createChooser(sendIntent, getString(R.string.logcat_btn_share)))
        }

        binding.btnClearLogs.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.logcat_clear_confirm_title)
                .setMessage(R.string.logcat_clear_confirm_msg)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.logcat_btn_clear) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppLogger.clearLogs(this@LogcatActivity)
                        AppLogger.i("LogcatActivity", "Logs cleared by admin user")
                        withContext(Dispatchers.Main) {
                            loadLogs(scrollToBottom = true)
                        }
                    }
                }
                .show()
        }
    }

    private fun startAutoRefresh() {
        stopAutoRefresh()
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(2500L)
                loadLogs(scrollToBottom = false)
            }
        }
    }

    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    private fun loadLogs(scrollToBottom: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val lines = AppLogger.readCombinedLogs(this@LogcatActivity, maxLines = 1500)
            withContext(Dispatchers.Main) {
                val previousSize = allLogLines.size
                allLogLines = lines
                applyFilterAndRender(scrollToBottom = scrollToBottom || lines.size != previousSize)
            }
        }
    }

    private fun applyFilterAndRender(scrollToBottom: Boolean) {
        val query = binding.etSearchLogs.text?.toString()?.trim() ?: ""

        val filtered = allLogLines.filter { line ->
            val matchesCategory = when (selectedFilterIndex) {
                1 -> line.contains("LocationService", ignoreCase = true) || line.contains("LocationApp", ignoreCase = true)
                2 -> line.contains("BootReceiver", ignoreCase = true) || line.contains("ServiceWatchdog", ignoreCase = true)
                3 -> line.contains("Bluetooth", ignoreCase = true)
                4 -> line.contains(" E/") || line.contains(" W/") || line.contains("Exception", ignoreCase = true) || line.contains("ERROR", ignoreCase = true)
                else -> true
            }
            val matchesSearch = query.isEmpty() || line.contains(query, ignoreCase = true)
            matchesCategory && matchesSearch
        }

        binding.tvLogCount.text = getString(R.string.logcat_line_count, filtered.size, allLogLines.size)
        displayedText = if (filtered.isEmpty()) {
            getString(R.string.logcat_empty)
        } else {
            filtered.joinToString("\n")
        }

        binding.tvLogcatOutput.text = displayedText

        if (scrollToBottom) {
            binding.scrollViewLogcat.post {
                binding.scrollViewLogcat.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
}

