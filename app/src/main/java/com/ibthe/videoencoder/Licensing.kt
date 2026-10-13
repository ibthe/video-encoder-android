package com.ibthe.videoencoder

import android.content.Context

interface Licensing {
    fun isLicensed(): Boolean
    fun label(): String
    fun buttonText(): String
    fun onButton()
    fun trialLeft(): Int
    fun consumeTrial()
}

class Trial(ctx: Context) {
    private val p = ctx.getSharedPreferences("lic", Context.MODE_PRIVATE)
    val total = 5
    fun left() = maxOf(0, total - p.getInt("used", 0))
    fun consume() = p.edit().putInt("used", p.getInt("used", 0) + 1).apply()
}
