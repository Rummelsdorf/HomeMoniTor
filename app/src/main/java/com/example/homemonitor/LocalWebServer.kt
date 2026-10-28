package com.example.homemonitor

import android.util.Log
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import okhttp3.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.concurrent.thread

/**
 * Minimaler lokaler HTTP-Server auf 127.0.0.1:8080.
 * Endpunkte:
 *   GET /                              -> HTML-Statusseite
 *   GET /snapshot?cam=front|back        -> aktuelles Kamerabild (JPEG) von der lokalen Kamera
 *   GET /sensors                       -> Werte lokaler Sensoren (JSON)
 *   GET /remote_snapshot?onion=...&cam=front|back -> entferntes Bild via Tor abrufen
 *
 * Wird von Orbot als „Versteckter Dienst“ (Port 8080 -> 127.0.0.1:8080)
 * ins Tor-Netzwerk exponiert.
 *
 * Verbesserungen gegenüber Original:
 *   - Zeitlimits (socket read timeout) gegen Hängende Verbindungen
 *   - Einfaches Rate-Limiting (max. 100 offene Anfragen)
 *   - Optionaler Auth-Token (Query-Parameter token=..., falls konfiguriert)
 *   - Nutzung von OkHttp für Remote-Fetch (SOCKS-Proxy über Orbot)
 *   - Getrennte Verantwortlichkeiten: handleClient legt nur HTTP-Antworten
 *     fest, Daten werden durch injectierte Provider bereitgestellt.
 */
