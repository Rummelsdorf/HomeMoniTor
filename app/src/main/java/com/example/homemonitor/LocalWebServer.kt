package com.example.homemonitor

import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimaler lokaler HTTP-Server auf 127.0.0.1:8080.
 * Endpunkte:
 *   GET /                     -> HTML-Statusseite
 *   GET /snapshot?cam=front|back -> aktuelles Kamerabild (JPEG)
 *
 * Wird von Orbot als „Versteckter Dienst“ (Port 8080 -> 127.0.0.1:8080)
 * ins Tor-Netzwerk exponiert.
 */
class LocalWebServer private constructor(
    private val port: Int,
    private val snapshotProvider: (String) -> ByteArray?
) {

    private var serverSocket: ServerSocket? = null
    private var running = false

    companion object {
        @Volatile private var instance: LocalWebServer? = null
        fun init(port: Int, snapshotProvider: (String) -> ByteArray?): LocalWebServer {
            return instance ?: synchronized(this) {
                instance ?: LocalWebServer(port, snapshotProvider).also { instance = it }
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
                serverSocket?.bind(java.net.InetSocketAddress("127.0.0.1", port))
                Log.i("LocalWebServer", "Server auf 127.0.0.1:$port")
                while (running) {
                    val client: Socket? = serverSocket?.accept() ?: break
                    thread(name = "HttpClient", isDaemon = true) {
                        handleClient(client)
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
            input = BufferedReader(InputStreamReader(client.inputStream))
            output = client.outputStream

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ").toList()
            if (parts.size < 2) return
            val path = parts[1]
            val url = java.net.URI(path)
            val query = url.query ?: ""

            if (path.startsWith("/snapshot")) {
                val cam = extractParam(query, "cam").orElse("back")
                val bytes = snapshotProvider(cam)
                if (bytes == null) {
                    sendResponse(output, 404, "text/plain", "Kein Bild verfügbar".toByteArray())
                } else {
                    sendResponse(output, 200, "image/jpeg", bytes)
                }
            } else {
                val html = buildHtml()
                sendResponse(output, 200, "text/html; charset=utf-8", html.toByteArray())
            }
        } catch (e: Exception) {
            Log.w("LocalWebServer", "Client-Fehler", e)
        } finally {
            try { input?.close() } catch (e: Exception) { }
            try { output?.close() } catch (e: Exception) { }
            try { client.close() } catch (e: Exception) { }
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
           <a href="/snapshot?cam=front">Frontkamera</a></p>
        <img src="/snapshot?cam=back" alt="Kamera">
        </body></html>
        """.trimIndent()
    }

    private fun sendResponse(output: OutputStream, status: Int,
                             contentType: String, body: ByteArray) {
        val statusLine = when (status) {
            200 -> "HTTP/1.1 200 OK"
            404 -> "HTTP/1.1 404 Not Found"
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
