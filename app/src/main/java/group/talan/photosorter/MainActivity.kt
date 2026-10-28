package group.talan.photosorter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import group.talan.photosorter.classify.PhotoClassifier
import group.talan.photosorter.core.Journal
import group.talan.photosorter.core.Prefs
import group.talan.photosorter.work.SortScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class UiState(
    val enabled: Boolean = false,
    val mediaGranted: Boolean = false,
    val allFilesGranted: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val modelPresent: Boolean = false,
    val entries: List<Journal.Entry> = emptyList(),
)

/** Один экран: разрешения, переключатель, кнопка «сортировать сейчас», журнал. */
class MainActivity : ComponentActivity() {

    private val prefs by lazy { Prefs(this) }
    private val journal by lazy { Journal(this) }
    private var ui by mutableStateOf(UiState())

    private val requestMedia =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Screen(
                    s = ui,
                    onToggle = ::setEnabled,
                    onMedia = { requestMedia.launch(requiredPermissions()) },
                    onAllFiles = ::openAllFilesSettings,
                    onBattery = ::openBatterySettings,
                    onSortNow = { SortScheduler.runNow(this) },
                    onRefresh = ::refresh,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val pm = getSystemService(PowerManager::class.java)
        ui = UiState(
            enabled = prefs.enabled,
            mediaGranted = requiredPermissions().all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            },
            allFilesGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager(),
            batteryUnrestricted = pm.isIgnoringBatteryOptimizations(packageName),
            modelPresent = PhotoClassifier.isAvailable(this),
            entries = journal.last(40),
        )
    }

    private fun setEnabled(on: Boolean) {
        if (on) {
            // Первое включение: старый архив галереи не трогаем, сортируем только то, что появится дальше.
            if (prefs.watermarkSec == 0L) prefs.watermarkSec = System.currentTimeMillis() / 1000
            prefs.enabled = true
            SortScheduler.start(this)
        } else {
            prefs.enabled = false
            SortScheduler.stop(this)
        }
        refresh()
    }

    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        Build.VERSION.SDK_INT >= 30 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    }

    private fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun openBatterySettings() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }
}

@Composable
private fun Screen(
    s: UiState,
    onToggle: (Boolean) -> Unit,
    onMedia: () -> Unit,
    onAllFiles: () -> Unit,
    onBattery: () -> Unit,
    onSortNow: () -> Unit,
    onRefresh: () -> Unit,
) {
    val ready = s.mediaGranted && s.allFilesGranted
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("PhotoSorter", style = MaterialTheme.typography.headlineSmall)
        Text("Работа • Документы • Семья • Другое. Новые фото из камеры и скриншоты переносятся автоматически.")
        Text(
            if (s.modelPresent) "Модель распознавания: подключена"
            else "Модель распознавания не подключена: определяются только документы, остальное идёт в «Другое»",
            style = MaterialTheme.typography.bodySmall,
        )

        if (!s.mediaGranted) Button(onMedia) { Text("1. Разрешить доступ к фото") }
        if (!s.allFilesGranted) Button(onAllFiles) { Text("2. Разрешить доступ ко всем файлам") }
        if (!s.batteryUnrestricted) Button(onBattery) { Text("3. Не ограничивать работу в фоне") }
        if (!s.batteryUnrestricted) {
            Text(
                "Samsung: Настройки → Батарея → Ограничения фоновой работы → «Спящие приложения»: убрать PhotoSorter; " +
                    "в сведениях о приложении → Батарея выбрать «Без ограничений».",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = s.enabled, onCheckedChange = onToggle, enabled = ready)
            Text(if (s.enabled) "Автосортировка включена" else "Автосортировка выключена")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onSortNow, enabled = s.enabled) { Text("Сортировать сейчас") }
            Button(onRefresh) { Text("Обновить журнал") }
        }

        HorizontalDivider()
        Text("Журнал", style = MaterialTheme.typography.titleMedium)
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        LazyColumn(Modifier.fillMaxWidth()) {
            items(s.entries) { e ->
                val name = e.from.substringAfterLast('/')
                val line = if (e.category != null) "$name → ${e.category}" else "$name: ${e.reason.substringBefore(';')}"
                Text("${fmt.format(Date(e.timeMs))}  $line", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
