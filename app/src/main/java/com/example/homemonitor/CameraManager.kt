package com.example.homemonitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager private constructor(
    private val context: Context,
    private val previewView: PreviewView,
    private val onImageCaptured: (ByteArray?) -> Unit
) {

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Aktuell gewählte Linse: 0 = hinten (Standard), 1 = vorne
    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    var latestJpeg: ByteArray? = null
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
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindPreview()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindPreview() {
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        cameraProvider?.unbindAll()
        try {
            cameraProvider?.bindToLifecycle(
                lifecycleOwner as androidx.lifecycle.LifecycleOwner,
                cameraSelector, preview, imageCapture
            )
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
        bindPreview()
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

            override fun onFailure(exception: ImageCaptureException) {
                super.onFailure(exception)
                Log.e("CameraManager", "Aufnahme fehlgeschlagen", exception)
                onImageCaptured(null)
            }
        })
    }

    private fun imageToBytes(image: ImageProxy): ByteArray? {
        return try {
            val buffer = image.planes[0].buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            // YUV -> JPEG Konvertierung über Bitmap (einfach & verlässlich)
            val bitmap = yuvToBitmap(image, data)
            if (bitmap == null) return data
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            baos.toByteArray()
        } catch (e: Exception) {
            Log.e("CameraManager", "imageToBytes", e)
            null
        }
    }

    private fun yuvToBitmap(image: ImageProxy, yuv: ByteArray): Bitmap? {
        return try {
            val width = image.width
            val height = image.height
            val ySize = width * height
            val uSize = (width + 1) / 2 * ((height + 1) / 2)

            val y = ByteArray(ySize)
            val u = ByteArray(uSize)
            val v = ByteArray(uSize)

            var yOff = 0
            val rowStride = image.planes[0].rowStride
            val pixelStride = image.planes[0].pixelStride
            var i = 0
            while (i < height) {
                System.arraycopy(yuv, yOff, y, i * width, width)
                yOff += rowStride
                i++
            }

            // U/V Ebenen (vereinfacht: plane 1 & 2)
            val uvRowStride = image.planes[1].rowStride
            val uvPixelStride = image.planes[1].pixelStride
            var ui = 0
            var uIdx = 0
            while (ui < (height + 1) / 2) {
                var vi = 0
                var vIdx = ui * uvRowStride
                while (vi < ((width + 1) / 2) * uvPixelStride step uvPixelStride) {
                    u[uIdx] = yuv[vIdx]
                    v[uIdx] = yuv[vIdx + 1]
                    vIdx += uvPixelStride * 2
                    uIdx++
                }
                ui++
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val header = ImageDecoder.Header(
                    ImageDecoder.Format.RGBA_8888, width, height)
                val source = ImageDecoder.createSource(
                    yuvToRgba(y, u, v, width, height), header)
                return ImageDecoder.decodeBitmap(source)
            } else {
                // Fallback: direktes ARGB Bitmap bauen (nur Y-Kanal als Grau)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                for (x in 0 until width) {
                    for (y in 0 until height) {
                        val gray = y[y * width + x] and 0xFF
                        bmp.setPixel(x, y, (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray)
                    }
                }
                return bmp
            }
        } catch (e: Exception) {
            Log.e("CameraManager", "yuvToBitmap", e)
            null
        }
    }

    @Suppress("SameParameterValue")
    private fun yuvToRgba(y: ByteArray, u: ByteArray, v: ByteArray,
                          width: Int, height: ByteArray = height.toByteArray()): ByteArray {
        // Diese Methode wird nicht direkt genutzt; der Fallback oben reicht.
        val rgba = ByteArray(width * height * 4)
        var idx = 0
        for (row in 0 until height.toInt()) {
            for (col in 0 until width) {
                val yIdx = row * width + col
                val yy = (y[yIdx] and 0xFF).toInt()
                val uIdx = (row / 2) * (width / 2) + (col / 2)
                val uu = (u[uIdx] and 0xFF).toInt() - 128
                val vv = (v[uIdx] and 0xFF).toInt() - 128
                var r = (yy + 1.402 * vv).toInt()
                var g = (yy - 0.344 * uu - 0.714 * vv).toInt()
                var b = (yy + 1.772 * uu).toInt()
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)
                rgba[idx++] = r.toByte()
                rgba[idx++] = g.toByte()
                rgba[idx++] = b.toByte()
                rgba[idx++] = -1
            }
        }
        return rgba
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
