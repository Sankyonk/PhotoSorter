package group.talan.photosorter.classify

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Собирает признаки кадра в [Scores]:
 *  - ML Kit Text Recognition (офлайн): сколько текста в кадре — признак документа;
 *  - яркость/линии на уменьшенной копии — признак листа и чертежа;
 *  - обученная модель (если файл модели лежит в assets): работа / семья / другое.
 *
 * Про текст: распознаватель «Latin» кириллицу читает плохо, но нам важно не что написано,
 * а что в кадре много текстовых блоков.
 */
class PhotoAnalyzer(context: Context) {

    private val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val classifier = PhotoClassifier.loadOrNull(context)

    suspend fun analyze(bitmap: Bitmap): Scores {
        val input = InputImage.fromBitmap(bitmap, 0)
        val frameArea = bitmap.width.toFloat() * bitmap.height

        val textResult = text.process(input).await()
        val stats = ImageStatsCalculator.compute(bitmap)

        val textArea = textResult.textBlocks.sumOf { b ->
            (b.boundingBox?.let { it.width().toLong() * it.height() } ?: 0L)
        }.toFloat() / frameArea

        val model = classifier?.classify(bitmap)

        return Scores(
            textBlockCount = textResult.textBlocks.size,
            textAreaRatio = textArea.coerceIn(0f, 1f),
            whiteRatio = stats.whiteRatio,
            edgeDensity = stats.edgeDensity,
            modelLabel = model?.label,
            modelConfidence = model?.confidence ?: 0f,
        )
    }

    fun close() {
        text.close()
        classifier?.close()
    }
}
