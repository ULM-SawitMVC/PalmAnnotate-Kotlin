package dev.sawitulm.palmannotate.data.storage

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Validation contract for one captured metric-depth artifact.
 *
 * Depth is useful only as a complete pair: a little-endian Y16 raster plus the JSON that
 * describes how to decode it. Callers must reject both files when either half is missing or
 * inconsistent; pairing a fresh RGB image with stale/partial depth is worse than having no depth.
 */
internal object DepthArtifactContract {
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /**
     * Structural validity is not enough to prove an old raw belongs to an old RGB. Only sidecars
     * carrying both content identities can upgrade UNKNOWN provenance to verified Orbbec.
     */
    fun hasContentBindings(metadataText: String): Boolean {
        val json = runCatching { JSONObject(metadataText) }.getOrNull() ?: return false
        return SHA256_REGEX.matches(json.optString("rawSha256")) &&
            SHA256_REGEX.matches(json.optString("rgbSha256"))
    }

    /**
     * Returns null when [rawSize] and [metadataText] form a valid Y16 pair, otherwise a concise
     * reason suitable for logs. This does not inspect pixel values; zero is a legitimate invalid
     * depth sample emitted by a sensor.
     */
    fun validationError(
        rawSize: Long,
        metadataText: String,
        actualRawSha256: String? = null,
        actualRgbSha256: String? = null,
    ): String? {
        val json = try {
            JSONObject(metadataText)
        } catch (_: Exception) {
            return "metadata is not valid JSON"
        }

        val width = json.optLong("width", 0L)
        val height = json.optLong("height", 0L)
        if (width <= 0L || height <= 0L) return "width and height must be positive"

        if (json.optString("format") != "Y16") return "format must be Y16"
        if (json.has("encoding") && json.optString("encoding") != "uint16le") {
            return "encoding must be uint16le"
        }

        val valueScale = json.optDouble("valueScale", Double.NaN)
        if (!valueScale.isFinite() || valueScale <= 0.0) {
            return "valueScale must be finite and positive"
        }

        val expectedSize = try {
            Math.multiplyExact(Math.multiplyExact(width, height), 2L)
        } catch (_: ArithmeticException) {
            return "dimensions overflow expected byte count"
        }
        if (rawSize != expectedSize) return "raw size $rawSize != expected $expectedSize"

        validateChecksum(json, "rawSha256", actualRawSha256)?.let { return it }
        validateChecksum(json, "rgbSha256", actualRgbSha256)?.let { return it }

        return null
    }

    private fun validateChecksum(json: JSONObject, key: String, actual: String?): String? {
        if (!json.has(key)) return null // Legacy sidecars remain readable, but are lower assurance.
        val expected = json.optString(key)
        if (!SHA256_REGEX.matches(expected)) return "$key is not a valid SHA-256"
        if (actual == null) return "$key could not be verified"
        if (!expected.equals(actual, ignoreCase = true)) return "$key checksum mismatch"
        return null
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        val alphabet = "0123456789abcdef"
        for (i in indices) {
            val value = this[i].toInt() and 0xff
            chars[i * 2] = alphabet[value ushr 4]
            chars[i * 2 + 1] = alphabet[value and 0x0f]
        }
        return String(chars)
    }
}
