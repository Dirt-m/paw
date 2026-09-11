package org.paw.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.paw.app.PeakData

/**
 * A clip that starts partway into its take must draw take frame `srcStart + n`
 * at column n, and nothing beyond. The regression: the per-column end frame
 * was clamped before `srcStart` was added, so every column reached `srcStart`
 * frames ahead into the take and later audio smeared leftward. Invisible for
 * latency-sized offsets, glaring after a left trim.
 */
class WaveLinesTest {
    private val bucket = 256

    /** Silent take with a single full-scale bucket at [burstBucket]. */
    private fun peaks(buckets: Int, burstBucket: Int): PeakData {
        val data = ByteArray(buckets * 2)
        data[2 * burstBucket] = -127
        data[2 * burstBucket + 1] = 127
        return PeakData(data, bucket, buckets.toLong() * bucket)
    }

    /** Column indexes whose line has any height. */
    private fun litColumns(lines: FloatArray, cols: Int): List<Int> =
        (0 until cols).filter { c -> lines[4 * c + 1] != lines[4 * c + 3] }

    @Test
    fun trimmedClipDrawsTheBurstWhereItSits() {
        val perPx = 512L                       // frames per column, two whole buckets
        val srcStart = 40 * perPx              // a 40-column left trim
        val burstFrame = srcStart + 100 * perPx
        val p = peaks(buckets = 1000, burstBucket = (burstFrame / bucket).toInt())
        val cols = 200
        val lines = buildWaveLines(p, srcStart, cols * perPx, cols.toFloat(), 100f)
        assertEquals(listOf(100), litColumns(lines, cols))
    }

    @Test
    fun untrimmedClipIsUnchanged() {
        val perPx = 512L
        val p = peaks(buckets = 1000, burstBucket = (30 * perPx / bucket).toInt())
        val cols = 100
        val lines = buildWaveLines(p, 0, cols * perPx, cols.toFloat(), 100f)
        assertEquals(listOf(30), litColumns(lines, cols))
    }

    @Test
    fun columnNeverReachesPastItsOwnFrames() {
        val p = peaks(buckets = 100, burstBucket = 50)
        val srcStart = 30L * bucket
        val out = FloatArray(4)
        // Column covering take frames [srcStart, srcStart + bucket): bucket 30, silent.
        appendColumn(p, out, 0, 0f, srcStart, srcStart + bucket, 50f, 40f)
        assertEquals(out[1], out[3], 0f)
    }
}
