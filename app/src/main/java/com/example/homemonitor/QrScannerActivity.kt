package com.example.homemonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.homemonitor.databinding.ActivityQrScannerBinding
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.Result
import java.util.concurrent.Executors

class QrScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScannerBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val barcodeReader = MultiFormatReader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Kamera-Berechtigung anfordern
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            initCamera()
        }
    }

    private fun initCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindPreview()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreview() {
        try {
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(binding.scannerPreview.surfaceProvider)

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy: ImageProxy ->
                handleFrame(imageProxy)
            })

            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                imageAnalysis,
                preview
            )
        } catch (e: Exception) {
            Log.e("QrScannerActivity", "Camera-Bindung fehlgeschlagen", e)
            Toast.makeText(this, "Kamera nicht verfügbar", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initCamera()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CAMERA = 1001
    }
}