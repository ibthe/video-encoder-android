package com.ibthe.videoencoder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject

private val PUB = "97380e89d34e914e8bb4856b053b739ec408b2483d7bea8070a370cd7f80ef93".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class DirectLicensing(private val a: AppCompatActivity, private val refresh: () -> Unit) : Licensing {
    private val prefs = a.getSharedPreferences("lic", 0)
    private val trial = Trial(a)
    private val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    private fun email(): String? = prefs.getString("key", null)?.let { verify(it) }

    // ID этого устройства: 16 символов, ключ выдаётся только под него
    private fun deviceCode(): String {
        val id = Settings.Secure.getString(a.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(("video-encoder:" + id).toByteArray())
        return h.joinToString("") { "%02X".format(it) }.take(16)
    }
    private fun pretty(c: String) = c.chunked(4).joinToString("-")

    private fun verify(key: String): String? = try {
        val parts = key.trim().split(".")
        val data = parts[0].toByteArray()
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(PUB, 0))
        signer.update(data, 0, data.size)
        if (!signer.verifySignature(Base64.decode(parts[1], flags))) null
        else {
            val j = JSONObject(String(Base64.decode(parts[0], flags)))
            val d = j.optString("d").filter { it.isLetterOrDigit() }.uppercase()
            if (d == deviceCode()) j.getString("e") else null
        }
    } catch (e: Exception) { null }

    override fun isLicensed() = email() != null
    override fun label() = email()?.let { a.getString(R.string.licensed, it) }
        ?: a.getString(R.string.trial, trial.left(), trial.total)
    override fun buttonText() = a.getString(R.string.enter_key)
    override fun trialLeft() = trial.left()
    override fun consumeTrial() = trial.consume()

    override fun onButton() {
        val d = a.resources.displayMetrics.density
        val input = EditText(a).apply {
            hint = a.getString(R.string.paste_key); setSingleLine()
            setTextColor(Color.WHITE); setHintTextColor(0x809A9AB0.toInt())
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * d; setColor(0x14FFFFFF); setStroke((1 * d).toInt(), 0x26FFFFFF)
            }
        }
        val holder = FrameLayout(a).apply {
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
            addView(input)
        }
        val id = pretty(deviceCode())
        AlertDialog.Builder(a)
            .setTitle(R.string.enter_key)
            .setMessage(a.getString(R.string.dev_hint, id))
            .setView(holder)
            .setPositiveButton(R.string.activate) { _, _ ->
                val k = input.text.toString()
                if (verify(k) != null) { prefs.edit().putString("key", k.trim()).apply(); refresh() }
                else Toast.makeText(a, R.string.bad_key, Toast.LENGTH_LONG).show()
            }
            .setNeutralButton(R.string.copy_id) { _, _ ->
                (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("device id", id))
                Toast.makeText(a, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}

fun createLicensing(a: AppCompatActivity, refresh: () -> Unit): Licensing = DirectLicensing(a, refresh)
