package org.paw.app.model

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric only for org.json, which the manifest code uses.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongTimeTest {

    @Test
    fun beatUnitSetsTheSongBeat() {
        // 120 quarters a minute: a quarter is 24000 frames, an eighth 12000.
        val fourFour = Song(name = "", tempoBpm = 120f)
        assertEquals(24000.0, fourFour.framesPerSongBeat, 1e-9)
        assertEquals(96000.0, fourFour.framesPerBar, 1e-9)
        val sevenEight = Song(name = "", tempoBpm = 120f, beatsPerBar = 7, beatUnit = 8)
        assertEquals(12000.0, sevenEight.framesPerSongBeat, 1e-9)
        assertEquals(84000.0, sevenEight.framesPerBar, 1e-9)
        assertEquals(168000L, sevenEight.barStartFrame(2))
        // A pattern tick lasts the same whatever the song's beat unit.
        assertEquals(fourFour.framesOf(480), sevenEight.framesOf(480))
    }

    @Test
    fun barBeatCountsFromOne() {
        val s = Song(name = "", tempoBpm = 120f, beatsPerBar = 7, beatUnit = 8)
        assertEquals(BarBeat(1, 1, 0), s.barBeatAt(0))
        assertEquals(BarBeat(1, 2, 50), s.barBeatAt(18000))
        assertEquals(BarBeat(1, 7, 99), s.barBeatAt(83999))
        assertEquals(BarBeat(2, 1, 0), s.barBeatAt(84000))
        assertEquals(BarBeat(3, 4, 25), s.barBeatAt(168000 + 3 * 12000 + 3000))
        assertEquals(BarBeat(1, 1, 0), s.barBeatAt(-5))
    }

    @Test
    fun snapGoesToTheNearestLine() {
        val s = Song(name = "", tempoBpm = 120f)  // beat 24000, bar 96000
        assertEquals(96000L, s.snapFrame(60000, SnapGrid.Bar))
        assertEquals(0L, s.snapFrame(40000, SnapGrid.Bar))
        assertEquals(48000L, s.snapFrame(47000, SnapGrid.Beat))
        assertEquals(47000L, s.snapFrame(47000, SnapGrid.Off))
        assertEquals(24000L, s.snapDelta(13000, SnapGrid.Beat))
        assertEquals(-24000L, s.snapDelta(-13000, SnapGrid.Beat))
        assertEquals(0L, s.snapDelta(11000, SnapGrid.Beat))
        assertEquals(-96000L, s.snapDelta(-70000, SnapGrid.Bar))
        assertEquals(11000L, s.snapDelta(11000, SnapGrid.Off))
        // In 6/8 a beat is an eighth.
        val e = Song(name = "", tempoBpm = 120f, beatsPerBar = 6, beatUnit = 8)
        assertEquals(12000L, e.snapFrame(13000, SnapGrid.Beat))
        assertEquals(72000L, e.snapFrame(50000, SnapGrid.Bar))
    }

    @Test
    fun meterOwnersAndOriginsSurviveSaving() {
        val song = Song(
            name = "S",
            tracks = listOf(Track(id = 2, name = "Beats 1", kind = TRACK_BEAT, inputDevice = InputDevice.None, inputMode = INPUT_NONE)),
            patterns = listOf(Pattern(id = 1, name = "A", track = 2)),
            beatsPerBar = 7,
            beatUnit = 8,
            takeOrigins = mapOf("audio/a.wav" to 48000L, "audio/b.wav" to 0L),
        )
        val back = songFromJson(song.toJson())
        assertEquals(song, back)
        assertEquals(8, back.beatUnit)
        assertEquals(2, back.patterns[0].track)
        assertEquals(48000L, back.takeOrigins["audio/a.wav"])
    }

    @Test
    fun oldManifestsGetDefaults() {
        val json = Song(name = "Old", tracks = listOf(Track(id = 1, name = "T"))).toJson()
        json.remove("beatUnit")
        json.remove("takeOrigins")
        val back = songFromJson(json)
        assertEquals(4, back.beatUnit)
        assertEquals(emptyMap<String, Long>(), back.takeOrigins)
    }

    @Test
    fun patternsWithoutAnOwnerGetOneOnLoad() {
        fun beat(id: Int, samples: List<Int>, clipsOf: List<Int> = emptyList()) = Track(
            id = id, name = "B$id", kind = TRACK_BEAT, inputDevice = InputDevice.None, inputMode = INPUT_NONE,
            samples = samples.map { Sample(it, "s$it", "audio/k.wav", 0, 100) },
            clips = clipsOf.mapIndexed { i, p -> Clip(id = id * 10 + i, take = "", srcStart = 0, length = 1000, start = 0, pattern = p) },
        )
        val song = Song(
            name = "Old",
            tracks = listOf(Track(id = 1, name = "Gtr"), beat(3, listOf(1, 2), clipsOf = listOf(4)), beat(5, listOf(7))),
            patterns = listOf(
                Pattern(id = 1, name = "kit of 5", hits = listOf(Hit(7, 0))),
                Pattern(id = 2, name = "kit of 3", hits = listOf(Hit(1, 0), Hit(2, 240))),
                // Hits in both kits: ambiguous, so the track with its clip.
                Pattern(id = 4, name = "mixed", hits = listOf(Hit(1, 0), Hit(7, 0))),
                // No hits and no clips: the first beat track.
                Pattern(id = 6, name = "empty"),
            ),
        )
        val json = song.toJson()
        val patterns = json.getJSONArray("patterns")
        for (i in 0 until patterns.length()) patterns.getJSONObject(i).remove("track")
        val owners = songFromJson(json).patterns.associate { it.id to it.track }
        assertEquals(mapOf(1 to 5, 2 to 3, 4 to 3, 6 to 3), owners)

        val noBeat = Song(name = "X", patterns = listOf(Pattern(id = 1, name = "p"))).toJson()
        noBeat.getJSONArray("patterns").getJSONObject(0).remove("track")
        assertEquals(0, songFromJson(noBeat).patterns[0].track)
    }

    @Test
    fun undoKeepsSettingsAndSwitches() {
        val then = Song(
            name = "S",
            tracks = listOf(Track(id = 1, name = "Old name"), Track(id = 2, name = "Gone later")),
            loopStart = 0, loopEnd = 96000, loopEnabled = false,
            clickEnabled = false, countInBars = 0, micBoostDb = 16f,
        )
        val now = Song(
            name = "S",
            tracks = listOf(Track(id = 1, name = "New name", armed = true, monitor = true)),
            loopStart = 0, loopEnd = 96000, loopEnabled = true,
            clickEnabled = true, countInBars = 2, micBoostDb = 20f,
            takeOrigins = mapOf("audio/t.wav" to 5L),
        )
        val back = then.keepingSettingsOf(now)
        assertEquals("Old name", back.tracks[0].name)
        assertEquals(true, back.tracks[0].armed)
        assertEquals(true, back.tracks[0].monitor)
        assertEquals(false, back.tracks[1].armed)
        assertEquals(true, back.loopEnabled)
        assertEquals(true, back.clickEnabled)
        assertEquals(2, back.countInBars)
        assertEquals(20f, back.micBoostDb)
        assertEquals(now.takeOrigins, back.takeOrigins)
        // A loop that stays on needs a region it can play.
        assertEquals(false, then.copy(loopEnd = 0).keepingSettingsOf(now).loopEnabled)
    }
}
