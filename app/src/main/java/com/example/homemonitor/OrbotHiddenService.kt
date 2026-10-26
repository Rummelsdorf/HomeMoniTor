package com.example.homemonitor

import android.content.Context
import android.util.Log
import java.io.*
import java.net.Socket

/**
 * Best-Effort-Integration mit Orbot (Tor für Android).
 *
 * Orbot stellt standardmäßig einen Tor-Control-Port auf 127.0.0.1:9051 bereit.
 * Diese Klasse versucht, einen ephemeren Hidden Service (ADD_ONION) anzulegen,
 * der den lokalen Webserver (Port 8080) ins Tor-Netzwerk exponiert.
 *
 * Falls Orbot nicht läuft oder das Authentifizierungsschema nicht passt, bleibt
 * die Onion-Adresse leer. In diesem Fall muss der Nutzer in Orbot manuell unter
 * „Einstellungen -> Versteckte Dienste“ einen Dienst einrichten:
 *   Virtueller Port: 8080  ->  127.0.0.1:8080
 */
class OrbotHiddenService private constructor(context: Context) {

    private val context: Context

    private var address: String? = null
    private var serviceId: String? = null
    private var running = false

    companion object {
        @Volatile private var instance: OrbotHiddenService? = null
        fun init(context: Context): OrbotHiddenService {
            return instance ?: synchronized(this) {
                instance ?: OrbotHiddenService(context.applicationContext).also { instance = it }
            }
        }
        fun get(): OrbotHiddenService? = instance
    }

    val address: String? get() = address

    fun start() {
        if (running) return
        running = true
        thread(name = "OrbotHiddenService", isDaemon = true) {
            tryAddress()
        }
    }

    fun stop() {
        running = false
        address = null
        serviceId = null
    }

    private fun tryAddress() {
        // 1) Einfacher Versuch über Orbot Control Port (9051), ohne Authentifizierung
        address = tryControlPort()
        if (address != null) {
            Log.i("OrbotHiddenService", "Hidden Service aktiv: $address")
            return
        }
        Log.w("OrbotHiddenService", "Orbot Control Port nicht erreichbar / AUTH fehlgeschlagen.")
    }

    private fun tryControlPort(): String? {
        val socket = Socket()
        try {
            socket.connect(java.net.InetSocketAddress("127.0.0.1", 9051), 3000)
            socket.soTimeout = 5000
            val out = BufferedWriter(OutputStreamWriter(socket.outputStream, "UTF-8"))
            val in = BufferedReader(InputStreamReader(socket.inputStream, "UTF-8"))

            // Willkommenszeile(n) lesen
            readUntil(in, "250")

            // AUTHENTICATE ohne Passwort versuchen
            out.write("AUTHENTICATE \"\"\r\n")
            out.flush()
            val authResp = readLine(in)
            Log.d("OrbotHiddenService", "AUTH: $authResp")

            // ADD_ONION: ephemerer Hidden Service, Port 8080 -> localhost:8080
            out.write("ADD_ONION Port=8080,127.0.0.1:8080\r\n")
            out.flush()

            var onion: String? = null
            var done = false
            while (!done) {
                val line = readLine(in) ?: break
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

    private fun readLine(in: BufferedReader): String? {
        return try { in.readLine() } catch (e: Exception) { null }
    }

    private fun readUntil(in: BufferedReader, prefix: String) {
        var line: String?
        while ({ line = readLine(in); line != null }()) {
            if (line.startsWith(prefix)) return
        }
    }
}
