package group.talan.photosorter.classify

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Обученная на ваших фото модель (MobileNetV2): работа / семья / (другое).
 *
 * Где лежит модель (файлы photo_model.tflite и photo_labels.txt, их делает training/train_model.py):
 *  1. в памяти телефона, папка Pictures/PhotoSorter/_model — основной вариант: модель обучена
 *     на личных фото, поэтому в APK и в репозиторий не попадает, её можно заменить без пересборки;
 *  2. запасной вариант — внутри приложения (app/src/main/assets).
 * Пока файлов нет, [loadOrNull] возвращает null, и приложение определяет только документы
 * (остальное уходит в «Другое»). Модель подхватывается при следующем проходе сортировки.
 *
 * Вход модели: float32 [1,224,224,3], значения 0..255 (нормализация внутри модели).
 * Выход: вероятности по меткам из photo_labels.txt, в том же порядке.
 */
class PhotoClassifier private constructor(
    private val interpreter: Interpreter,
    private val labels: List<String>,
) {
    data class Result(val label: String, val confidence: Float)

    fun classify(bitmap: Bitmap): Result {
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== bitmap) scaled.recycle()

        val input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
        for (p in px) {
            input.putFloat(((p shr 16) and 0xFF).toFloat())
            input.putFloat(((p shr 8) and 0xFF).toFloat())
            input.putFloat((p and 0xFF).toFloat())
        }
        input.rewind()

        val out = Array(1) { FloatArray(labels.size) }
        interpreter.run(input, out)
        var best = 0
        for (i in out[0].indices) if (out[0][i] > out[0][best]) best = i
        return Result(labels[best], out[0][best])
    }

    fun close() = interpreter.close()

    companion object {
        const val SIZE = 224
        const val MODEL_FILE = "photo_model.tflite"
        const val LABELS_FILE = "photo_labels.txt"
        const val PHONE_DIR = "Pictures/PhotoSorter/_model"

        private fun phoneFile(name: String) = File(Environment.getExternalStorageDirectory(), "$PHONE_DIR/$name")

        /** Оба файла модели лежат в папке на телефоне (тогда берём их, а не встроенные). */
        private fun useModelFromPhone(): Boolean = phoneFile(MODEL_FILE).isFile && phoneFile(LABELS_FILE).isFile

        private fun open(context: Context, name: String): InputStream =
            if (useModelFromPhone()) phoneFile(name).inputStream() else context.assets.open(name)

        fun isAvailable(context: Context): Boolean =
            runCatching { open(context, MODEL_FILE).close(); open(context, LABELS_FILE).close(); true }
                .getOrDefault(false)

        /** null — модели нет или она не подходит к списку меток. */
        fun loadOrNull(context: Context): PhotoClassifier? = try {
            val bytes = open(context, MODEL_FILE).use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            model.put(bytes)
            model.rewind()
            val labels = open(context, LABELS_FILE).bufferedReader().use { it.readLines() }
                .map { it.trim() }.filter { it.isNotEmpty() }
            val interpreter = Interpreter(model)
            if (interpreter.getOutputTensor(0).shape().last() != labels.size) {
                interpreter.close()
                null
            } else {
                PhotoClassifier(interpreter, labels)
            }
        } catch (e: Exception) {
            null
        }
    }
}
