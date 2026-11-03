package com.example.homemonitor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Verwaltet die Kamera (CameraX): Vorschau, Aufnahme und Speicherung in die Galerie.
 * Singleton; wird an den Lifecycle der Activity gebunden.
 */
class CameraManager private constructor(
    private val context: Context,
    private val previewView: PreviewView,
    private val onImageCaptured: (ByteArray?) -> Unit
) {

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var owner: androidx.lifecycle.LifecycleOwner? = null

    // Aktuell gewählte Linse: 0 = hinten (Standard), 1 = vorne
    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    @Volatile var latestJpeg: ByteArray? = null
        private set

    companion object {
        @Volatile private var instance: CameraManager? = null

        fun init(context: Context, previewView: PreviewView,
                 onImageCaptured: (ByteArray?) -> Unit): CameraManager {
            return instance ?: synchronized(this) {
                instance ?: CameraManager(context, previewView, onImageCaptured).also { instance = it }
            }
        }

        fun get(): CameraManager? = instance
    }

    // Kamera-Provider finden und an das Lifecycle der Activity binden
    fun bindToLifecycle(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        owner = lifecycleOwner
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindPreview(lifecycleOwner)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindPreview(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        try {
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector, preview, imageCapture
            )
            Log.d("CameraManager", "Camera gebunden")
        } catch (e: Exception) {
            Log.e("CameraManager", "bindToLifecycle fehlgeschlagen", e)
        }
    }

    // Kamera auf die andere Seite umschalten (hinten <-> vorne)
    fun flip() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val lifecycle = owner ?: return
        bindPreview(lifecycle)
    }

    // Foto von der aktuell gewählten Kamera aufnehmen
    fun takeSnapshot() {
        imageCapture?.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                super.onCaptureSuccess(image)
                val bytes = imageToBytes(image)
                image.close()
                latestJpeg = bytes
                onImageCaptured(bytes)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("CameraManager", "Aufnahme fehlgeschlagen", exception)
                onImageCaptured(null)
            }
        })
    }

    private fun imageToBytes(image: ImageProxy): ByteArray? {
        return try {
            // YUV -> Bitmap -> JPEG Konvertierung über die CameraX ImageProxy API
            val bitmap = image.toBitmap()
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            baos.toByteArray()
        } catch (e: Exception) {
            Log.e("CameraManager", "imageToBytes", e)
            null
        }
    }

    // Bild in die Android-Galerie (MediaStore) speichern
    fun saveToGallery(bitmap: Bitmap, title: String): Boolean {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "${title}_$timestamp.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/HomeMoniTor")
            }
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: return false
            val os = resolver.openOutputStream(uri) ?: return false
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)
            os.close()
            Toast.makeText(context, "In Galerie gespeichert", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Log.e("CameraManager", "saveToGallery", e)
            Toast.makeText(context, "Speichern fehlgeschlagen", Toast.LENGTH_SHORT).show()
            false
        }
    }

    // Executor beenden und Singleton freigeben
    fun shutdown() {
        cameraExecutor.shutdown()
        latestJpeg = null
        instance = null
    }
}
