package dev.sawitulm.palmannotate.domain.model

data class BunchMeasurements(
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val circumferenceCm: Double? = null,
    val notes: String? = null,
) {
    val hasAnyValue: Boolean
        get() = weightKg != null || heightCm != null || circumferenceCm != null || !notes.isNullOrBlank()

    fun normalized() = copy(notes = notes?.trim()?.takeIf(String::isNotEmpty))

    fun validationError(): String? = when {
        weightKg == null -> "Weight is required."
        !weightKg.isFinite() || weightKg <= 0.0 -> "Weight must be greater than zero."
        heightCm != null && (!heightCm.isFinite() || heightCm <= 0.0) ->
            "Height must be greater than zero when provided."
        circumferenceCm != null && (!circumferenceCm.isFinite() || circumferenceCm <= 0.0) ->
            "Circumference must be greater than zero when provided."
        else -> null
    }

    companion object {
        fun parseInput(
            weight: String,
            height: String,
            circumference: String,
            notes: String,
        ): Result<BunchMeasurements> = runCatching {
            fun parse(text: String, label: String): Double? {
                if (text.isBlank()) return null
                return text.trim().replace(',', '.').toDoubleOrNull()
                    ?: throw IllegalArgumentException("$label must be a number.")
            }

            BunchMeasurements(
                weightKg = parse(weight, "Weight"),
                heightCm = parse(height, "Height"),
                circumferenceCm = parse(circumference, "Circumference"),
                notes = notes,
            ).normalized().also { measurements ->
                measurements.validationError()?.let { throw IllegalArgumentException(it) }
            }
        }
    }
}
