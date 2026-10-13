package com.ibthe.videoencoder

import androidx.appcompat.app.AppCompatActivity
import com.android.billingclient.api.*

// Идентификатор товара в Google Play Console (разовая покупка)
private const val PRODUCT_ID = "full_license"

class PlayLicensing(private val a: AppCompatActivity, private val refresh: () -> Unit) :
    Licensing, PurchasesUpdatedListener {
    private val prefs = a.getSharedPreferences("lic", 0)
    private val trial = Trial(a)
    private val client = BillingClient.newBuilder(a)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    init {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) query()
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun query() {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { _, list -> handle(list) }
    }

    private fun handle(list: List<Purchase>) {
        for (p in list) {
            if (p.products.contains(PRODUCT_ID) && p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                if (!p.isAcknowledged) {
                    client.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
                    ) {}
                }
                prefs.edit().putBoolean("owned", true).apply()
                a.runOnUiThread(refresh)
            }
        }
    }

    override fun onPurchasesUpdated(r: BillingResult, list: MutableList<Purchase>?) {
        if (r.responseCode == BillingClient.BillingResponseCode.OK && list != null) handle(list)
    }

    override fun isLicensed() = prefs.getBoolean("owned", false)
    override fun label() = if (isLicensed()) a.getString(R.string.licensed_play)
        else a.getString(R.string.trial, trial.left(), trial.total)
    override fun buttonText() = a.getString(R.string.buy)
    override fun trialLeft() = trial.left()
    override fun consumeTrial() = trial.consume()

    override fun onButton() {
        val q = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build())
        ).build()
        client.queryProductDetailsAsync(q) { _, details ->
            val d = details.firstOrNull() ?: return@queryProductDetailsAsync
            val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build())
            ).build()
            a.runOnUiThread { client.launchBillingFlow(a, params) }
        }
    }
}

fun createLicensing(a: AppCompatActivity, refresh: () -> Unit): Licensing = PlayLicensing(a, refresh)
