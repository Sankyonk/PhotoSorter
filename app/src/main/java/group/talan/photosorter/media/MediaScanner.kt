package group.talan.photosorter.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import kotlin.math.max

data class Photo(val id: Long, val uri: Uri, val path: String, val dateAddedSec: Long)

/** Поиск новых снимков в галерее (MediaStore). */
class MediaScanner(private val context: Context) {

    /**
     * Фото, добавленные в интервале (sinceSec, untilSec] из указанных папок, старые → новые.
     * untilSec = «сейчас минус settle»: свежие файлы ещё могут дописываться.
     */
    fun findNew(sinceSec: Long, untilSec: Long, sourceFolders: Set<String>): List<Photo> {
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val folderSel = sourceFolders.joinToString(" OR ") {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        }
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ? AND " +
                "${MediaStore.Images.Media.DATE_ADDED} <= ? AND ($folderSel)"
        val args = arrayOf(sinceSec.toString(), untilSec.toString()) +
                sourceFolders.map { "$it%" }

        val result = mutableListOf<Photo>()
        @Suppress("DEPRECATION")
        context.contentResolver.query(
            images,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_ADDED,
            ),
            selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result += Photo(id, ContentUris.withAppendedId(images, id), c.getString(dataCol), c.getLong(dateCol))
            }
        }
        return result
    }
}

/** Загрузка уменьшенной копии для анализа (учитывает поворот из EXIF). */
object BitmapLoader {
    fun load(context: Context, uri: Uri, maxSide: Int = 1024): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            decoder.setTargetSampleSize(max(1, longest / maxSide))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        null
    }
}
