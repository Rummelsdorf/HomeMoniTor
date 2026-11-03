package com.example.homemonitor

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Verwaltet die Anforderung eines V3-Onion-Service über die Intent-API von Orbot.
 * Speichert die zurückgegebene Onion-Adresse in SharedPreferences.
 */
class OrbotOnionManager(
    private val activity: ComponentActivity,
    private val localPort: Int = 8080,
    private val onAddressReceived: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val ORBOT_PACKAGE = "org.torproject.android"

        private const val ACTION_REQUEST_V3 =
            "org.torproject.android.REQUEST_V3_ONION_SERVICE"

        // Orbot liefert den Hostnamen je nach Version unter unterschiedlichen Extra-Keys.
        private const val EXTRA_HOSTNAME =
            "org.torproject.android.REQUESTED_V3_HOSTNAME"

        private val HOSTNAME_KEYS = arrayOf(
            "hs_hostname",
            "hostname",
            "onion",
            "onion_address",
            "org.torproject.android.REQUESTED_V3_HOSTNAME"
        )

        private const val ONION_PORT = 80
    }

    private val launcher =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode != Activity.RESULT_OK) {
                onError("Orbot hat die Anfrage nicht bestätigt.")
                return@registerForActivityResult
            }

            val data = result.data
            var onionAddress: String? = null
            for (key in HOSTNAME_KEYS) {
                val v = data?.getStringExtra(key)
                if (!v.isNullOrBlank() && v.trim().endsWith(".onion")) {
                    onionAddress = v.trim()
                    break
                }
            }

            if (
                onionAddress.isNullOrBlank() ||
                !onionAddress.endsWith(".onion")
            ) {
                onError("Keine gültige Onion-Adresse zurückgegeben.")
                return@registerForActivityResult
            }

            // Nach der obigen Prüfung ist die Adresse garantiert nicht null.
            val resolved = onionAddress!!
            saveOnionAddress(resolved)
            onAddressReceived(resolved)
        }

    // Orbot-Installation prüfen (Package Visibility nötig)
    fun isOrbotInstalled(): Boolean {
        return try {
            activity.packageManager.getPackageInfo(
                ORBOT_PACKAGE,
                0
            )
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    // V3-Onion-Service über Intent-API an Orbot senden
    // Ergebnis wird über ActivityResultContracts.StartActivityForResult zurückgegeben
    fun requestOnionService() {
        if (!isOrbotInstalled()) {
            onError("Orbot ist nicht installiert.")
            return
        }

        val intent = Intent(ACTION_REQUEST_V3).apply {
            setPackage(ORBOT_PACKAGE)

            putExtra("localPort", localPort)
            putExtra("onionPort", ONION_PORT)
            putExtra("name", "Meine Android App")
        }

        try {
            launcher.launch(intent)
        } catch (e: Exception) {
            onError("Orbot konnte nicht gestartet werden: ${e.message}")
        }
    }

    // Gespeicherte Onion-Adresse in SharedPreferences ablegen
    private fun saveOnionAddress(address: String) {
        activity.getSharedPreferences(
            "onion_service",
            Activity.MODE_PRIVATE
        ).edit()
            .putString("onion_address", address)
            .apply()
    }

    // Gespeicherte Onion-Adresse aus SharedPreferences laden
    fun getSavedOnionAddress(): String? {
        return activity.getSharedPreferences(
            "onion_service",
            Activity.MODE_PRIVATE
        ).getString("onion_address", null)
    }
}
