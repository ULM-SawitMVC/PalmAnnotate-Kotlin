package dev.sawitulm.palmannotate

import dev.sawitulm.palmannotate.data.export.ExportManager
import dev.sawitulm.palmannotate.domain.model.ActiveSession
import dev.sawitulm.palmannotate.domain.model.AnnotationClass
import dev.sawitulm.palmannotate.domain.model.Bbox
import dev.sawitulm.palmannotate.domain.model.BunchMeasurements
import dev.sawitulm.palmannotate.domain.model.CrossSideLink
import dev.sawitulm.palmannotate.domain.model.DatasetType
import dev.sawitulm.palmannotate.domain.model.OutputSchema
import dev.sawitulm.palmannotate.domain.model.TreeSide
import dev.sawitulm.palmannotate.domain.usecase.SessionUseCases
import dev.sawitulm.palmannotate.domain.usecase.WeightDatasetPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BunchWeightTests {
    private fun bbox(
        id: String,
        classId: Int = AnnotationClass.B1.id,
        measurements: BunchMeasurements = BunchMeasurements(),
    ) = Bbox(
        id = id,
        classId = classId,
        className = AnnotationClass.fromId(classId).displayName,
        x1 = 10f,
        y1 = 20f,
        x2 = 100f,
        y2 = 160f,
        measurements = measurements,
    )

    private fun side(index: Int, boxes: List<Bbox>) = TreeSide(
        sideIndex = index,
        label = "Side ${index + 1}",
        imageUri = null,
        labelUri = null,
        imageWidth = 1000,
        imageHeight = 800,
        bboxes = boxes,
        originalBboxes = boxes,
    )

    private fun session(
        sides: List<TreeSide>,
        links: List<CrossSideLink> = emptyList(),
    ) = ActiveSession(
        sessionId = "sample",
        treeName = "WEIGHT_A01_0001",
        split = "field",
        sides = sides,
        suggestedLinks = emptyList(),
        confirmedLinks = links,
        metadata = null,
        datasetType = DatasetType.BUNCH_WEIGHT,
    )

    @Test
    fun `weight is required while optional measurements may stay empty`() {
        assertNotNull(BunchMeasurements().validationError())
        assertNull(BunchMeasurements(weightKg = 12.4).validationError())
        assertNull(
            BunchMeasurements(
                weightKg = 12.4,
                heightCm = 62.0,
                circumferenceCm = 84.5,
                notes = "  tangkai panjang  ",
            ).normalized().validationError(),
        )
        assertEquals(
            "tangkai panjang",
            BunchMeasurements(weightKg = 12.4, notes = "  tangkai panjang  ").normalized().notes,
        )
        assertNotNull(BunchMeasurements(weightKg = 12.4, heightCm = 0.0).validationError())
        assertNotNull(BunchMeasurements(weightKg = 12.4, circumferenceCm = -1.0).validationError())
    }

    @Test
    fun `measurement input accepts comma decimals and rejects invalid numbers`() {
        val parsed = BunchMeasurements.parseInput(
            weight = "12,4",
            height = "",
            circumference = "84.5",
            notes = "  matang merata  ",
        ).getOrThrow()

        assertEquals(12.4, parsed.weightKg!!, 0.0001)
        assertNull(parsed.heightCm)
        assertEquals(84.5, parsed.circumferenceCm!!, 0.0001)
        assertEquals("matang merata", parsed.notes)
        assertTrue(BunchMeasurements.parseInput("abc", "", "", "").isFailure)
        assertTrue(BunchMeasurements.parseInput("12", "nol", "", "").isFailure)
    }

    @Test
    fun `measurement edits propagate to every linked appearance`() {
        val initial = session(
            sides = listOf(side(0, listOf(bbox("b0"))), side(1, listOf(bbox("b1")))),
            links = listOf(CrossSideLink.create("L0", 0, "b0", 1, "b1")),
        )
        val measurements = BunchMeasurements(12.4, 62.0, 84.5, "tangkai panjang")

        val updated = SessionUseCases.setBboxMeasurements(initial, 1, "b1", measurements)

        assertEquals(measurements, updated.sides[0].bboxes[0].measurements)
        assertEquals(measurements, updated.sides[1].bboxes[0].measurements)
    }

    @Test
    fun `linking appearances keeps the existing nonempty measurement`() {
        val measurements = BunchMeasurements(weightKg = 9.75, notes = "uji timbang")
        val initial = session(
            sides = listOf(
                side(0, listOf(bbox("b0", measurements = measurements))),
                side(1, listOf(bbox("b1"))),
            ),
        )

        val linked = SessionUseCases.addManualLink(initial, 0, "b0", 1, "b1")

        assertEquals(measurements, linked.sides[0].bboxes[0].measurements)
        assertEquals(measurements, linked.sides[1].bboxes[0].measurements)
    }

    @Test
    fun `completion requires a class and weight for every physical bunch`() {
        assertNotNull(WeightDatasetPolicy.completionError(session(emptyList())))
        assertNotNull(
            WeightDatasetPolicy.completionError(
                session(listOf(side(0, listOf(bbox("b0", classId = AnnotationClass.UNASSIGNED.id))))),
            ),
        )
        assertNotNull(
            WeightDatasetPolicy.completionError(session(listOf(side(0, listOf(bbox("b0")))))),
        )
        assertNull(
            WeightDatasetPolicy.completionError(
                session(listOf(side(0, listOf(bbox("b0", measurements = BunchMeasurements(11.2)))))),
            ),
        )
    }

    @Test
    fun `weight export stores measurements once per bunch and round trips appearances`() {
        val measurements = BunchMeasurements(12.4, 62.0, 84.5, "tangkai panjang")
        val original = session(
            sides = listOf(
                side(0, listOf(bbox("b0", measurements = measurements))),
                side(1, listOf(bbox("b1", measurements = measurements))),
            ),
            links = listOf(CrossSideLink.create("L0", 0, "b0", 1, "b1")),
        )

        val json = ExportManager.generateOutputJson(original)
        val bunches = json.getJSONArray("bunches")
        assertEquals(1, bunches.length())
        assertEquals(12.4, bunches.getJSONObject(0).getDouble("weight_kg"), 0.0001)
        assertEquals(2, bunches.getJSONObject(0).getJSONArray("appearances").length())

        val parsed = OutputSchema.toSessionData(json)
        assertEquals(measurements, parsed.sides[0].bboxes[0].measurements)
        assertEquals(measurements, parsed.sides[1].bboxes[0].measurements)
    }

    @Test
    fun `dataset type preserves legacy group keys and separates weight sessions`() {
        assertEquals("DAMIMAS__A21B", DatasetType.MULTISIDE.runGroupKey("DAMIMAS__A21B"))
        assertEquals(
            "BUNCH_WEIGHT__DAMIMAS__A21B",
            DatasetType.BUNCH_WEIGHT.runGroupKey("DAMIMAS__A21B"),
        )
        assertEquals(DatasetType.MULTISIDE, DatasetType.fromPersisted("UNKNOWN"))
        assertTrue(DatasetType.BUNCH_WEIGHT.allowsEarlyFinish(capturedSides = 1, configuredSides = 2))
    }
}