class LocalWebServer private constructor(
    private val port: Int,
    private val snapshotProvider: (String) -> ByteArray?,
    private val sensorProvider: () -> String?,
    private val okHttpClient: OkHttpClient,
    private val authToken: String? = null
) {

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    companion object {
        @Volatile private var instance: LocalWebServer? = null

        fun init(
            port: Int,
            snapshotProvider: (String) -> ByteArray?,
            sensorProvider: () -> String?,
            okHttpClient: OkHttpClient,
            authToken: String? = null
        ): LocalWebServer {
            return instance ?: synchronized(this) {
                instance ?: LocalWebServer(port, snapshotProvider, sensorProvider, okHttpClient, authToken)
                    .also { instance = it }
            }
        }

        fun get(): LocalWebServer? = instance
    }

    fun start() {
        if (running) return
        running = true
        thread(name = "LocalWebServer", isDaemon = true) {
            try {
                serverSocket = ServerSocket()
                serverSocket?.reuseAddress = true
                serverSocket?.bind(InetSocketAddress("127.0.0.1", port))
                Log.i("LocalWebServer", "Server auf 127.0.0.1:$port")
                var openConnections = 0
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    openConnections++
                    if (openConnections > 100) {
                        // Einfaches Rate-Limit: zu viele offene Verbindungen abweisen
                        client.close()
                        openConnections--
                        continue
                    }
                    thread(name = "HttpClient", isDaemon = true) {
                        try {
                            handleClient(client)
                        } finally {
                            openConnections--
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e("LocalWebServer", "Server-Fehler", e)
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) { }
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        var input: BufferedReader? = null
        var output: OutputStream? = null
        try {
            client.soTimeout = 5000 // Schutz gegen hängende Clients
            input = BufferedReader(InputStreamReader(client.inputStream))
            output = client.outputStream

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ").toList()
            if (parts.size < 2) return
            val method = parts[0]
            if (method != "GET") {
                sendResponse(output, 405, "text/plain", "Method Not Allowed".toByteArray())
                return
            }

            val path = parts[1]
            val query = URI(path).query ?: ""

            // Einfache Auth-Prüfung (nur wenn ein Token konfiguriert ist)
            val tokenParam = extractParam(query, "token")
            if (authToken != null && authToken != tokenParam) {
                sendResponse(output, 401, "text/plain", "Unauthorized".toByteArray())
                return
            }

            when {
                path.startsWith("/snapshot") -> handleSnapshot(output, query)
                path.startsWith("/sensors") -> handleSensors(output)
                path.startsWith("/remote_snapshot") -> handleRemoteSnapshot(output, query, okHttpClient)
                else -> {
                    val html = buildHtml()
                    sendResponse(output, 200, "text/html; charset=utf-8", html.toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.w("LocalWebServer", "Client-Fehler", e)
        } finally {
            try { input?.close() } catch (e: Exception) { }
            try { output?.close() } catch (e: Exception) { }
            try { client.close() } catch (e: Exception) { }
        }
    }

    private fun handleSnapshot(output: OutputStream, query: String) {
        val cam = extractParam(query, "cam") ?: "back"
        if (cam != "front" && cam != "back") {
            sendResponse(output, 400, "text/plain", "Invalid cam parameter".toByteArray())
            return
        }
        val bytes = snapshotProvider(cam)
        if (bytes == null) {
            sendResponse(output, 404, "text/plain", "Kein Bild verfügbar".toByteArray())
        } else {
            sendResponse(output, 200, "image/jpeg", bytes)
        }
    }

    private fun handleSensors(output: OutputStream) {
        val json = sensorProvider()
        if (json == null) {
            sendResponse(output, 404, "text/plain", "Keine Sensorwerte verfügbar".toByteArray())
        } else {
            sendResponse(output, 200, "application/json; charset=utf-8", json.toByteArray())
        }
    }

    private fun handleRemoteSnapshot(output: OutputStream, query: String, client: OkHttpClient) {
        val onion = extractParam(query, "onion")
        val cam = extractParam(query, "cam") ?: "back"
        if (onion.isNullOrBlank()) {
            sendResponse(output, 400, "text/plain", "Missing onion parameter".toByteArray())
            return
        }
        val remoteBytes = fetchRemoteSnapshot(onion, cam, client)
        if (remoteBytes != null) {
            sendResponse(output, 200, "image/jpeg", remoteBytes)
        } else {
            sendResponse(output, 404, "text/plain", "Remote image not available".toByteArray())
        }
    }

    private fun fetchRemoteSnapshot(onion: String, cam: String, client: OkHttpClient): ByteArray? {
        val url = "http://$onion/snapshot?cam=$cam"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/jpeg")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes()
                else null
            }
        } catch (e: Exception) {
            Log.w("LocalWebServer", "Failed to fetch remote snapshot", e)
            null
        }
    }

    private fun extractParam(query: String, name: String): String? {
        for (pair in query.split("&")) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == name) return kv[1]
        }
        return null
    }

    private fun buildHtml(): String {
        val addr = OrbotHiddenService.get()?.address ?: "nicht verfügbar"
        return """
            <html><head><meta charset="utf-8"><title>HomeMoniTor Hidden Service</title>
            <style>body{font-family:sans-serif;background:#222;color:#eee;padding:2em;}
            a{color:#bb86fc;} img{max-width:100%;border:1px solid #555;}</style></head>
            <body>
            <h1>HomeMoniTor - Versteckter Dienst</h1>
            <p>Onion-Adresse: <b>$addr</b></p>
            <p><a href="/snapshot?cam=back">Hintenkamera</a> |
               <a href="/snapshot?cam=front">Frontkamera</a> |
               <a href="/sensors">Sensoren (JSON)</a> |
               <a href="/remote_snapshot?cam=back&onion=$addr">Remote Back</a> |
               <a href="/remote_snapshot?cam=front&onion=$addr">Remote Front</a></p>
            <img src="/snapshot?cam=back" alt="Kamera">
            </body></html>
            """.trimIndent()
    }

    private fun sendResponse(output: OutputStream, status: Int,
                             contentType: String, body: ByteArray) {
        val statusLine = when (status) {
            200 -> "HTTP/1.1 200 OK"
            404 -> "HTTP/1.1 404 Not Found"
            405 -> "HTTP/1.1 405 Method Not Allowed"
            else -> "HTTP/1.1 $status"
        }
        val header = "$statusLine\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(header.toByteArray())
        output.write(body)
        output.flush()
    }
}
