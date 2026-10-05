package com.claytonmuhoza.easybilling.platform.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Turns a logo chosen by the user into what is stored and printed: a PNG no larger than
 * [MAX_SIDE] pixels.
 */
object LogoImage {
    /** Longest side of a stored logo, in pixels. */
    const val MAX_SIDE = 600

    private const val PNG_QUALITY = 100

    /**
     * @param image the bytes of an image file.
     * @return the logo as a PNG, or null if [image] is not an image.
     */
    fun toPng(image: ByteArray): ByteArray? {
        val decoded = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val png = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, png)
        return png.toByteArray()
    }
}
