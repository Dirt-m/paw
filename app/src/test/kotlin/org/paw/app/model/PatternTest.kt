package org.paw.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric only for org.json, which the manifest code uses.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PatternTest {

    @Test
    fun meterArithmetic() {
        // 7/8 in 16ths: an eighth is 480 ticks, a step 240, a bar 14 steps.
        val p = Pattern(id = 1, name = "", beats = 7, unit = 8, grouping = listOf(2, 2, 3), stepsPerBeat = 2)
        assertEquals(480, p.ticksPerBeat)
        assertEquals(240, p.ticksPerStep)
        assertEquals(3360, p.ticksPerBar)
        assertEquals(14, p.steps)
        // 4/4 triplets over two bars.
        val q = Pattern(id = 2, name = "", bars = 2, stepsPerBeat = 3)
        assertEquals(320, q.ticksPerStep)
        assertEquals(24, q.steps)
    }

    @Test
    fun groupingsCoverEveryBeatOnce() {
        for (beats in 1..16) {
            val all = groupingsFor(beats)
            assertEquals(defaultGrouping(beats), all.first())
            for (g in all) assertEquals("$beats: $g", beats, g.sum())
            assertEquals(all.size, all.toSet().size)
        }
        assertEquals(listOf(2, 2, 3), defaultGrouping(7))
        assertEquals(listOf(3, 3), defaultGrouping(6))
        assertTrue(listOf(3, 2, 2) in groupingsFor(7))
    }

    @Test
    fun tempoSetsTheFrameLengthOfATick() {
        // 120 bpm at 48 kHz: a quarter is 24000 frames.
        val song = Song(name = "", tempoBpm = 120f)
        assertEquals(24000L, song.framesOf(TICKS_PER_QUARTER))
        assertEquals(84000L, song.framesOf(Pattern(id = 1, name = "", beats = 7, unit = 8).lengthTicks))
    }

    @Test
    fun beatTracksPatternsAndPatternClipsSurviveSaving() {
        val pattern = Pattern(
            id = 3, name = "Groove", beats = 5, unit = 8, grouping = listOf(3, 2), bars = 2,
            stepsPerBeat = 3, hits = listOf(Hit(1, 0), Hit(2, 480, 0.5f)),
        )
        val beat = Track(
            id = 2, name = "Beats 1", kind = TRACK_BEAT, inputDevice = InputDevice.None, inputMode = INPUT_NONE,
            samples = listOf(Sample(1, "Kick", "audio/a.wav", 100, 4800, gain = 0.5f)),
            clips = listOf(Clip(id = 9, take = "", srcStart = 300, length = 96000, start = 48000, pattern = 3)),
        )
        val audio = Track(id = 1, name = "Gtr", clips = listOf(Clip(1, "audio/b.wav", 0, 100, 0)))
        val song = Song(
            name = "S", tracks = listOf(audio, beat), patterns = listOf(pattern),
            nextPatternId = 4, nextSampleId = 2,
        )
        val back = songFromJson(song.toJson())
        assertEquals(song, back)
        assertTrue(back.tracks[1].clips[0].isPattern)
        assertTrue(!back.tracks[0].clips[0].isPattern)
    }

    @Test
    fun manifestsWithoutSamplerFieldsStillLoad() {
        val old = Song(name = "Old", tracks = listOf(Track(id = 1, name = "T"))).toJson()
        old.remove("patterns")
        old.remove("nextPatternId")
        old.remove("nextSampleId")
        val back = songFromJson(old)
        assertEquals(emptyList<Pattern>(), back.patterns)
        assertEquals(TRACK_AUDIO, back.tracks[0].kind)
    }
}
