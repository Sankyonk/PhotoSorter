package group.talan.photosorter.classify

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ImageStats(val whiteRatio: Float, val edgeDensity: Float)

/** Дешёвая оценка «похоже на лист бумаги» и «много тонких линий» на уменьшенной копии кадра. */
object ImageStatsCalculator {
    private const val SIDE = 192
    private const val EDGE_STEP = 40   // порог перепада яркости (0..255)

    fun compute(src: Bitmap): ImageStats {
        val scale = SIDE.toFloat() / max(src.width, src.height)
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()

        val gray = IntArray(w * h)
        var white = 0
        for (i in px.indices) {
            val r = (px[i] shr 16) and 0xFF
            val g = (px[i] shr 8) and 0xFF
            val b = px[i] and 0xFF
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            // «белый»: светлый и почти без цвета
            if (mx >= 200 && (mx - mn) <= 30) white++
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
        }

        var edges = 0
        for (y in 0 until h - 1) {
            for (x in 0 until w - 1) {
                val c = gray[y * w + x]
                val dx = abs(gray[y * w + x + 1] - c)
                val dy = abs(gray[(y + 1) * w + x] - c)
                if (dx + dy > EDGE_STEP) edges++
            }
        }
        val total = w * h
        return ImageStats(white.toFloat() / total, edges.toFloat() / total)
    }
}
