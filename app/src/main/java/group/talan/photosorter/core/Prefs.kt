package group.talan.photosorter.core

import android.content.Context

/** Простые настройки. Пока значения зашиты, позже можно вынести на экран настроек. */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("photosorter", Context.MODE_PRIVATE)

    /** Включена ли автосортировка. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /**
     * Граница «уже обработано»: фото, добавленные в галерею раньше этой секунды, не трогаем.
     * При первом включении ставится «сейчас», чтобы старый архив не переехал неожиданно.
     */
    var watermarkSec: Long
        get() = sp.getLong("watermark", 0L)
        set(v) = sp.edit().putLong("watermark", v).apply()

    /** Корневая папка сортировки (относительно общей памяти телефона). */
    val rootFolder: String = "Pictures/PhotoSorter"

    /**
     * Откуда забираем фото: камера и скриншоты (на Samsung скриншоты лежат в DCIM/Screenshots,
     * на других телефонах — в Pictures/Screenshots). Мессенджеры не трогаем: у них свои папки.
     */
    val sourceFolders: Set<String> = setOf("DCIM/Camera/", "DCIM/Screenshots/", "Pictures/Screenshots/")

    /** Ждём, пока камера дозапишет файл и MediaStore «устаканится». */
    val settleSec: Long = 30
}
