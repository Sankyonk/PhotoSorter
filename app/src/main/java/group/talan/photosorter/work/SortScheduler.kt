package group.talan.photosorter.work

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Как добиваемся «сортировка в течение 5 минут после появления фото»:
 *
 *  1. Основной механизм — запуск по изменению галереи (content URI trigger). Система сама будит
 *     приложение: через ~1 мин после новых снимков, не позже ~2 мин, плюс 30 с «отстоя» файла
 *     в [SortWorker]. Итого обычно 1,5–3 минуты. Служба в фоне не висит.
 *  2. Страховка — периодический проход раз в 15 минут (минимум WorkManager): подберёт то,
 *     что пропустил триггер.
 *
 * Системный режим экономии (Doze) может задерживать оба механизма, когда телефон долго лежит
 * с погашенным экраном. Если окажется, что 5 минут держатся не всегда, — следующий шаг —
 * режим со службой переднего плана (постоянное уведомление) и ContentObserver.
 */
object SortScheduler {
    private const val NAME_TRIGGER = "photosorter-trigger"
    private const val NAME_PERIODIC = "photosorter-periodic"
    private const val NAME_NOW = "photosorter-now"

    /** Включить автосортировку. Повторный вызов безопасен. */
    fun start(ctx: Context) {
        arm(ctx, ExistingWorkPolicy.KEEP)
        val periodic = PeriodicWorkRequestBuilder<SortWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(NAME_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    /** Вызывается самим воркером в конце: триггер одноразовый, его нужно взводить заново. */
    fun rearm(ctx: Context) = arm(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE)

    fun stop(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(NAME_TRIGGER)
        wm.cancelUniqueWork(NAME_PERIODIC)
    }

    /** Кнопка «Сортировать сейчас». Свежие (моложе 30 с) файлы всё равно ждут. */
    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            NAME_NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SortWorker>().build()
        )
    }

    private fun arm(ctx: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(60, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(120, TimeUnit.SECONDS)
            .build()
        val request = OneTimeWorkRequestBuilder<SortWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putBoolean(SortWorker.KEY_REARM, true).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NAME_TRIGGER, policy, request)
    }
}
