package com.example.homemonitor

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class OrbotOnionManager(
    private val activity: ComponentActivity,
    private val onAddressReceived: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val ORBOT_PACKAGE = "org.torproject.android"

        private const val ACTION_REQUEST_V3 =
            "org.torproject.android.REQUEST_V3_ONION_SERVICE"

        private const val EXTRA_HOSTNAME =
            "org.torproject.android.REQUESTED_V3_HOSTNAME"

        private const val LOCAL_PORT = 8080
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

            val onionAddress =
                result.data?.getStringExtra(EXTRA_HOSTNAME)

            if (
                onionAddress.isNullOrBlank() ||
                !onionAddress.endsWith(".onion")
            ) {
                onError("Keine gültige Onion-Adresse zurückgegeben.")
                return@registerForActivityResult
            }

            saveOnionAddress(onionAddress)
            onAddressReceived(onionAddress)
        }

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

    fun requestOnionService() {
        if (!isOrbotInstalled()) {
            onError("Orbot ist nicht installiert.")
            return
        }

        val intent = Intent(ACTION_REQUEST_V3).apply {
            setPackage(ORBOT_PACKAGE)

            putExtra("localPort", LOCAL_PORT)
            putExtra("onionPort", ONION_PORT)
            putExtra("name", "Meine Android App")
        }

        try {
            launcher.launch(intent)
        } catch (e: Exception) {
            onError("Orbot konnte nicht gestartet werden: ${e.message}")
        }
    }

    private fun saveOnionAddress(address: String) {
        activity.getSharedPreferences(
            "onion_service",
            Activity.MODE_PRIVATE
        ).edit()
            .putString("onion_address", address)
            .apply()
    }

    fun getSavedOnionAddress(): String? {
        return activity.getSharedPreferences(
            "onion_service",
            Activity.MODE_PRIVATE
        ).getString("onion_address", null)
    }
}
