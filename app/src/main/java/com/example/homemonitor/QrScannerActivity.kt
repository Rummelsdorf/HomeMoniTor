package com.example.homemonitor

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.homemonitor.databinding.ActivityQrScannerBinding
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.Result
import java.util.concurrent.Executors

/**
 * QR-Scanner für Onion-Adressen (CameraX + ZXing).
 * Nach erfolgreichem Scan wird die Adresse dem [OnionStore] hinzugefügt
 * und der Scan-Flow beendet.
 */
class QrScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var cameraProvider: ProcessCameraProvider
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val barcodeReader = MultiFormatReader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindPreview()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreview() {
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(binding.scannerPreview.surfaceProvider)

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_LATEST)
            .setExecutor(cameraExecutor)
            .setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy: ImageProxy, imageAnalysis: ImageAnalysis ->
                handleFrame(imageProxy)
            })
            .build()

        cameraProvider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            imageAnalysis,
            preview
        )
    }

    private fun handleFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val source = RGBLuminanceSource(bitmap)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result: Result? = barcodeReader.decode(binaryBitmap)
            if (result != null) {
                runOnUiThread { handleQrResult(result) }
            }
        } catch (e: Exception) {
            Log.e("QrScannerActivity", "Frame-Verarbeitung fehlgeschlagen", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun handleQrResult(result: Result) {
        val raw = result.text.trim()
        val store = OnionStore.get() ?: OnionStore.init(this)
        if (store.extractOnion(raw) != null) {
            store.add(raw)
            binding.tvScanResult.text = getString(R.string.scanner_saved)
            Toast.makeText(this, R.string.scanner_saved, Toast.LENGTH_SHORT).show()
        } else {
            binding.tvScanResult.text = getString(R.string.scanner_invalid)
            Toast.makeText(this, R.string.scanner_invalid, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
