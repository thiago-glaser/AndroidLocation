package com.example.locationservice

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.example.locationservice.databinding.ActivitySubscriptionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class SubscriptionActivity : AppCompatActivity(), BillingManager.BillingUpdatesListener {

    private lateinit var binding: ActivitySubscriptionBinding
    private lateinit var apiClient: ApiClient
    private lateinit var billingManager: BillingManager

    private var selectedProductId = BillingManager.PRODUCT_PRO_MONTHLY
    private var currentStatus: SubscriptionStatus? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient(this)
        billingManager = BillingManager(this, lifecycleScope, this)

        setupListeners()
        loadSubscriptionStatus()
        billingManager.startConnection()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.cardMonthly.setOnClickListener {
            selectPlan(BillingManager.PRODUCT_PRO_MONTHLY)
        }
        binding.radioMonthly.setOnClickListener {
            selectPlan(BillingManager.PRODUCT_PRO_MONTHLY)
        }

        binding.cardYearly.setOnClickListener {
            selectPlan(BillingManager.PRODUCT_PRO_YEARLY)
        }
        binding.radioYearly.setOnClickListener {
            selectPlan(BillingManager.PRODUCT_PRO_YEARLY)
        }

        binding.btnSubscribe.setOnClickListener {
            binding.progressBar.visibility = View.VISIBLE
            billingManager.launchPurchaseFlow(this, selectedProductId)
        }

        binding.btnManage.setOnClickListener {
            val playUrl = "https://play.google.com/store/account/subscriptions?package=$packageName"
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(playUrl))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, playUrl, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun selectPlan(productId: String) {
        selectedProductId = productId
        if (productId == BillingManager.PRODUCT_PRO_MONTHLY) {
            binding.radioMonthly.isChecked = true
            binding.radioYearly.isChecked = false
            binding.cardMonthly.strokeColor = Color.parseColor("#4F46E5")
            binding.cardMonthly.strokeWidth = dpToPx(2)
            binding.cardYearly.strokeColor = Color.parseColor("#E2E8F0")
            binding.cardYearly.strokeWidth = dpToPx(1)
        } else {
            binding.radioMonthly.isChecked = false
            binding.radioYearly.isChecked = true
            binding.cardMonthly.strokeColor = Color.parseColor("#E2E8F0")
            binding.cardMonthly.strokeWidth = dpToPx(1)
            binding.cardYearly.strokeColor = Color.parseColor("#4F46E5")
            binding.cardYearly.strokeWidth = dpToPx(2)
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun loadSubscriptionStatus() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val statusResult = withContext(Dispatchers.IO) {
                apiClient.getSubscriptionStatus()
            }
            val carsResult = withContext(Dispatchers.IO) {
                apiClient.getCars()
            }

            binding.progressBar.visibility = View.GONE

            if (statusResult.isSuccess) {
                val status = statusResult.getOrNull() ?: SubscriptionStatus()
                currentStatus = status
                val carCount = carsResult.getOrNull()?.size ?: 0
                updateUiWithStatus(status, carCount)
            }
        }
    }

    private fun updateUiWithStatus(status: SubscriptionStatus, carCount: Int) {
        binding.tvCurrentPlanName.text = status.planName
        if (status.isPro) {
            binding.tvCurrentPlanBadge.text = "PRO"
            binding.tvCurrentPlanBadge.setBackgroundColor(Color.parseColor("#4F46E5"))
            binding.tvCurrentPlanCars.text = getString(
                R.string.subscription_vehicles_limit,
                carCount,
                getString(R.string.subscription_unlimited)
            )
            binding.btnSubscribe.text = getString(R.string.subscription_active_badge)
            binding.btnSubscribe.isEnabled = false

            if (!status.expiryTime.isNullOrEmpty()) {
                binding.tvCurrentPlanExpiry.visibility = View.VISIBLE
                binding.tvCurrentPlanExpiry.text = getString(R.string.subscription_valid_until, status.expiryTime)
            } else {
                binding.tvCurrentPlanExpiry.visibility = View.GONE
            }
        } else {
            binding.tvCurrentPlanBadge.text = "FREE"
            binding.tvCurrentPlanBadge.setBackgroundColor(Color.parseColor("#64748B"))
            binding.tvCurrentPlanCars.text = getString(
                R.string.subscription_vehicles_limit,
                carCount,
                status.maxCars.toString()
            )
            binding.tvCurrentPlanExpiry.visibility = View.GONE
            binding.btnSubscribe.text = getString(R.string.subscription_btn_upgrade)
            binding.btnSubscribe.isEnabled = true
        }
    }

    // BillingManager Callbacks
    override fun onBillingSetupFinished() {
        runOnUiThread {
            billingManager.queryAvailableProducts()
        }
    }

    override fun onProductsQueried(productDetailsList: List<ProductDetails>) {
        runOnUiThread {
            for (details in productDetailsList) {
                val offer = details.subscriptionOfferDetails?.firstOrNull()
                val phase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
                val formattedPrice = phase?.formattedPrice

                if (formattedPrice != null) {
                    if (details.productId == BillingManager.PRODUCT_PRO_MONTHLY) {
                        binding.tvPriceMonthly.text = "$formattedPrice / mo"
                    } else if (details.productId == BillingManager.PRODUCT_PRO_YEARLY) {
                        binding.tvPriceYearly.text = "$formattedPrice / yr"
                    }
                }
            }
        }
    }

    override fun onPurchaseSuccess(purchase: Purchase) {
        runOnUiThread {
            binding.progressBar.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            val productId = purchase.products.firstOrNull() ?: selectedProductId
            val verifyResult = withContext(Dispatchers.IO) {
                apiClient.verifyGooglePlaySubscription(
                    purchaseToken = purchase.purchaseToken,
                    productId = productId,
                    orderId = purchase.orderId
                )
            }

            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (verifyResult.isSuccess) {
                    val updatedStatus = verifyResult.getOrNull() ?: SubscriptionStatus(isPro = true)
                    currentStatus = updatedStatus
                    loadSubscriptionStatus()

                    AlertDialog.Builder(this@SubscriptionActivity)
                        .setTitle("Delivery Trips Pro")
                        .setMessage(getString(R.string.subscription_success))
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    val err = verifyResult.exceptionOrNull()?.message ?: "Verification failed"
                    Toast.makeText(
                        this@SubscriptionActivity,
                        getString(R.string.subscription_error, err),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onPurchaseFailed(responseCode: Int, debugMessage: String) {
        runOnUiThread {
            binding.progressBar.visibility = View.GONE
            if (responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                Toast.makeText(
                    this,
                    getString(R.string.subscription_error, debugMessage),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager.destroy()
    }
}
