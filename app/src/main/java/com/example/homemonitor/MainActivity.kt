package com.example.homemonitor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.homemonitor.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.BindException
import java.io.File

/**
 * Haupttätigkeit der App mit zwei Zuständen:
 * 1. Kamera mit QR-Scanner (Versteckter Dienst / Onion)
 * 2. Gallery mit verknüpften Live-Feed-Adressen
 *
 * Der Übergang zwischen den Zuständen erfolgt über Buttons.
 * Ein "Quit"-Button ist immer sichtbar.
 */
class MainActivity : AppCompatActivity() {

    // Globaler Crash-Handler: jede Exception sichtbar machen
    // (an den vorherigen Handler weiterleiten, damit der Prozess sauber abstürzen kann)
    private val previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()

    init {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            android.util.Log.e("HomeMoniTorCrash", "FATAL: App stürzt ab", e)
            e.printStackTrace()
            previousUncaughtExceptionHandler?.uncaughtException(Thread.currentThread(), e)
        }
    }

    private lateinit var binding: ActivityMainBinding

    // UI-Elemente
    private lateinit var btnStateCamera: Button
    private lateinit var btnStateGallery: Button
    private lateinit var panelCamera: View
    private lateinit var panelGallery: View
    private lateinit var btnQuit: Button
    private lateinit var btnShowConnectedFeeds: Button

    // Camera-Zustand UI (inside panelCamera)
    private lateinit var cameraPreview: androidx.camera.view.PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var btnFlipCamera: Button
    private lateinit var btnSnapshot: Button
    private lateinit var tvOnionAddress: TextView
    private lateinit var btnShowQr: Button
    private lateinit var btnHideQr: Button
    private lateinit var ivQr: ImageView
    private lateinit var btnReadSensors: Button
    private lateinit var tvSensors: TextView

    // Gallery-Zustand UI (inside panelGallery)
    private lateinit var btnCameraMode: Button
    private lateinit var tvGalleryStatus: TextView
    private lateinit var lvGalleryFeeds: ListView

    // Dienste & Komponenten
    private lateinit var cameraManager: CameraManager
    private lateinit var sensorManager: SensorManager
    private lateinit var localWebServer: LocalWebServer
    private lateinit var onionStore: OnionStore
    private lateinit var okHttpClient: OkHttpClient

    // State
    private enum class AppState {
        CAMERA, GALLERY
    }
    private var currentState: AppState = AppState.CAMERA

    // Thread-sicherer Zugriff auf latestJpeg (Camera + Webserver + UI)
    private val jpegLock = Any()
    @Volatile private var latestJpeg: ByteArray? = null

    // Eigene Onion-Adresse dieses Geräts (von OrbotOnionManager bereitgestellt).
    @Volatile private var ownOnionAddress: String? = null

    private lateinit var torManager: TorManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)

            // UI-Elemente erst binden, bevor Callbacks ausgelöst werden können
            initUi()
            setupListeners()

            // Flexibler Port: 8080, 8081, ... prüfen und speichern
            val webPort = findAndStoreFreePort()

            // OnionStore früh initialisieren, damit die gespeicherte Adresse unten
            // (und der Orbot-Callback) nicht auf eine uninitialisierte lateinit-Variable trifft.
            onionStore = OnionStore.get() ?: OnionStore.init(this)

            torManager = TorManager(this, webPort)
            torManager.setOnAddressReady { address ->
                runOnUiThread {
                    try {
                        val addr = address.trim().lowercase()
                        ownOnionAddress = addr
                        onionStore.add(addr)
                        tvOnionAddress.text = addr
                        tvStatus.text = getString(R.string.status_service_started)
                        ivQr.setImageBitmap(QrGenerator.generate(addr, 512))
                        ivQr.visibility = View.VISIBLE
                        Toast.makeText(this, "Hidden Service aktiv: $addr", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Callback Fehler", e)
                        Toast.makeText(this, "Fehler bei Onion-Antwort: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            // 1) Zuerst prüfen, ob Adresse intern gespeichert wurde
            val savedAddress = torManager.getSavedOnionAddress()
            if (savedAddress != null) {
                try {
                    val addr = savedAddress.trim().lowercase()
                    ownOnionAddress = addr
                    onionStore.add(addr)
                    tvOnionAddress.text = addr
                    // Gespeicherte Adresse sofort in UI laden
                } catch (e: Exception) {
                    Log.e("MainActivity", "Gespeicherte Adresse laden fehlgeschlagen", e)
                    Toast.makeText(this, "Gespeicherte Adresse laden fehlgeschlagen", Toast.LENGTH_SHORT).show()
                }
            }

            // 2) Tor direkt: Hidden Service starten (Address via Callback)
            torManager.start()

            okHttpClient = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", ORBOT_SOCKS_PORT)))
                .build()

            // onionStore wurde bereits oben initialisiert (idempotent).
            onionStore = OnionStore.get() ?: OnionStore.init(this)
            sensorManager = SensorManager.get() ?: SensorManager.init(this)
            sensorManager.start()
            localWebServer = LocalWebServer.get() ?: LocalWebServer.init(
                webPort,
                { cam -> latestJpeg },
                { sensorManager.collectJson() },
                okHttpClient,
                { ownOnionAddress }
            )
            cameraManager = CameraManager.get() ?: CameraManager.init(this, binding.cameraPreview) { bytes ->
                synchronized(jpegLock) {
                    latestJpeg = bytes
                }
            }

            setState(AppState.CAMERA)

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            } else {
                cameraManager.bindToLifecycle(this)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "onCreate CRASH", e)
            Toast.makeText(this, "App-Start fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            try {
                setContentView(R.layout.activity_main)
            } catch (e2: Exception) {
                // letzter Notfall
            }
        }
    }

    // UI-Elemente initialisieren (IDs finden und Variablen zuweisen)
    private fun initUi() {
        btnStateCamera = findViewById(R.id.btnStateCamera)
        btnStateGallery = findViewById(R.id.btnStateGallery)
        panelCamera = findViewById(R.id.panelCamera)
        panelGallery = findViewById(R.id.panelGallery)
        btnQuit = findViewById(R.id.btnQuit)
        btnShowConnectedFeeds = findViewById(R.id.btnShowConnectedFeeds)

        // Camera-Zustand
        cameraPreview = findViewById(R.id.cameraPreview)
        tvStatus = findViewById(R.id.tvStatus)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)
        btnFlipCamera = findViewById(R.id.btnFlipCamera)
        btnSnapshot = findViewById(R.id.btnSnapshot)
        tvOnionAddress = findViewById(R.id.tvOnionAddress)
        btnShowQr = findViewById(R.id.btnShowQr)
        btnHideQr = findViewById(R.id.btnHideQr)
        ivQr = findViewById(R.id.ivQr)
        btnReadSensors = findViewById(R.id.btnReadSensors)
        tvSensors = findViewById(R.id.tvSensors)

        // Gallery-Zustand
        btnCameraMode = findViewById(R.id.btnCameraMode)
        tvGalleryStatus = findViewById(R.id.tvGalleryStatus)
        lvGalleryFeeds = findViewById(R.id.lvGalleryFeeds)

        // Versions-Info (sichtbar für Unterscheidung)
        val versionText = findViewById<TextView>(R.id.tvVersion)
        versionText.text = "HM v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}) — ${BuildConfig.BUILD_DATE}"
    }

    // Button-Listener für Zustandswechsel, Kamera, QR, Sensoren und Quit einrichten
    private fun setupListeners() {
        // Zustands-Wechsel Buttons
        btnStateCamera.setOnClickListener { setState(AppState.CAMERA) }
        btnStateGallery.setOnClickListener { setState(AppState.GALLERY) }

        // Quit Button (immer sichtbar)
        btnQuit.setOnClickListener { finish() }

        // Kamera-Zustand Listener
        // Startzustand: Dienst beim ersten Start automatisch registrieren
        btnStartService.setOnClickListener {
            localWebServer.start()
            tvStatus.text = getString(R.string.status_service_started)
        }
        btnStopService.setOnClickListener {
            // Lokalen Webserver stoppen. Der Hidden Service selbst wird von Orbot
            // verwaltet (OrbotOnionManager); hier wird nur der lokale Endpunkt beendet.
            localWebServer.stop()
            tvStatus.text = getString(R.string.status_service_stopped)
        }
        btnFlipCamera.setOnClickListener { cameraManager.flip() }
        btnSnapshot.setOnClickListener { cameraManager.takeSnapshot() }
        btnShowQr.setOnClickListener { showOnionQr() }
        btnHideQr.setOnClickListener { ivQr.visibility = View.GONE }
        btnReadSensors.setOnClickListener { readSensors() }

        // Gallery-Zustand Listener
        btnCameraMode.setOnClickListener { setState(AppState.CAMERA) }

        // Show connected feeds (wird im Kamera-Zustand verwendet)
        btnShowConnectedFeeds.setOnClickListener { setState(AppState.GALLERY) }
    }

    // Anwendung zwischen Kamera- und Gallery-Zustand umschalten
    private fun setState(newState: AppState) {
        currentState = newState

        btnStateCamera.isSelected = newState == AppState.CAMERA
        btnStateGallery.isSelected = newState == AppState.GALLERY

        when (newState) {
            AppState.CAMERA -> {
                panelCamera.visibility = View.VISIBLE
                panelGallery.visibility = View.GONE
            }
            AppState.GALLERY -> {
                panelCamera.visibility = View.GONE
                panelGallery.visibility = View.VISIBLE
                loadGalleryFeeds()
            }
        }
    }

    // Thumbnail für eine Onion-Adresse abrufen und lokal speichern (1 Slot pro Eintrag, immer überschrieben)
    private fun fetchThumbnailForOnion(onion: String) {
        try {
            val url = "http://$onion/thumbnail.jpeg"
            val request = Request.Builder().url(url).header("Accept", "image/jpeg").build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes() ?: return
                    val dir = File(filesDir, "thumbnails")
                    dir.mkdirs()
                    // Dateiname basiert auf Onion-Adresse (sanitisiert, 1 pro Eintrag)
                    val safeName = onion.trim().lowercase().replace(".", "_") + ".jpg"
                    val file = File(dir, safeName)
                    file.writeBytes(bytes)
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Thumbnail-Abruf fehlgeschlagen für $onion", e)
        }
    }

    // Gallery-Feeds aus dem Speicher laden und Thumbnails aktualisieren
    private fun loadGalleryFeeds() {
        val addresses = onionStore.addresses
        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, addresses)
        lvGalleryFeeds.adapter = adapter
        tvGalleryStatus.text = if (addresses.isEmpty()) "Keine Feed-Adressen" else "${addresses.size} Feed-Adresse(n)"
        // Für jeden Eintrag Thumbnail abrufen (1 pro Eintrag, überschrieben).
        // Netzwerk-I/O darf nicht im UI-Thread laufen (NetworkOnMainThreadException),
        // daher im Hintergrund ausführen.
        Thread {
            for (addr in addresses) {
                fetchThumbnailForOnion(addr)
            }
        }.start()
    }

    // "Show connection code" - zeigt QR mit Onion-URL
    private fun showOnionQr() {
        try {
            val onion = onionStore.addresses.firstOrNull() ?: run {
                tvOnionAddress.text = getString(R.string.status_not_available)
                return
            }
            tvOnionAddress.text = onion
            ivQr.setImageBitmap(QrGenerator.generate(onion, 512))
            ivQr.visibility = View.VISIBLE
            Toast.makeText(this, "Onion-Code: $onion", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "QR-Generierung fehlgeschlagen", e)
            Toast.makeText(this, "QR-Code konnte nicht erzeugt werden", Toast.LENGTH_LONG).show()
        }
    }

    // Bitmap aus Byte-Array dekodieren (mit verkleinerter Auflösung)
    private fun decodeBitmap(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })

    // Sensor-Daten als JSON sammeln und anzeigen
    private fun readSensors() {
        val json = sensorManager.collectJson()
        tvSensors.text = json ?: getString(R.string.sensors_empty)
    }

    // Aktuelles Kamerabild in die Galerie speichern
    private fun saveCurrentSnapshotToGallery() {
        val bytes = latestJpeg ?: return
        val bmp = decodeBitmap(bytes)
        val onion = tvOnionAddress.text.toString().trim().takeIf { it.isNotEmpty() } ?: "camera"
        if (bmp != null) {
            if (cameraManager.saveToGallery(bmp, onion)) {
                Toast.makeText(this, "In Galerie gespeichert", Toast.LENGTH_SHORT).show()
                loadGalleryFeeds()
            } else {
                Toast.makeText(this, "Speichern fehlgeschlagen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Berechtigung erteilt: Kamera an Lifecycle binden
                cameraManager.bindToLifecycle(this)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { sensorManager.stop() } catch (e: Exception) { }
        try { cameraManager.shutdown() } catch (e: Exception) { }
        // Lokalen Webserver und dessen Socket/Threads ebenfalls beenden.
        try { localWebServer.stop() } catch (e: Exception) { }
        try { okHttpClient.dispatcher.executorService.shutdown() } catch (e: Exception) { }
    }

    // Flexible Port-Ermittlung: probiert 8080, 8081, ... und speichert das Ergebnis
    private fun findAndStoreFreePort(): Int {
        val prefs = getSharedPreferences("port_config", Context.MODE_PRIVATE)
        val saved = prefs.getString("http_port", "8080")?.toIntOrNull() ?: 8080
        for (candidate in saved until saved + 10) {
            try {
                val test = ServerSocket()
                test.bind(InetSocketAddress("127.0.0.1", candidate))
                test.close()
                prefs.edit().putString("http_port", candidate.toString()).apply()
                Log.d("MainActivity", "Webserver Port gefunden: $candidate")
                return candidate
            } catch (e: BindException) {
                // Port bereits belegt, nächster Versuch
            }
        }
        Log.w("MainActivity", "Kein freier Port im Bereich $saved-${saved + 9}")
        return saved
    }

    private companion object {
        const val LOCAL_PORT = 8080
        const val ORBOT_SOCKS_PORT = 9050
        const val ORBOT_ONION_PORT = 80
        const val REQUEST_CAMERA = 1001
    }
}