package group.talan.photosorter.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import group.talan.photosorter.classify.PhotoAnalyzer
import group.talan.photosorter.classify.Rules
import group.talan.photosorter.core.Journal
import group.talan.photosorter.core.Prefs
import group.talan.photosorter.media.BitmapLoader
import group.talan.photosorter.media.FileMover
import group.talan.photosorter.media.MediaScanner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/**
 * Один проход сортировки: найти новые снимки → классифицировать → переместить → записать в журнал.
 * Всегда завершается success: упавший воркер в цепочке отменил бы следующий запуск.
 * Если снимок не удалось открыть или проанализировать, он остаётся на месте (запись в журнале).
 */
class SortWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val journal = Journal(applicationContext)
        try {
            if (prefs.enabled) lock.withLock { sortNewPhotos(prefs, journal) }
        } catch (e: Exception) {
            journal.append(
                Journal.Entry(System.currentTimeMillis(), "-", null, null, "ошибка прохода: ${e.message}")
            )
        } finally {
            if (prefs.enabled && inputData.getBoolean(KEY_REARM, false)) {
                SortScheduler.rearm(applicationContext)
            }
        }
        return Result.success()
    }

    private suspend fun sortNewPhotos(prefs: Prefs, journal: Journal) {
        val nowSec = System.currentTimeMillis() / 1000
        val photos = MediaScanner(applicationContext)
            .findNew(prefs.watermarkSec, nowSec - prefs.settleSec, prefs.sourceFolders)
        if (photos.isEmpty()) return

        val analyzer = PhotoAnalyzer(applicationContext)
        val mover = FileMover(applicationContext, prefs)
        var watermark = prefs.watermarkSec
        try {
            for (p in photos) {
                val bitmap = BitmapLoader.load(applicationContext, p.uri)
                if (bitmap == null) {
                    journal.append(Journal.Entry(System.currentTimeMillis(), p.path, null, null, "не удалось открыть"))
                } else {
                    try {
                        val scores = analyzer.analyze(bitmap)
                        val d = Rules.decide(scores)
                        val dest = mover.move(p.path, d.category)
                        journal.append(
                            Journal.Entry(System.currentTimeMillis(), p.path, dest.path, d.category.folderName,
                                "${d.reason}; ${scores.summary()}")
                        )
                    } catch (e: Exception) {
                        journal.append(
                            Journal.Entry(System.currentTimeMillis(), p.path, null, null, "ошибка: ${e.message}")
                        )
                    } finally {
                        bitmap.recycle()
                    }
                }
                watermark = max(watermark, p.dateAddedSec)
                prefs.watermarkSec = watermark
            }
        } finally {
            analyzer.close()
        }
    }

    companion object {
        const val KEY_REARM = "rearm"
        private val lock = Mutex()   // чтобы два запуска не обрабатывали одни и те же фото
    }
}
