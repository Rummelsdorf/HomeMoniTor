package com.example.homemonitor

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.homemonitor.databinding.ActivityMainBinding

/**
 * Haupttätigkeit der App.
 *
 * Zeigt die Kamera (verlinkt über einen Hidden Service / Onion-Adresse) sowie
 * eine Galerie mit Fotos, die über QR-Codes mit Onion-Adressen verknüpft wurden.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var localWebServer: LocalWebServer
    private lateinit var onionStore: OnionStore
    private val galleryBitmaps = mutableListOf<Bitmap>()
    private var latestJpeg: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Komponenten initialisieren (Singletons bzw. lokale Services)
        onionStore = OnionStore.get() ?: OnionStore.init(this)
        cameraManager = CameraManager.get() ?: CameraManager.init(this, binding.cameraPreview) { bytes -> latestJpeg = bytes }
        cameraManager.bindToLifecycle(this)
        localWebServer = LocalWebServer.get() ?: LocalWebServer.init(LOCAL_PORT) { cam -> latestJpeg }

        setupTabs()
        setupListeners()
        loadGallery()

        // Kamera-Berechtigung anfordern
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    private fun setupTabs() {
        binding.btnTabHost.setOnClickListener { showTab(TAB_HOST) }
        binding.btnTabViewer.setOnClickListener { showTab(TAB_VIEWER) }
        binding.btnTabScanner.setOnClickListener { showTab(TAB_SCANNER) }
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
        binding.btnLoad.setOnClickListener { loadSnapshot() }
        binding.btnSaveGallery.setOnClickListener { saveCurrentSnapshotToGallery() }
    }

    private fun startLocalService() {
        // Lokalen Webserver starten und Hidden Service über Orbot aktivieren
        localWebServer.start()
        OrbotHiddenService.get()?.start()
        binding.tvStatus.text = getString(R.string.status_service_started)
    }

    private fun stopLocalService() {
        localWebServer.stop()
        OrbotHiddenService.get()?.stop()
        binding.tvStatus.text = getString(R.string.status_service_stopped)
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

    private fun loadSnapshot() {
        val cam = binding.etOnion.text?.toString()?.trim()?.ifEmpty { null } ?: "back"
        val bytes = latestJpeg ?: return
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp != null) {
            binding.ivViewerImage.setImageBitmap(bmp)
        }
    }

    private fun saveCurrentSnapshotToGallery() {
        val bytes = latestJpeg ?: return
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val onion = binding.etOnion.text?.toString()?.trim()
        if (bmp != null && onion != null) {
            cameraManager.saveToGallery(bmp, onion)
            loadGallery()
        }
    }

    /**
     * Laedt die Galerie mit den gespeicherten Fotos aus dem MediaStore.
     */
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
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    bitmaps.add(bmp)
                }
            }
            galleryBitmaps.clear()
            galleryBitmaps.addAll(bitmaps)
            binding.lvGallery.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, galleryBitmaps)
            binding.tvGalleryEmpty.visibility = if (bitmaps.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        const val LOCAL_PORT = 8080
        const val REQUEST_CAMERA = 1001
        const val TAB_HOST = 0
        const val TAB_VIEWER = 1
        const val TAB_SCANNER = 2
    }
}
