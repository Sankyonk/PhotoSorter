package group.talan.photosorter.classify

import group.talan.photosorter.core.Category

/** Измеренные признаки фото. Ни одной Android-зависимости — правила тестируются обычным JUnit. */
data class Scores(
    val textBlockCount: Int = 0,        // число текстовых блоков (ML Kit)
    val textAreaRatio: Float = 0f,      // доля кадра под текстом
    val whiteRatio: Float = 0f,         // доля «белых» пикселей (светлых, без цвета) — признак листа
    val edgeDensity: Float = 0f,        // доля пикселей с резким перепадом — линии чертежа
    val modelLabel: String? = null,     // ответ обученной модели: work / family / other; null — модели нет
    val modelConfidence: Float = 0f,    // уверенность модели 0..1
) {
    fun summary(): String =
        "текст=%.2f/%d белое=%.2f линии=%.3f модель=%s %.2f".format(
            textAreaRatio, textBlockCount, whiteRatio, edgeDensity,
            modelLabel ?: "нет", modelConfidence,
        )
}

data class Decision(val category: Category, val confidence: Float, val reason: String)

/** Пороги вынесены отдельно: подбираются по журналу на реальных фото. Значения — стартовые. */
data class Thresholds(
    val paperMin: Float = 0.5f,
    val textAreaMin: Float = 0.12f,
    val textBlocksMin: Int = 8,
    val drawingEdgeMin: Float = 0.04f,
    // «Работа» требует большей уверенности: рабочая папка может уходить коллегам,
    // и семейному фото там делать нечего. Сомнительное лучше отправить в «Другое».
    val workMin: Float = 0.85f,
    val familyMin: Float = 0.70f,
)

object Rules {
    /**
     * Порядок: документ → ответ модели → «Другое».
     *  - Фото паспорта или чертежа остаётся документом, даже если на нём лицо.
     *  - Модель различает «работа» и «семья» (каски, конструкции, грунт против людей, детей, питомцев).
     *  - Нет модели или она не уверена — «Другое». Файл всегда уходит в одну из четырёх папок.
     */
    fun decide(s: Scores, t: Thresholds = Thresholds()): Decision {
        val paper = s.whiteRatio >= t.paperMin
        if (paper && (s.textAreaRatio >= t.textAreaMin ||
                    s.textBlockCount >= t.textBlocksMin ||
                    s.edgeDensity >= t.drawingEdgeMin)
        ) {
            return Decision(Category.DOCUMENTS, s.whiteRatio.coerceIn(0f, 1f), "светлый лист + текст/линии")
        }

        val label = s.modelLabel
            ?: return Decision(Category.OTHER, 0f, "модель не подключена")

        return when (label) {
            "work" ->
                if (s.modelConfidence >= t.workMin)
                    Decision(Category.WORK, s.modelConfidence, "модель: работа")
                else
                    Decision(Category.OTHER, s.modelConfidence, "модель не уверена (работа)")
            "family" ->
                if (s.modelConfidence >= t.familyMin)
                    Decision(Category.FAMILY, s.modelConfidence, "модель: семья")
                else
                    Decision(Category.OTHER, s.modelConfidence, "модель не уверена (семья)")
            else -> Decision(Category.OTHER, s.modelConfidence, "модель: другое")
        }
    }
}
