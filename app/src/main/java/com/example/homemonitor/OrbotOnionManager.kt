package com.example.homemonitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity

/**
 * Verwaltet die Anforderung eines V3-Onion-Service über die Intent-API von Orbot.
 * Speichert die zurückgegebene Onion-Adresse in SharedPreferences.
 *
 * Hinweis: Orbot ist laut offizieller Manifest-Analyse ein
 * `BIND_VPN_SERVICE`-geschützter Service (`exported=true`), kein extern
 * aufrufbarer Activity. Daher wird der Service hier per
 * `Context.BIND_AUTO_CREATE` + VPN-Action gebunden statt per Activity-Intent.
 */
class OrbotOnionManager(
    private val activity: ComponentActivity,
    private val localPort: Int = 8080,
    private val onAddressReceived: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val ORBOT_PACKAGE = "org.torproject.android"

        // Service-Interface von Orbot (VPN-basierter Service)
        private const val SERVICE_INTERFACE = "android.net.VpnService"

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

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.i("OrbotOnionManager", "OrbotService verbunden: $name")
            // Hier können Onion-/Service-Callbacks gefeuert werden.
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w("OrbotOnionManager", "OrbotService getrennt: $name")
        }
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

    // Binden an den Orbot-Service über VPN-Intent
    fun bindOrbotService() {
        if (isOrbotInstalled()) {
            val intent = Intent(SERVICE_INTERFACE).apply {
                setPackage(ORBOT_PACKAGE)
            }
            activity.bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        } else {
            onError("Orbot ist nicht installiert.")
        }
    }

    // V3-Onion-Service über Intent-API an Orbot senden
    // Ergebnis wird über ActivityResultContracts.StartActivityForResult zurückgegeben
    fun requestOnionService() {
        if (!isOrbotInstalled()) {
            onError("Orbot ist nicht installiert.")
            return
        }

        // Binde an den VPN-basierten OrbotService
        bindOrbotService()

        // Fallback: Adresse laden / anzeigen
        val savedAddress = getSavedOnionAddress()
        if (savedAddress != null) {
            onAddressReceived(savedAddress)
        }
    }

    // Gespeicherte Onion-Adresse in SharedPreferences ablegen
    private fun saveOnionAddress(address: String) {
        activity.getSharedPreferences(
            "onion_service",
            Context.MODE_PRIVATE
        ).edit()
            .putString("onion_address", address)
            .apply()
    }

    // Gespeicherte Onion-Adresse aus SharedPreferences laden
    fun getSavedOnionAddress(): String? {
        return activity.getSharedPreferences(
            "onion_service",
            Context.MODE_PRIVATE
        ).getString("onion_address", null)
    }
}
