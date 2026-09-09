package dev.sawitulm.palmannotate

import dev.sawitulm.palmannotate.data.export.ExportManager
import dev.sawitulm.palmannotate.data.yolo.YoloParser
import dev.sawitulm.palmannotate.domain.model.ActiveSession
import dev.sawitulm.palmannotate.domain.model.AnnotationClass
import dev.sawitulm.palmannotate.domain.model.Bbox
import dev.sawitulm.palmannotate.domain.model.BunchMeasurements
import dev.sawitulm.palmannotate.data.storage.ArtifactIdentityPolicy
import dev.sawitulm.palmannotate.domain.model.CaptureSetPolicy
import dev.sawitulm.palmannotate.domain.model.CrossSideLink
import dev.sawitulm.palmannotate.domain.model.DatasetType
import dev.sawitulm.palmannotate.domain.model.OutputSchema
import dev.sawitulm.palmannotate.domain.model.TreeSide
import dev.sawitulm.palmannotate.domain.results.ResultsComputer
import dev.sawitulm.palmannotate.domain.usecase.SessionUseCases
import dev.sawitulm.palmannotate.domain.usecase.WeightDatasetPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BunchWeightTests {
    private fun bbox(
        id: String,
        classId: Int = AnnotationClass.UNASSIGNED.id,
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
                side(1, listOf(bbox("b0"))),
            ),
        )

        val linked = SessionUseCases.addManualLink(initial, 0, "b0", 1, "b0")

        assertEquals(measurements, linked.sides[0].bboxes[0].measurements)
        assertEquals(measurements, linked.sides[1].bboxes[0].measurements)
        assertEquals(1, linked.confirmedLinks.size)
    }

    @Test
    fun `completion requires weight but does not require a ripeness class`() {
        assertNotNull(WeightDatasetPolicy.completionError(session(emptyList())))
        assertNull(
            WeightDatasetPolicy.completionError(
                session(
                    listOf(
                        side(
                            0,
                            listOf(
                                bbox(
                                    "b0",
                                    classId = AnnotationClass.UNASSIGNED.id,
                                    measurements = BunchMeasurements(11.2),
                                ),
                            ),
                        ),
                    ),
                ),
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
        assertFalse(json.getJSONObject("images").getJSONObject("side_1")
            .getJSONArray("annotations").getJSONObject(0).has("class_id"))
        assertFalse(bunches.getJSONObject(0).has("class"))
        assertFalse(bunches.getJSONObject(0).getJSONArray("appearances").getJSONObject(0).has("class_name"))
        assertFalse(json.getJSONObject("summary").has("by_class"))

        val parsed = OutputSchema.toSessionData(json)
        assertEquals(measurements, parsed.sides[0].bboxes[0].measurements)
        assertEquals(measurements, parsed.sides[1].bboxes[0].measurements)
        assertEquals(AnnotationClass.UNASSIGNED.id, parsed.sides[0].bboxes[0].classId)
        assertEquals(1, parsed.confirmedLinks.size)
    }

    @Test
    fun `weight exports use measurements and one implicit YOLO object class`() {
        val measurements = BunchMeasurements(9.75, 60.0, null, "uji, tertaut")
        val original = session(
            sides = listOf(
                side(0, listOf(bbox("b0", measurements = measurements))),
                side(1, listOf(bbox("b0", measurements = measurements))),
            ),
            links = listOf(CrossSideLink.create("L0", 0, "b0", 1, "b0")),
        )
        val results = ResultsComputer.compute(original)

        val yolo = YoloParser.serialize(
            original.sides[0].bboxes,
            original.sides[0].imageWidth,
            original.sides[0].imageHeight,
            classIdOverride = 0,
        )
        assertTrue(yolo.startsWith("0 "))
        assertEquals(yolo, ExportManager.generateYoloTxt(original.sides[0], DatasetType.BUNCH_WEIGHT))

        val csv = ExportManager.generateCsv(original, results)
        assertTrue(csv.startsWith("sample_name,split,bunch_id,appearance_count,weight_kg"))
        assertTrue(csv.contains("9.75,60.0,,\"uji, tertaut\""))
        assertFalse(csv.contains(",B1,"))

        val identity = ExportManager.generateIdentityJson(original, results)
        val identityBunch = identity.getJSONArray("bunches").getJSONObject(0)
        assertEquals(DatasetType.BUNCH_WEIGHT.name, identity.getString("dataset_type"))
        assertEquals(9.75, identityBunch.getDouble("weight_kg"), 0.0001)
        assertFalse(identity.has("classMismatchCount"))
        assertFalse(identityBunch.has("classMismatch"))
        assertFalse(identityBunch.getJSONArray("detections").getJSONObject(0).has("class"))
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

    /** BUG-003: separate runs were not enough; the artifact namespace is the tree name. */
    @Test
    fun `weight samples get their own name namespace while multiside names are untouched`() {
        assertEquals(
            "DAMIMAS_QA0905_0001",
            CaptureSetPolicy.treeName("DAMIMAS", "QA0905", "", 1, DatasetType.MULTISIDE),
        )
        assertEquals(
            "DAMIMAS_QA0905_BW_0001",
            CaptureSetPolicy.treeName("DAMIMAS", "QA0905", "", 1, DatasetType.BUNCH_WEIGHT),
        )
        // The two modules may hold the same variety/block/sequence without sharing a filename.
        assertNotEquals(
            CaptureSetPolicy.treeName("DAMIMAS", "QA0905", "", 1, DatasetType.MULTISIDE),
            CaptureSetPolicy.treeName("DAMIMAS", "QA0905", "", 1, DatasetType.BUNCH_WEIGHT),
        )
        // The marker sits before the naming token, so variety/block/sequence stay derivable.
        val tokenised =
            CaptureSetPolicy.treeName("DAMIMAS", "QA0905", "K7Q2M1", 42, DatasetType.BUNCH_WEIGHT)
        assertEquals("DAMIMAS_QA0905_BW_K7Q2M1_0042", tokenised)
        assertEquals(
            "DAMIMAS_QA0905_BW_0042",
            CaptureSetPolicy.logicalTreeName(tokenised, "K7Q2M1"),
        )
        // ExportManager.deriveTreeNumber / FolderResumeImporter.parseTreeId both read the
        // trailing numeric segment; the marker must not sit between it and the name.
        assertEquals(42, tokenised.substringAfterLast('_').toInt())
        assertEquals("QA0905", tokenised.split('_')[1])
        assertNull(ArtifactIdentityPolicy.treeNameError(tokenised))

        // The marker is only positionally distinct from a block, so it is reserved as a block
        // token: without that, a weight run with no block and a multiside run whose block is "BW"
        // would both write DAMIMAS_BW_0001.
        assertEquals(
            CaptureSetPolicy.treeName("DAMIMAS", "", "", 1, DatasetType.BUNCH_WEIGHT),
            CaptureSetPolicy.treeName("DAMIMAS", "BW", "", 1, DatasetType.MULTISIDE),
        )
        assertNotNull(CaptureSetPolicy.blockError("BW"))
        assertNotNull(CaptureSetPolicy.blockError("b-w"))
        assertNotNull(CaptureSetPolicy.blockError(""))
        assertNotNull(CaptureSetPolicy.blockError("   "))
        assertNull(CaptureSetPolicy.blockError("QA0905"))
        assertNull(CaptureSetPolicy.blockError("A21B"))
        assertNull(CaptureSetPolicy.blockError("BW2"))
    }

    /** BUG-002: a draft save kept the Complete flag an earlier, valid revision had earned. */
    @Test
    fun `an invalid weight revision cannot stay complete`() {
        val complete = session(listOf(side(0, listOf(bbox("b0", measurements = BunchMeasurements(weightKg = 12.5))))))
        assertTrue(WeightDatasetPolicy.resolveCompletion(complete, markComplete = true, wasComplete = false))
        assertTrue(WeightDatasetPolicy.resolveCompletion(complete, markComplete = false, wasComplete = true))

        // Complete sample, then an extra box left unweighed, saved as a draft.
        val revised = session(
            listOf(
                side(
                    0,
                    listOf(
                        bbox("b0", measurements = BunchMeasurements(weightKg = 12.5)),
                        bbox("b1", classId = AnnotationClass.UNASSIGNED.id),
                    ),
                ),
            ),
        )
        assertNotNull(WeightDatasetPolicy.completionError(revised))
        assertFalse(WeightDatasetPolicy.resolveCompletion(revised, markComplete = false, wasComplete = true))
        assertFalse(WeightDatasetPolicy.resolveCompletion(revised, markComplete = true, wasComplete = true))

        // Revocation must be two-way. The silent auto-save on a side swipe sees the freshly drawn,
        // unweighed bunch and clears the flag; once the operator enters a weight, the very next
        // save must be able to restore it. A one-way gate would strand a
        // finished sample as Draft, because only "Save & next sample" ever passes markComplete.
        assertTrue(WeightDatasetPolicy.resolveCompletion(complete, markComplete = false, wasComplete = false))

        // Multiside has no completion contract: the flag keeps its historical, sticky meaning.
        val multiside = ActiveSession(
            sessionId = "tree", treeName = "DAMIMAS_QA0905_0001", split = "field",
            sides = listOf(side(0, listOf(bbox("b0", classId = AnnotationClass.UNASSIGNED.id)))),
            suggestedLinks = emptyList(), confirmedLinks = emptyList(), metadata = null,
            datasetType = DatasetType.MULTISIDE,
        )
        assertTrue(WeightDatasetPolicy.resolveCompletion(multiside, markComplete = false, wasComplete = true))
        assertFalse(WeightDatasetPolicy.resolveCompletion(multiside, markComplete = false, wasComplete = false))
        assertTrue(WeightDatasetPolicy.resolveCompletion(multiside, markComplete = true, wasComplete = false))
    }
}
