package com.example.homemonitor

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Tor-Integration: Steuert den lokalen Tor-Daemon direkt über das
 * Tor Control Protocol (TCP), um einen Hidden Service (ADD_ONION) zu
 * erstellen, der den lokalen Webserver (Port) exponiert.
 *
 * Damit ist keine Orbot-Intent/API mehr nötig – Tor wird direkt integriert.
 */
class TorManager(
    private val context: Context,
    private val localPort: Int = 8080
) {
    @Volatile var address: String? = null
    private var serviceId: String? = null
    @Volatile private var running: Boolean = false
    private var onAddressReady: ((String) -> Unit)? = null

    fun setOnAddressReady(l: (String) -> Unit) {
        onAddressReady = l
    }

    companion object {
        @Volatile private var instance: TorManager? = null

        private const val TOR_CONTROL_PORT = 9051
        private const val KEY_ONION_KEY = "tor_onion_key_v1"
        private const val ONION_LIFETIME_SECONDS = 31536000

        fun init(context: Context): TorManager = instance ?: synchronized(this) {
            instance ?: TorManager(context).also { instance = it }
        }

        fun get(): TorManager? = instance
    }

    fun start(): Boolean {
        if (running) return true
        running = true
        thread(name = "TorManager", isDaemon = true) { tryAddress() }
        return true
    }

    fun stop() {
        running = false
        address = null
        serviceId = null
    }

    // Tor Control Port anfragen und Hidden Service (ADD_ONION) erstellen
    fun requestOnionService(): String? {
        if (running) return address
        running = true
        thread(name = "TorManager", isDaemon = true) { tryAddress() }
        // Address wird async gesetzt; hier Rückgabe über Saved-Address/Callback
        return address
    }

    private fun getOrCreateOnionKey(): String? {
        return try {
            val keyGen = java.security.KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(1024)
            val keyPair = keyGen.generateKeyPair()
            val encoded = android.util.Base64.encodeToString(
                keyPair.private.encoded,
                android.util.Base64.DEFAULT
            )
            // Tor erwartet: RSA1024:<base64>
            "RSA1024:$encoded"
        } catch (e: Exception) {
            Log.w("TorManager", "Onion-Key-Generierung fehlgeschlagen", e)
            null
        }
    }

    private fun tryAddress() {
        val onionKey = getOrCreateOnionKey()
        val address = tryControlPort(onionKey)
        if (address != null) {
            this.address = address
            Log.i("TorManager", "Hidden Service aktiv: $address")
            onAddressReady?.invoke(address)
        } else {
            Log.w("TorManager", "Tor Control Port nicht erreichbar / ADD_ONION fehlgeschlagen")
            this.address = null
        }
    }

    private fun tryControlPort(onionKey: String?): String? {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress("127.0.0.1", TOR_CONTROL_PORT), 3000)
            socket.soTimeout = 5000
            val out = BufferedWriter(OutputStreamWriter(socket.outputStream, "UTF-8"))
            val reader = BufferedReader(InputStreamReader(socket.inputStream, "UTF-8"))

            readUntil(reader, "250")

            out.write("AUTHENTICATE \"\"\r\n")
            out.flush()
            val authResp = readLine(reader)
            Log.d("TorManager", "AUTH: $authResp")

            out.write(if (onionKey != null) "ADD_ONION $onionKey Port=$localPort,127.0.0.1:$localPort\r\n" else "ADD_ONION Port=$localPort,127.0.0.1:$localPort\r\n")
            out.flush()

            var onion: String? = null
            var done = false
            while (!done) {
                val line = readLine(reader) ?: break
                Log.d("TorManager", "CTRL: $line")
                if (line.startsWith("250-service-id=")) {
                    serviceId = line.substringAfter("250-service-id=").trim()
                }
                if (line.startsWith("250-") && line.contains(".onion")) {
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
            Log.w("TorManager", "Tor Control Port fehlgeschlagen", e)
            null
        } finally {
            try { socket.close() } catch (e: Exception) { }
        }
    }

    private fun readLine(reader: BufferedReader): String? = try { reader.readLine() } catch (e: Exception) { null }

    private fun readUntil(reader: BufferedReader, prefix: String) {
        while (true) {
            val line = readLine(reader) ?: return
            if (line.startsWith(prefix)) return
        }
    }

    // Gespeicherte Onion-Adresse in SharedPreferences ablegen
    fun saveOnionAddress(address: String) {
        context.getSharedPreferences("onion_service", Context.MODE_PRIVATE).edit()
            .putString("onion_address", address).apply()
    }

    // Gespeicherte Onion-Adresse aus SharedPreferences laden
    fun getSavedOnionAddress(): String? {
        return context.getSharedPreferences("onion_service", Context.MODE_PRIVATE).getString("onion_address", null)
    }
}
