package com.example.homemonitor

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import com.example.homemonitor.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale

/**
 * Haupttätigkeit der App.
 *
 * Zeigt die Kamera (verlinkt über einen Hidden Service / Onion-Adresse) sowie
 * eine Galerie mit Fotos, die über QR-Codes mit Onion-Adressen verknüpft wurden.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var sensorManager: SensorManager
    private lateinit var localWebServer: LocalWebServer
    private lateinit var onionStore: OnionStore
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var mainViewModel: MainViewModel

    // Thread-sicherer Zugriff auf latestJpeg
    @Volatile private var latestJpeg: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // OkHttp-Client für Remote-Fetch über Tor SOCKS-Proxy (Orbot)
        okHttpClient = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050)))
            .build()

        // ViewModel für die App-Logik
        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]
        mainViewModel.latestJpeg.asLiveData().observe(this) { bytes ->
            latestJpeg = bytes
        }

        // Komponenten initialisieren
        onionStore = OnionStore.get() ?: OnionStore.init(this)
        cameraCallback = { bytes ->
            mainViewModel.updateLatestJpeg(bytes)
        }
        cameraManager = CameraManager.get() ?: CameraManager.init(this, binding.cameraPreview, cameraCallback)
        cameraManager.bindToLifecycle(this)
        sensorManager = SensorManager.get() ?: SensorManager.init(this)
        sensorManager.start()
        localWebServer = LocalWebServer.get() ?: LocalWebServer.init(
            LOCAL_PORT,
            { cam -> latestJpeg },
            { sensorManager.collectJson() },
            okHttpClient
        )

        setupTabs()
        setupListeners()
        loadGallery()

        // Kamera-Berechtigung anfordern
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    private var cameraCallback: (ByteArray?) -> Unit = { bytes -> /* no-op placeholder */ }

    private fun setupTabs() {
        binding.btnTabHost.setOnClickListener { showTab(TAB_HOST) }
        binding.btnTabViewer.setOnClickListener { showTab(TAB_VIEWER) }
        binding.btnTabScanner.setOnClickListener { startQrScanner() }
    }

    private fun startQrScanner() {
        startActivity(Intent(this, QrScannerActivity::class.java))
    }

    private fun showTab(index: Int) {
        binding.panelHost.visibility = if (index == TAB_HOST) View.VISIBLE else View.GONE
        binding.panelViewer.visibility = if (index == TAB_VIEWER) View.VISIBLE else View.GONE
        binding.panelScanner.visibility = if (index == TAB_SCANNER) View.VISIBLE else View.GONE
        binding.btnTabHost.isSelected = index == TAB_HOST
        binding.btnTabViewer.isSelected = index == TAB_VIEWER
        binding.btnTabScanner.isSelected = index == TAB_SCANNER
    }

    private fun setupListeners() {
        binding.btnStartService.setOnClickListener { startLocalService() }
        binding.btnStopService.setOnClickListener { stopLocalService() }
        binding.btnFlipCamera.setOnClickListener { cameraManager.flip() }
        binding.btnSnapshot.setOnClickListener { cameraManager.takeSnapshot() }
        binding.btnShowQr.setOnClickListener { showOnionQr() }
        binding.btnHideQr.setOnClickListener { binding.ivQr.visibility = View.GONE }
        binding.btnReadSensors.setOnClickListener { readSensors() }
        binding.btnLoad.setOnClickListener { loadSnapshot() }
        binding.btnSaveGallery.setOnClickListener { saveCurrentSnapshotToGallery() }
    }

    private fun startLocalService() {
        localWebServer.start()
        OrbotHiddenService.get()?.start()
        binding.tvStatus.text = getString(R.string.status_service_started)
    }

    private fun stopLocalService() {
        localWebServer.stop()
        OrbotHiddenService.get()?.stop()
        binding.tvStatus.text = getString(R.string.status_service_stopped)
    }

    private fun readSensors() {
        val json = sensorManager.collectJson()
        binding.tvSensors.text = json ?: getString(R.string.sensors_empty)
    }

    private fun showOnionQr() {
        val onion = onionStore.addresses.firstOrNull() ?: run {
            binding.tvOnionAddress.text = getString(R.string.status_not_available)
            return
        }
        binding.tvOnionAddress.text = onion
        binding.ivQr.setImageBitmap(QrGenerator.generate(onion, 512))
        binding.ivQr.visibility = View.VISIBLE
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })

    private fun loadSnapshot() {
        val onionInput = binding.etOnion.text?.toString()?.trim()
        val camParam = extractValidCamParam(onionInput)

        // Zuerst lokales Bild zeigen (falls vorhanden)
        val localBytes = latestJpeg
        if (localBytes != null) {
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.Default) { decodeBitmap(localBytes) }
                if (bmp != null) {
                    binding.ivViewerImage.setImageBitmap(bmp)
                }
            }
        }

        // Falls eine gültige Onion-Adresse eingegeben wurde, remote Bild fetchen
        if (!onionInput.isNullOrBlank() && onionStore.isOnionAddress(onionInput)) {
            lifecycleScope.launch {
                val remoteBytes = fetchRemoteSnapshot(onionInput, camParam)
                if (remoteBytes != null) {
                    val bmp = withContext(Dispatchers.Default) { decodeBitmap(remoteBytes) }
                    if (bmp != null) {
                        binding.ivViewerImage.setImageBitmap(bmp)
                        Toast.makeText(this@MainActivity, "Remote Bild geladen", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Kein Remote-Bild verfügbar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun extractValidCamParam(input: String?): String {
        val s = input?.trim()?.lowercase(Locale.US) ?: "back"
        return when (s) {
            "front" -> "front"
            else -> "back"
        }
    }

    private suspend fun fetchRemoteSnapshot(onion: String, cam: String): ByteArray? {
        val url = "http://$onion/snapshot?cam=$cam"
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "image/jpeg")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes() else null
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to fetch remote snapshot from $onion", e)
            null
        }
    }

    private fun saveCurrentSnapshotToGallery() {
        val bytes = latestJpeg ?: return
        val bmp = decodeBitmap(bytes)
        val onion = binding.etOnion.text?.toString()?.trim()
        if (bmp != null) {
            val filename = if (onion != null) "$onion" else "camera"
            if (cameraManager.saveToGallery(bmp, filename)) {
                Toast.makeText(this, "In Galerie gespeichert", Toast.LENGTH_SHORT).show()
                loadGallery()
            } else {
                Toast.makeText(this, "Speichern fehlgeschlagen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadGallery() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(Environment.DIRECTORY_PICTURES + "/HomeMoniTor"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val bitmaps = mutableListOf<Bitmap>()
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: continue
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val bmp = contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().let { decodeBitmapOptimizedForGallery(it) }
                }
                if (bmp != null) {
                    bitmaps.add(bmp)
                }
            }
            val adapter = GalleryAdapter(this)
            adapter.submit(bitmaps)
            binding.lvGallery.adapter = adapter
            binding.tvGalleryEmpty.visibility = if (bitmaps.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun decodeBitmapOptimizedForGallery(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var inSampleSize = 1
        if (opts.outWidth > 512 || opts.outHeight > 512) {
            val halfWidth = opts.outWidth / 2
            val halfHeight = opts.outHeight / 2
            while (halfWidth / inSampleSize >= 512 && halfHeight / inSampleSize >= 512) {
                inSampleSize *= 2
            }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            // Berechtigungsergebnis auswerten (z. B. Kamera später initialisieren)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.stop()
        cameraManager.shutdown()
        try { okHttpClient.dispatcher.executorService.shutdown() } catch (e: Exception) { }
    }

    private companion object {
        const val LOCAL_PORT = 8080
        const val REQUEST_CAMERA = 1001
        const val TAB_HOST = 0
        const val TAB_VIEWER = 1
        const val TAB_SCANNER = 2
    }
}
