package com.meher.btthermalprint

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.math.max
import kotlin.math.min

object EscPosEncoder {
    private val textCharset: Charset = Charset.forName("UTF-8")

    fun text(text: String): ByteArray = ByteArrayOutputStream().use { out ->
        out.write(byteArrayOf(0x1B, 0x40))
        out.write(text.replace("\r\n", "\n").toByteArray(textCharset))
        out.write(byteArrayOf(0x0A, 0x0A, 0x0A))
        out.toByteArray()
    }

    fun bitmap(bitmap: Bitmap, printerWidthPx: Int): ByteArray = ByteArrayOutputStream().use { out ->
        val scaled = bitmap.scaleToWidth(printerWidthPx)
        val width = scaled.width - (scaled.width % 8)
        val cropped = if (width != scaled.width) Bitmap.createBitmap(scaled, 0, 0, width, scaled.height) else scaled
        out.write(byteArrayOf(0x1B, 0x40))
        out.writeRaster(cropped)
        out.write(byteArrayOf(0x0A, 0x0A, 0x0A))
        out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeRaster(bitmap: Bitmap) {
        val widthBytes = bitmap.width / 8
        val height = bitmap.height
        write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
        write(byteArrayOf((widthBytes and 0xFF).toByte(), ((widthBytes shr 8) and 0xFF).toByte()))
        write(byteArrayOf((height and 0xFF).toByte(), ((height shr 8) and 0xFF).toByte()))

        val pixels = IntArray(bitmap.width)
        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (xByte in 0 until widthBytes) {
                var value = 0
                for (bit in 0 until 8) {
                    val x = xByte * 8 + bit
                    if (pixels[x].isDark()) {
                        value = value or (0x80 shr bit)
                    }
                }
                write(value)
            }
        }
    }

    private fun Bitmap.scaleToWidth(targetWidth: Int): Bitmap {
        val safeWidth = max(8, targetWidth)
        if (width == safeWidth) return this
        val ratio = safeWidth.toFloat() / width.toFloat()
        val targetHeight = max(1, (height * ratio).toInt())
        return Bitmap.createScaledBitmap(this, safeWidth, targetHeight, true)
    }

    private fun Int.isDark(): Boolean {
        val alpha = Color.alpha(this)
        if (alpha < 96) return false
        val red = Color.red(this)
        val green = Color.green(this)
        val blue = Color.blue(this)
        val luminance = (red * 0.299) + (green * 0.587) + (blue * 0.114)
        return min(255.0, luminance) < 160.0
    }
}
