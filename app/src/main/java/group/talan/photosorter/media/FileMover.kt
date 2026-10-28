package group.talan.photosorter.media

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import group.talan.photosorter.core.Category
import group.talan.photosorter.core.Prefs
import java.io.File

/**
 * Перемещает файл в папку категории. Оригинал не теряется ни при каком сбое:
 * сначала rename (мгновенно в пределах одной памяти), иначе копия → проверка размера → удаление оригинала.
 * Требует «доступ ко всем файлам» (Android 11+), см. MainActivity.
 */
class FileMover(private val context: Context, private val prefs: Prefs) {

    fun move(photoPath: String, category: Category): File {
        val src = File(photoPath)
        require(src.exists()) { "Файл не найден: $photoPath" }

        val destDir = File(
            Environment.getExternalStorageDirectory(),
            "${prefs.rootFolder}/${category.folderName}",
        )
        check(destDir.isDirectory || destDir.mkdirs()) { "Не удалось создать папку ${destDir.path}" }
        val dest = uniqueTarget(destDir, src.name)

        if (!src.renameTo(dest)) {
            try {
                src.copyTo(dest)
            } catch (e: Exception) {
                dest.delete()
                throw e
            }
            if (dest.length() == src.length()) {
                dest.setLastModified(src.lastModified())
                src.delete()
            } else {
                dest.delete()
                error("Копия не совпала по размеру, оригинал оставлен")
            }
        }

        // Обновляем галерею: старый путь исчезнет, новый появится
        MediaScannerConnection.scanFile(context, arrayOf(src.path, dest.path), null, null)
        return dest
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (true) {
            f = File(dir, if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext")
            if (!f.exists()) return f
            i++
        }
    }
}
