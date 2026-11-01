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
import info.guardianproject.netcipher.proxy.OrbotHelper
import java.net.InetSocketAddress
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // OkHttp-Client für Remote-Fetch über Tor SOCKS-Proxy (Orbot)
        okHttpClient = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", ORBOT_SOCKS_PORT)))
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
            synchronized(jpegLock) {
                latestJpeg = bytes
            }
        }

        // Start Hidden Service automatisch beim ersten Start
        startHiddenService()

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

        // Versions-Info (sichtbar für Unterscheidung)
        val versionText = findViewById<TextView>(R.id.tvVersion)
        versionText.text = "HM v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}) — ${BuildConfig.BUILD_DATE}"
    }

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

    private fun decodeBitmap(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })

    private fun startHiddenService() {
        try {
            if (OrbotHelper.isOrbotInstalled(this)) {
                // Intent-API: Hidden Service registrieren
                OrbotHelper.requestV3OnionServiceOnPort(this, LOCAL_PORT, ORBOT_ONION_PORT, ORBOT_SERVICE_NAME)
                tvStatus.text = getString(R.string.status_service_started)
                // Status-Update via Broadcast empfangen (Orbot antwortet oft per Broadcast)
                registerReceiver(orbotStatusReceiver, IntentFilter(OrbotHelper.ACTION_STATUS))
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Orbot nicht installiert")
                    .setMessage("Orbot (Tor) ist nicht installiert. Zum Starten des Hidden Service ist Orbot erforderlich. Bestätigen beendet die App.")
                    .setPositiveButton("Bestätigt / Beenden") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeMoniTor", "Hidden Service Intent fehlgeschlagen", e)
            Toast.makeText(this, "Hidden Service-Request fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val orbotStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == OrbotHelper.ACTION_STATUS) {
                val status = intent.getStringExtra(OrbotHelper.EXTRA_STATUS)
                val pkg = intent.getStringExtra(OrbotHelper.EXTRA_PACKAGE_NAME)
                Log.d("OrbotStatus", "Status=$status pkg=$pkg")
                // Wenn Orbot aktiv ist, prüfen wir, ob eine Onion-Adresse im Intent steckt
                val onionFromBroadcast = intent.getStringExtra("onion_address")
                    ?: intent.getStringExtra("address")
                    ?: intent.getStringExtra("service_id")
                if (!onionFromBroadcast.isNullOrBlank()) {
                    val addr = onionFromBroadcast.trim().lowercase()
                    onionStore.add(addr)
                    tvOnionAddress.text = addr
                    ivQr.setImageBitmap(QrGenerator.generate(addr, 512))
                    ivQr.visibility = View.VISIBLE
                    Toast.makeText(context, "Hidden Service aktiv: $addr", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        try {
            super.onActivityResult(requestCode, resultCode, data)
        } catch (e: Exception) { }
        if (requestCode == 3333) { // V3_ONION_SERVICE_REQUEST_CODE
            try {
                val onion = data?.getStringExtra("onion_address")
                    ?: data?.getStringExtra("service_id")
                    ?: (if (data?.hasExtra("address") == true) data.getStringExtra("address") else null)
                if (!onion.isNullOrBlank()) {
                    val addr = onion.trim().lowercase()
                    onionStore.add(addr)
                    tvOnionAddress.text = addr
                    ivQr.setImageBitmap(QrGenerator.generate(addr, 512))
                    ivQr.visibility = View.VISIBLE
                    Toast.makeText(this, "Hidden Service: $addr", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Hidden Service-Registrierung fehlgeschlagen oder abgebrochen", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "onActivityResult Fehler", e)
                Toast.makeText(this, "Fehler beim Verarbeiten der Onion-Antwort", Toast.LENGTH_LONG).show()
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
        try { unregisterReceiver(orbotStatusReceiver) } catch (e: Exception) { }
        sensorManager.stop()
        cameraManager.shutdown()
        try { okHttpClient.dispatcher.executorService.shutdown() } catch (e: Exception) { }
    }

    private companion object {
        const val LOCAL_PORT = 8080
        const val ORBOT_SOCKS_PORT = 9050
        const val ORBOT_ONION_PORT = 80
        const val ORBOT_SERVICE_NAME = "HomeMoniTor"
        const val REQUEST_CAMERA = 1001
    }
}