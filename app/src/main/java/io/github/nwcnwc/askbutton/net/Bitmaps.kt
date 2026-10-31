package io.github.nwcnwc.askbutton.net

import android.graphics.Bitmap
import io.github.nwcnwc.askbutton.logic.ImageSize
import java.io.ByteArrayOutputStream

object Bitmaps {
    fun jpeg(bitmap: Bitmap, maxEdge: Int, quality: Int): ByteArray {
        val (width, height) = ImageSize.fit(bitmap.width, bitmap.height, maxEdge)
        val scaled = if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        try {
            val stream = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                error("Couldn't encode the screenshot")
            }
            return stream.toByteArray()
        } finally {
            if (scaled != bitmap) scaled.recycle()
        }
    }

    fun fromRgba(width: Int, height: Int, rowStride: Int, pixelStride: Int, pixels: java.nio.ByteBuffer): Bitmap {
        val stride = pixelStride.coerceAtLeast(1)
        val rowPadding = rowStride - stride * width
        val paddedWidth = width + (rowPadding / stride).coerceAtLeast(0)
        val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        pixels.rewind()
        bitmap.copyPixelsFromBuffer(pixels)
        if (paddedWidth == width) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        bitmap.recycle()
        return cropped
    }
}
