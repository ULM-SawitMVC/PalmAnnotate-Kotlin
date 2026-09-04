package dev.sawitulm.palmannotate.domain.usecase

import dev.sawitulm.palmannotate.domain.model.ActiveSession
import dev.sawitulm.palmannotate.domain.model.AnnotationClass
import dev.sawitulm.palmannotate.domain.model.DatasetType
import dev.sawitulm.palmannotate.domain.results.ResultsComputer

object WeightDatasetPolicy {
    fun completionError(session: ActiveSession): String? {
        if (session.datasetType != DatasetType.BUNCH_WEIGHT) return null
        if (session.totalBboxes == 0) return "Add at least one bunch bounding box."

        for ((index, members) in ResultsComputer.compute(session).clusters.values.withIndex()) {
            if (members.any { it.className == AnnotationClass.UNASSIGNED.displayName }) {
                return "Choose a class for bunch ${index + 1}."
            }
            val measurements = members.map { it.measurements.normalized() }.distinct()
            if (measurements.size != 1) {
                return "Linked appearances for bunch ${index + 1} have different measurements."
            }
            measurements.single().validationError()?.let { error ->
                return "Bunch ${index + 1}: $error"
            }
        }
        return null
    }
}

