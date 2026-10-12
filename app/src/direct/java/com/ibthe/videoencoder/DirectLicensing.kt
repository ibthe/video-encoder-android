package com.ibthe.videoencoder

import android.util.Base64
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject

private val PUB = "011f278e7d6e025c907f4262601a69e17717e6441a6560280f9e29f2e8677cdf".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class DirectLicensing(private val a: AppCompatActivity, private val refresh: () -> Unit) : Licensing {
    private val prefs = a.getSharedPreferences("lic", 0)
    private val trial = Trial(a)
    private val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    private fun email(): String? = prefs.getString("key", null)?.let { verify(it) }

    private fun verify(key: String): String? = try {
        val parts = key.trim().split(".")
        val data = parts[0].toByteArray()
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(PUB, 0))
        signer.update(data, 0, data.size)
        if (signer.verifySignature(Base64.decode(parts[1], flags)))
            JSONObject(String(Base64.decode(parts[0], flags))).getString("e") else null
    } catch (e: Exception) { null }

    override fun isLicensed() = email() != null
    override fun label() = email()?.let { a.getString(R.string.licensed, it) }
        ?: a.getString(R.string.trial, trial.left(), trial.total)
    override fun buttonText() = a.getString(R.string.enter_key)
    override fun trialLeft() = trial.left()
    override fun consumeTrial() = trial.consume()

    override fun onButton() {
        val input = EditText(a).apply { hint = a.getString(R.string.paste_key); setSingleLine() }
        AlertDialog.Builder(a).setView(input)
            .setPositiveButton(R.string.activate) { _, _ ->
                val k = input.text.toString()
                if (verify(k) != null) { prefs.edit().putString("key", k.trim()).apply(); refresh() }
                else Toast.makeText(a, R.string.bad_key, Toast.LENGTH_SHORT).show()
            }.setNegativeButton(R.string.cancel, null).show()
    }
}

fun createLicensing(a: AppCompatActivity, refresh: () -> Unit): Licensing = DirectLicensing(a, refresh)
