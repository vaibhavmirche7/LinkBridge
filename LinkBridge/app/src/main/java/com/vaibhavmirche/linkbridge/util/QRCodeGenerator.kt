package com.vaibhavmirche.linkbridge.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QRCodeGenerator {
    
    /**
     * Generates a QR code bitmap for the given URL
     * @param url The URL to encode in the QR code
     * @param size The size of the QR code in pixels (width and height)
     * @return Bitmap of the QR code or null if generation fails
     */
    fun generateQRCode(url: String, size: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val hints = hashMapOf<EncodeHintType, Any>().apply {
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
                put(EncodeHintType.MARGIN, 1)
            }
            
            val bitMatrix: BitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            
            bitmap
        } catch (e: WriterException) {
            e.printStackTrace()
            null
        }
    }
}
