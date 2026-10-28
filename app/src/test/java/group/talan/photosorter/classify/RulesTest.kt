package group.talan.photosorter.classify

import group.talan.photosorter.core.Category
import org.junit.Assert.assertEquals
import org.junit.Test

class RulesTest {

    @Test
    fun scannedPageIsDocument() {
        val d = Rules.decide(Scores(whiteRatio = 0.8f, textAreaRatio = 0.3f, textBlockCount = 15))
        assertEquals(Category.DOCUMENTS, d.category)
    }

    @Test
    fun drawingWithoutTextIsDocument() {
        val d = Rules.decide(Scores(whiteRatio = 0.7f, edgeDensity = 0.08f))
        assertEquals(Category.DOCUMENTS, d.category)
    }

    @Test
    fun passportPhotoIsDocumentEvenIfModelSaysFamily() {
        val d = Rules.decide(
            Scores(whiteRatio = 0.7f, textBlockCount = 10, modelLabel = "family", modelConfidence = 0.95f)
        )
        assertEquals(Category.DOCUMENTS, d.category)
    }

    @Test
    fun confidentWorkIsWork() {
        val d = Rules.decide(Scores(modelLabel = "work", modelConfidence = 0.93f))
        assertEquals(Category.WORK, d.category)
    }

    @Test
    fun unsureWorkGoesToOther() {
        val d = Rules.decide(Scores(modelLabel = "work", modelConfidence = 0.75f))
        assertEquals(Category.OTHER, d.category)
    }

    @Test
    fun confidentFamilyIsFamily() {
        val d = Rules.decide(Scores(modelLabel = "family", modelConfidence = 0.8f))
        assertEquals(Category.FAMILY, d.category)
    }

    @Test
    fun unsureFamilyGoesToOther() {
        val d = Rules.decide(Scores(modelLabel = "family", modelConfidence = 0.55f))
        assertEquals(Category.OTHER, d.category)
    }

    @Test
    fun modelOtherGoesToOther() {
        val d = Rules.decide(Scores(modelLabel = "other", modelConfidence = 0.99f))
        assertEquals(Category.OTHER, d.category)
    }

    @Test
    fun withoutModelEverythingExceptDocumentsGoesToOther() {
        assertEquals(Category.OTHER, Rules.decide(Scores()).category)
    }

    @Test
    fun brightPhotoWithoutTextOrLinesIsNotDocument() {
        val d = Rules.decide(Scores(whiteRatio = 0.6f, edgeDensity = 0.01f))
        assertEquals(Category.OTHER, d.category)
    }
}
