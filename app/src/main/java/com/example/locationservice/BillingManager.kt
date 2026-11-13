package com.example.locationservice

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BillingManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: BillingUpdatesListener
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_PRO_MONTHLY = "travelaccess_pro_monthly"
        const val PRODUCT_PRO_YEARLY = "travelaccess_pro_yearly"
    }

    interface BillingUpdatesListener {
        fun onBillingSetupFinished()
        fun onProductsQueried(productDetailsList: List<ProductDetails>)
        fun onPurchaseSuccess(purchase: Purchase)
        fun onPurchaseFailed(responseCode: Int, debugMessage: String)
    }

    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .build()

    private var isConnected = false
    private val cachedProductDetails = mutableMapOf<String, ProductDetails>()

    fun startConnection() {
        if (isConnected) return

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    isConnected = true
                    Log.d(TAG, "BillingClient connected successfully")
                    queryAvailableProducts()
                    queryActivePurchases()
                    listener.onBillingSetupFinished()
                } else {
                    Log.w(TAG, "BillingClient setup failed: ${billingResult.debugMessage}")
                    listener.onPurchaseFailed(billingResult.responseCode, billingResult.debugMessage)
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnected = false
                Log.w(TAG, "BillingClient disconnected. Reconnecting...")
            }
        })
    }

    fun queryAvailableProducts() {
        if (!isConnected) {
            startConnection()
            return
        }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_PRO_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_PRO_YEARLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                cachedProductDetails.clear()
                for (details in productDetailsList) {
                    cachedProductDetails[details.productId] = details
                    Log.d(TAG, "Found product: ${details.productId} - ${details.name}")
                }
                scope.launch(Dispatchers.Main) {
                    listener.onProductsQueried(productDetailsList)
                }
            } else {
                Log.w(TAG, "Failed to query products: ${billingResult.debugMessage}")
            }
        }
    }

    fun launchPurchaseFlow(activity: Activity, productId: String) {
        val productDetails = cachedProductDetails[productId]
        if (productDetails == null) {
            Log.e(TAG, "Product details not found for $productId")
            listener.onPurchaseFailed(
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                "Product details not loaded yet"
            )
            return
        }

        val offerDetails = productDetails.subscriptionOfferDetails?.firstOrNull()
        if (offerDetails == null) {
            Log.e(TAG, "No offer details found for $productId")
            listener.onPurchaseFailed(
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                "No subscription offer found"
            )
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .setOfferToken(offerDetails.offerToken)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val responseCode = billingClient.launchBillingFlow(activity, billingFlowParams).responseCode
        if (responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Failed to launch billing flow: response code $responseCode")
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    for (purchase in purchases) {
                        handlePurchase(purchase)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "User canceled purchase")
            }
            else -> {
                Log.e(TAG, "Purchase error: ${billingResult.responseCode} - ${billingResult.debugMessage}")
                listener.onPurchaseFailed(billingResult.responseCode, billingResult.debugMessage)
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.d(TAG, "Purchase acknowledged successfully")
                    } else {
                        Log.w(TAG, "Failed to acknowledge purchase: ${billingResult.debugMessage}")
                    }
                }
            }

            scope.launch(Dispatchers.Main) {
                listener.onPurchaseSuccess(purchase)
            }
        }
    }

    fun queryActivePurchases() {
        if (!isConnected) return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (purchase in purchasesList) {
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        handlePurchase(purchase)
                    }
                }
            }
        }
    }

    fun destroy() {
        if (isConnected) {
            billingClient.endConnection()
            isConnected = false
        }
    }
}
