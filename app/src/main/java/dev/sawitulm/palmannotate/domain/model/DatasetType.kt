package dev.sawitulm.palmannotate.domain.model

enum class DatasetType {
    MULTISIDE,
    BUNCH_WEIGHT,
    MULTISIDE_VIDEO;

    /** One mp4 per tree is part of the package; a tree cannot be committed without it. */
    val requiresVideo: Boolean get() = this == MULTISIDE_VIDEO

    fun runGroupKey(baseGroupKey: String): String =
        if (this == MULTISIDE) baseGroupKey else "${name}__$baseGroupKey"

    fun allowsEarlyFinish(capturedSides: Int, configuredSides: Int): Boolean =
        this == BUNCH_WEIGHT && capturedSides >= 1 && capturedSides < configuredSides

    companion object {
        fun fromPersisted(value: String?): DatasetType =
            entries.firstOrNull { it.name == value } ?: MULTISIDE
    }
}

