package dev.sawitulm.palmannotate.data.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Materializes EXIF orientation into JPEG pixels.
 *
 * CameraX may store the sensor-oriented pixel buffer and describe the display rotation only in
 * EXIF. Coil honors that tag, while BitmapFactory, the detector, and many YOLO consumers do not.
 * New phone captures are therefore canonicalized once before their dimensions and SHA-256 are
 * committed. Identity-oriented JPEGs are returned byte-for-byte to avoid unnecessary recompression.
 */
internal object JpegOrientationNormalizer {
    @Throws(IOException::class)
    fun normalize(jpegBytes: ByteArray): ByteArray {
        if (jpegBytes.isEmpty()) throw IOException("Captured JPEG is empty")

        val orientation = try {
            ExifInterface(ByteArrayInputStream(jpegBytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )
        } catch (e: Exception) {
            throw IOException("Captured JPEG EXIF could not be read", e)
        }
        val transform = ExifOrientationPolicy.transformFor(orientation)
        if (transform.isIdentity) return jpegBytes

        var source: Bitmap? = null
        var normalized: Bitmap? = null
        try {
            source = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: throw IOException("Captured JPEG pixels could not be decoded")
            val matrix = Matrix().apply {
                if (transform.flipHorizontal) postScale(-1f, 1f)
                if (transform.clockwiseDegrees != 0) {
                    postRotate(transform.clockwiseDegrees.toFloat())
                }
            }
            normalized = Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                matrix,
                true,
            )
            val output = ByteArrayOutputStream(jpegBytes.size.coerceAtLeast(32 * 1024))
            if (!normalized.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                throw IOException("Normalized JPEG could not be encoded")
            }
            return output.toByteArray().also {
                if (it.isEmpty()) throw IOException("Normalized JPEG is empty")
            }
        } catch (e: OutOfMemoryError) {
            throw IOException("Not enough memory to normalize captured JPEG orientation", e)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Captured JPEG orientation normalization failed", e)
        } finally {
            if (normalized != null && normalized !== source && !normalized.isRecycled) {
                normalized.recycle()
            }
            if (source != null && !source.isRecycled) source.recycle()
        }
    }
}
