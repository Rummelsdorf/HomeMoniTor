package com.example.homemonitor

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import android.util.Log
import java.io.*
import java.net.Socket
import android.security.keystore.KeyGenParameterSpec
import java.security.KeyPairGenerator
import kotlin.concurrent.thread

/**
 * Best-Effort-Integration mit Orbot (Tor für Android).
 *
 * Orbot stellt standardmäßig einen Tor-Control-Port bereit.
 * Diese Klasse versucht, einen Hidden Service (ADD_ONION) anzulegen,
 * der den lokalen Webserver (Port 8080) ins Tor-Netzwerk exponiert.
 *
 * Verwendet einen persistenten Onion-Schlüssel (pro Installation), damit die
 * Onion-Adresse stabil bleibt. Fallback: ephemeral (ohne Schlüssel).
 *
 * Falls Orbot nicht läuft oder das Authentifizierungsschema nicht passt, bleibt
 * die Onion-Adresse leer. In diesem Fall muss der Nutzer in Orbot manuell unter
 * „Einstellungen -> Versteckte Dienste“ einen Dienst einrichten:
 *   Virtueller Port: 8080  ->  127.0.0.1:8080
 *   Ziel-IP: 127.0.0.1
 * Starte Orbot neu. Die App verwendet nun diesen Tunnel.
 */
class OrbotHiddenService private constructor(private val context: Context) {

    @Volatile var address: String? = null
    private var serviceId: String? = null
    @Volatile private var running: Boolean = false

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            "homemonitor_prefs",
            "homemonitor_prefs_master",
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        android.util.Log.w("OrbotHiddenService", "EncryptedSharedPreferences failed, using fallback", e)
        context.getSharedPreferences("homemonitor_fallback", Context.MODE_PRIVATE)
    }

    companion object {
        @Volatile private var instance: OrbotHiddenService? = null

        private const val KEY_ONION_KEY = "onion_key_v1"
        private const val ONION_LIFETIME_SECONDS = 31536000
        private const val ORBOT_CONTROL_PORT = 8118

        fun init(context: Context): OrbotHiddenService {
            return instance ?: synchronized(this) {
                instance ?: OrbotHiddenService(context.applicationContext).also { instance = it }
            }
        }

        fun get(): OrbotHiddenService? = instance
    }

    fun start() {
        if (running) return
        running = true
        thread(name = "OrbotHiddenService", isDaemon = true) {
            tryAddress()
        }
    }

    fun stop() {
        running = false
        val currentServiceId = serviceId
        address = null
        serviceId = null
        if (currentServiceId != null) {
            // Hidden Service beim Stop über den Tor Control Port entfernen
            try {
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress("127.0.0.1", ORBOT_CONTROL_PORT), 3000)
                socket.soTimeout = 5000
                val out = BufferedWriter(OutputStreamWriter(socket.outputStream, "UTF-8"))
                val reader = BufferedReader(InputStreamReader(socket.inputStream, "UTF-8"))
                readUntil(reader, "250")
                out.write("DELONION $currentServiceId")
                out.write("\r\n")
                out.flush()
                val resp = readLine(reader)
                Log.d("OrbotHiddenService", "DELONION Antwort: $resp")
                socket.close()
            } catch (e: Exception) {
                Log.w("OrbotHiddenService", "DELONION fehlgeschlagen", e)
            }
        }
        address = null
        serviceId = null
    }

    private fun getOrCreateOnionKey(): String? {
        prefs.getString(KEY_ONION_KEY, null)?.let { return it }
        return try {
            val keyGen = KeyPairGenerator.getInstance("RSA")
            val spec = KeyGenParameterSpec.Builder("homemonitor", 1024).build()
            keyGen.initialize(spec)
            val keyPair = keyGen.generateKeyPair()
            val encoded = android.util.Base64.encodeToString(
                keyPair.private.encoded,
                android.util.Base64.DEFAULT
            )
            prefs.edit().putString(KEY_ONION_KEY, encoded).apply()
            encoded
        } catch (e: Exception) {
            null
        }
    }

    private fun tryAddress() {
        // 1) Versuch über Orbot Control Port (8118); mit persistentem Key für stabile Adresse
        address = tryControlPort(getOrCreateOnionKey())
        if (address != null) {
            Log.i("OrbotHiddenService", "Hidden Service aktiv: $address")
            return
        }
        Log.w("OrbotHiddenService", "Orbot Control Port nicht erreichbar / AUTH fehlgeschlagen.")
    }

    private fun tryControlPort(onionKey: String?): String? {
        val socket = Socket()
        try {
            socket.connect(java.net.InetSocketAddress("127.0.0.1", ORBOT_CONTROL_PORT), 3000)
            socket.soTimeout = 5000
            val out = BufferedWriter(OutputStreamWriter(socket.outputStream, "UTF-8"))
            val reader = BufferedReader(InputStreamReader(socket.inputStream, "UTF-8"))

            // Willkommenszeile(n) lesen
            readUntil(reader, "250")

            // AUTHENTICATE ohne Passwort versuchen
            out.write("AUTHENTICATE \"\"\r\n")
            out.flush()
            val authResp = readLine(reader)
            Log.d("OrbotHiddenService", "AUTH: $authResp")

            // ADD_ONION: Hidden Service, Port 8080 -> localhost:8080
            // Mit persistiertem Key: stabile Onion-Adresse (v2). Ohne Key: ephemeral.
            out.write(if (onionKey != null) "ADD_ONION $onionKey ${ONION_LIFETIME_SECONDS}\r\n" else "ADD_ONION Port=8080,127.0.0.1:8080\r\n")
            out.flush()

            var onion: String? = null
            var done = false
            while (!done) {
                val line = readLine(reader) ?: break
                Log.d("OrbotHiddenService", "CTRL: $line")
                if (line.startsWith("250-service-id=")) {
                    serviceId = line.substringAfter("250-service-id=").trim()
                }
                if (line.startsWith("250-") && line.contains(".onion")) {
                    // Manche Tor-Versionen liefern die Adresse direkt mit
                    val token = line.split(" ").firstOrNull { it.endsWith(".onion") }
                    if (token != null) onion = token
                }
                if (line.startsWith("250 OK")) done = true
                if (line.startsWith("550") || line.startsWith("551")) break
            }

            if (onion == null && serviceId != null) {
                onion = "$serviceId.onion"
            }
            return onion
        } catch (e: Exception) {
            Log.w("OrbotHiddenService", "Control-Port-Versuch fehlgeschlagen", e)
            return null
        } finally {
            try { socket.close() } catch (e: Exception) { }
        }
    }

    private fun readLine(reader: BufferedReader): String? {
        return try { reader.readLine() } catch (e: Exception) { null }
    }

    private fun readUntil(reader: BufferedReader, prefix: String) {
        while (true) {
            val line = readLine(reader) ?: return
            if (line.startsWith(prefix)) return
        }
    }
}