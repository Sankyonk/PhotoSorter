package group.talan.photosorter.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Журнал действий: что и куда переложили (или почему оставили). Нужен для проверки и отката. */
class Journal(context: Context) {

    data class Entry(
        val timeMs: Long,
        val from: String,
        val to: String?,        // null — файл не перемещён
        val category: String?,
        val reason: String,
    )

    private val file = File(context.applicationContext.filesDir, "journal.jsonl")

    @Synchronized
    fun append(e: Entry) {
        trimIfHuge()
        val o = JSONObject()
            .put("t", e.timeMs)
            .put("from", e.from)
            .put("to", e.to ?: JSONObject.NULL)
            .put("cat", e.category ?: JSONObject.NULL)
            .put("why", e.reason)
        file.appendText(o.toString() + "\n")
    }

    /** Последние [n] записей, новые сверху. */
    @Synchronized
    fun last(n: Int): List<Entry> {
        if (!file.exists()) return emptyList()
        return file.readLines().takeLast(n)
            .mapNotNull { line -> runCatching { parse(line) }.getOrNull() }
            .reversed()
    }

    private fun parse(line: String): Entry {
        val o = JSONObject(line)
        return Entry(
            timeMs = o.getLong("t"),
            from = o.getString("from"),
            to = if (o.isNull("to")) null else o.getString("to"),
            category = if (o.isNull("cat")) null else o.getString("cat"),
            reason = o.getString("why"),
        )
    }

    private fun trimIfHuge() {
        if (file.exists() && file.length() > 512_000) {
            val tail = file.readLines().takeLast(1000)
            file.writeText(tail.joinToString("\n") + "\n")
        }
    }
}
