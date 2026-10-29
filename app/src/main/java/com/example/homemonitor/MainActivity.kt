package com.example.homemonitor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.app.AlertDialog
import android.graphics.BitmapFactory
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
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Haupttätigkeit der App mit zwei Zuständen:
 * 1. Kamera mit QR-Scanner (Versteckter Dienst / Onion)
 * 2. Gallery mit verknüpften Live-Feed-Adressen
 *
 * Der Übergang zwischen den Zuständen erfolgt über Buttons.
 * Ein "Quit"-Button ist immer sichtbar.
 */
class MainActivity : AppCompatActivity() {

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

    // Thread-sicherer Zugriff auf latestJpeg
    @Volatile private var latestJpeg: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Orbot-Check: muss vorne stehen und bestätigt werden
        if (!isOrbotAccessible()) {
            AlertDialog.Builder(this)
                .setTitle("Orbot nicht erreichbar")
                .setMessage("Orbot (Tor) läuft nicht oder ist nicht über 127.0.0.1:9051 erreichbar. Bitte Orbot starten und bestätigen. Die App wird danach beendet.")
                .setPositiveButton("Bestätigt / Beenden") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // OkHttp-Client für Remote-Fetch über Tor SOCKS-Proxy (Orbot)
        okHttpClient = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050)))
            .build()

        // UI-Initialisierung (view IDs vorab klären, ohne Camera/Sensor Zugriff)
        initUi()

        // Buttons initialisieren
        setupListeners()

        // Dienste, die keine Kamera-Berechtigung brauchen, vorab starten
        onionStore = OnionStore.get() ?: OnionStore.init(this)
        sensorManager = SensorManager.get() ?: SensorManager.init(this)
        sensorManager.start()
        localWebServer = LocalWebServer.get() ?: LocalWebServer.init(
            LOCAL_PORT,
            { cam -> latestJpeg },
            { sensorManager.collectJson() },
            okHttpClient
        )
        // CameraManager initialisieren (ohne Bindung bis Berechtigung erteilt ist)
        cameraManager = CameraManager.get() ?: CameraManager.init(this, binding.cameraPreview) { bytes ->
            latestJpeg = bytes
        }

        // Startzustand: Kamera panel sichtbar, Gallery unsichtbar
        setState(AppState.CAMERA)

        // Kamera-Berechtigung anfordern (NACH UI-Setup)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            // Berechtigung bereits erteilt: Kamera an Lifecycle binden
            cameraManager.bindToLifecycle(this)
        }
    }

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
    }

    private fun setupListeners() {
        // Zustands-Wechsel Buttons
        btnStateCamera.setOnClickListener { setState(AppState.CAMERA) }
        btnStateGallery.setOnClickListener { setState(AppState.GALLERY) }

        // Quit Button (immer sichtbar)
        btnQuit.setOnClickListener { finish() }

        // Kamera-Zustand Listener
        btnStartService.setOnClickListener {
            localWebServer.start()
            OrbotHiddenService.get()?.start()
            tvStatus.text = getString(R.string.status_service_started)
        }
        btnStopService.setOnClickListener {
            localWebServer.stop()
            OrbotHiddenService.get()?.stop()
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

    private fun loadGalleryFeeds() {
        val addresses = onionStore.addresses
        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, addresses)
        lvGalleryFeeds.adapter = adapter
        tvGalleryStatus.text = if (addresses.isEmpty()) "Keine Feed-Adressen" else "${addresses.size} Feed-Adresse(n)"
    }

    // "Show connection code" - zeigt QR mit Onion-URL
    private fun showOnionQr() {
        val onion = onionStore.addresses.firstOrNull() ?: run {
            tvOnionAddress.text = getString(R.string.status_not_available)
            return
        }
        tvOnionAddress.text = onion
        ivQr.setImageBitmap(QrGenerator.generate(onion, 512))
        ivQr.visibility = View.VISIBLE
        Toast.makeText(this, "Onion-Code: $onion", Toast.LENGTH_SHORT).show()
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })

    private fun readSensors() {
        val json = sensorManager.collectJson()
        tvSensors.text = json ?: getString(R.string.sensors_empty)
    }

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
        sensorManager.stop()
        cameraManager.shutdown()
        try { okHttpClient.dispatcher.executorService.shutdown() } catch (e: Exception) { }
    }

    private fun isOrbotAccessible(): Boolean = try {
        val socket = java.net.Socket()
        socket.connect(java.net.InetSocketAddress("127.0.0.1", 9051), 800)
        socket.close()
        true
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val LOCAL_PORT = 8080
        const val REQUEST_CAMERA = 1001
    }
}