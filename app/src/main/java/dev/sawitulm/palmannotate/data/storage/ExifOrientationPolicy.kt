package dev.sawitulm.palmannotate.data.storage

/**
 * Pixel transform encoded by the TIFF/EXIF Orientation tag.
 *
 * Mirrored orientations are expressed in the order defined by ExifInterface:
 * horizontal flip first, followed by clockwise rotation.
 */
internal data class ExifOrientationTransform(
    val flipHorizontal: Boolean,
    val clockwiseDegrees: Int,
) {
    val isIdentity: Boolean
        get() = !flipHorizontal && clockwiseDegrees == 0
}

internal object ExifOrientationPolicy {
    fun transformFor(orientation: Int): ExifOrientationTransform = when (orientation) {
        2 -> ExifOrientationTransform(flipHorizontal = true, clockwiseDegrees = 0)
        3 -> ExifOrientationTransform(flipHorizontal = false, clockwiseDegrees = 180)
        4 -> ExifOrientationTransform(flipHorizontal = true, clockwiseDegrees = 180)
        5 -> ExifOrientationTransform(flipHorizontal = true, clockwiseDegrees = 270)
        6 -> ExifOrientationTransform(flipHorizontal = false, clockwiseDegrees = 90)
        7 -> ExifOrientationTransform(flipHorizontal = true, clockwiseDegrees = 90)
        8 -> ExifOrientationTransform(flipHorizontal = false, clockwiseDegrees = 270)
        else -> ExifOrientationTransform(flipHorizontal = false, clockwiseDegrees = 0)
    }
}
