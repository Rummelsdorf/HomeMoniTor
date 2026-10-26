package com.example.homemonitor

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Hashtable

/**
 * Erzeugt QR-Code-Bilder – z. B. für die Onion-Adresse des Hidden Service.
 */
object QrGenerator {

    /**
     * Generiert einen QR-Code für den übergebenen Text (z. B. Onion-Adresse).
     *
     * @param text Der zu kodierende Text
     * @param sizePx Größe des QR-Codes in Pixeln (quadratisch)
     * @return Das erzeugte QR-Code-Bitmap
     * @throws WriterException Wenn der Text nicht als QR-Code dargestellt werden kann
     */
    @Throws(WriterException::class)
    fun generate(text: String, sizePx: Int): Bitmap {
        val hints = Hashtable<EncodeHintType, Any>()
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.MARGIN] = 1

        val writer = QRCodeWriter()
        val matrix: BitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
