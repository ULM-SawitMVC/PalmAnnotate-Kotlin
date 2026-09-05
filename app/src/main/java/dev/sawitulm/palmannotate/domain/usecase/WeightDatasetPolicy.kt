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

    /**
     * The `isComplete` flag a saved revision may carry.
     *
     * For [DatasetType.BUNCH_WEIGHT] the flag is DERIVED from the revision, not declared by the
     * caller: a weight sample is finished exactly when every bunch has a class and a valid weight,
     * which is what [completionError] already decides. Deriving it is what makes the flag
     * two-way. A gate of the form `(markComplete || wasComplete) && valid` only ever revokes: the
     * silent auto-save that fires on a side swipe or an Edit/Review toggle sees the freshly drawn,
     * still-unassigned bunch and clears the flag, and no later save can restore it because the
     * caller that grants it is only reachable through "Save & next sample". A finished, valid
     * sample would then sit in the list as Draft for the rest of the collection day.
     *
     * [completionError] is null for every [DatasetType.MULTISIDE] session, so a multiside tree
     * keeps its historical semantics exactly: only an explicit completion grants the flag, and
     * nothing revokes it.
     */
    fun resolveCompletion(
        session: ActiveSession,
        markComplete: Boolean,
        wasComplete: Boolean,
    ): Boolean =
        if (session.datasetType == DatasetType.BUNCH_WEIGHT) completionError(session) == null
        else markComplete || wasComplete
}

