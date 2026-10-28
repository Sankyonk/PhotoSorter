package group.talan.photosorter

import android.app.Application
import group.talan.photosorter.core.Prefs
import group.talan.photosorter.work.SortScheduler

class PhotoSorterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // После обновления приложения или очистки очереди — убедиться, что наблюдение взведено.
        // WorkManager сам переживает перезагрузку телефона, отдельный BootReceiver не нужен.
        if (Prefs(this).enabled) SortScheduler.start(this)
    }
}
