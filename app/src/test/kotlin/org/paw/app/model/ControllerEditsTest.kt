package org.paw.app.model

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.paw.app.SongController
import org.paw.app.snap.ShadowEngine
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Drives the controller against the shadow engine, for the edit rules that
// live there rather than in the model: what undo covers, arming across
// devices, and the pattern and take operations.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowEngine::class])
class ControllerEditsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var c: SongController
    private val duo = InputDevice.Usb("M-Track Duo")

    private fun song() = Song(
        name = "Edits",
        tracks = listOf(
            Track(id = 1, name = "Guitar", inputDevice = duo, inputMode = INPUT_CH1),
            Track(id = 2, name = "Vox", inputDevice = duo, inputMode = INPUT_CH2),
            Track(id = 3, name = "Room", inputDevice = InputDevice.Mic, inputMode = INPUT_CH1),
            Track(
                id = 4, name = "Beats 1", kind = TRACK_BEAT, inputDevice = InputDevice.None, inputMode = INPUT_NONE,
                samples = listOf(Sample(1, "Kick", "audio/k.wav", 0, 4800)),
                clips = listOf(Clip(id = 1, take = "", srcStart = 0, length = 96000, start = 0, pattern = 1)),
            ),
            Track(id = 5, name = "Beats 2", kind = TRACK_BEAT, inputDevice = InputDevice.None, inputMode = INPUT_NONE),
        ),
        patterns = listOf(Pattern(id = 1, name = "Groove", track = 4, hits = listOf(Hit(1, 0)))),
        nextTrackId = 6, nextClipId = 2, nextPatternId = 2, nextSampleId = 2,
        tempoBpm = 120f,
    )

    @Before
    fun setUp() {
        ShadowEngine.reset()
        val store = ProjectStore(context)
        val dir = store.dirFor("Edits")
        dir.deleteRecursively()
        store.save(dir, song())
        c = SongController(context, store, dir, song())
        c.inputOptions = listOf(InputOption(duo, "M-Track Duo"), InputOption(InputDevice.Mic, "Phone mic"))
        c.start()
        c.openStreams(7, 8, duo, "usb:M-Track Duo")
    }

    @After
    fun tearDown() = c.stop()

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

    @Test
    fun undoLeavesArmMonitorAndLoopSwitchAlone() {
        c.setLoopRegion(0, 96000)
        c.renameTrack(1, "Lead")
        c.setArmed(1, true)
        c.setMonitor(1, true)
        c.setLoopEnabled(false)
        c.undo()
        assertEquals("Guitar", c.song.track(1)!!.name)
        assertTrue(c.song.track(1)!!.armed)
        assertTrue(c.song.track(1)!!.monitor)
        assertFalse(c.song.loopEnabled)
        c.undo()
        assertEquals(0L, c.song.loopEnd)
        assertFalse(c.canUndo)
    }

    @Test
    fun loopRegionRulesHold() {
        c.setLoopRegion(0, 1000)
        assertNotNull(c.message)
        assertFalse(c.canUndo)
        c.setLoopEnabled(true)
        assertFalse(c.song.loopEnabled)
        c.setLoopRegion(48000, 144000)
        assertTrue(c.song.loopEnabled)
        c.clearLoop()
        assertEquals(0L, c.song.loopEnd)
        assertFalse(c.song.loopEnabled)
        c.undo()
        assertEquals(144000L, c.song.loopEnd)
    }

    @Test
    fun aRecordingIsItsOwnUndoStep() {
        c.renameTrack(1, "Lead")
        ShadowEngine.takeFrames["take_x_t1.wav"] = 96000
        ShadowEngine.recordResult = longArrayOf(48000, 0, 1, 1, 1, 96000) to arrayOf("/any/audio/take_x_t1.wav")
        idle()
        val placed = c.song.track(1)!!.clips.single()
        assertEquals("audio/take_x_t1.wav", placed.take)
        assertEquals(placed.start, c.originalStartOf("audio/take_x_t1.wav"))
        c.undo()
        assertTrue(c.song.track(1)!!.clips.isEmpty())
        assertEquals("Lead", c.song.track(1)!!.name)
        // The take still knows where it went.
        assertEquals(placed.start, c.originalStartOf("audio/take_x_t1.wav"))
        c.undo()
        assertEquals("Guitar", c.song.track(1)!!.name)
    }

    @Test
    fun armingAnotherDeviceDisarmsTheOthers() {
        c.setArmed(1, true)
        c.setArmed(2, true)
        assertTrue(c.song.track(1)!!.armed && c.song.track(2)!!.armed)
        assertNull(c.message)
        c.setArmed(3, true)
        assertTrue(c.song.track(3)!!.armed)
        assertFalse(c.song.track(1)!!.armed || c.song.track(2)!!.armed)
        assertEquals("Guitar, Vox disarmed: Room records from the phone mic", c.message)
        assertFalse(c.canUndo)
    }

    @Test
    fun meterDrivesTheClickAndTheBeatGrid() {
        c.setMeter(7, 8)
        assertEquals(7, c.song.beatsPerBar)
        assertEquals(8, c.song.beatUnit)
        assertEquals(240f to 7, ShadowEngine.metronome)
        assertEquals(12000L, c.framesPerBeat)
        c.setTempo(90f)
        assertEquals(180f to 7, ShadowEngine.metronome)
        c.undo()
        assertEquals(120f, c.song.tempoBpm)
        c.undo()
        assertEquals(4, c.song.beatUnit)
        c.setCountIn(4)
        assertEquals(2, c.song.countInBars)
    }

    @Test
    fun patternsBelongToTheirTrack() {
        val id = c.newPattern(5)
        assertTrue(c.song.track(5)!!.clips.isEmpty())
        assertEquals(listOf(id), c.patternsFor(5).map { it.id })
        c.placePatternAt(4, id, 1)
        assertTrue(c.song.track(4)!!.clips.none { it.pattern == id })
        c.placePatternAt(5, id, 2)
        val clip = c.song.track(5)!!.clips.single()
        assertEquals(192000L, clip.start)
        assertEquals(5 to clip.id, c.selectedClip)
        assertEquals(5, c.selectedTrack)
        c.renamePattern(id, "  Fill  ")
        assertEquals("Fill", c.song.pattern(id)!!.name)
    }

    @Test
    fun variationsRepeatsAndDeletes() {
        val variation = c.makeVariation(4, 1)
        assertNotEquals(1, variation)
        assertEquals("Groove 2", c.song.pattern(variation)!!.name)
        assertEquals(4, c.song.pattern(variation)!!.track)
        assertEquals(variation, c.song.track(4)!!.clips.single().pattern)
        val again = c.makeVariation(4, 1)
        assertEquals("Groove 3", c.song.pattern(again)!!.name)

        // The original is free now and can go; a pattern in use cannot.
        assertTrue(c.deletePattern(1))
        val inUse = c.song.track(4)!!.clips.single().pattern!!
        assertFalse(c.deletePattern(inUse))
        assertNotNull(c.message)

        c.setPatternRepeats(4, 1, 3)
        val clip = c.song.track(4)!!.clips.single()
        assertEquals(3 * 96000L, clip.length)
        assertEquals(3, c.patternRepeats(clip))
        assertEquals(1, c.patternRepeats(clip.copy(length = 1000)))
        assertEquals(2, c.patternRepeats(clip.copy(length = 97000)))
        assertEquals(1, c.patternRepeats(clip.copy(length = 96001)))
    }

    @Test
    fun patternLayoutKeepsTheStepLength() {
        c.setPatternLayout(1, 7, 8, 2, 4, listOf(3, 2, 2))
        val p = c.song.pattern(1)!!
        assertEquals(listOf(7, 8, 2, 2), listOf(p.beats, p.unit, p.bars, p.stepsPerBeat))
        assertEquals(listOf(3, 2, 2), p.grouping)
        c.setPatternLayout(1, 5, 8, 1, 2, listOf(4, 4))
        assertEquals(defaultGrouping(5), c.song.pattern(1)!!.grouping)
    }

    @Test
    fun clipsStackInListOrder() {
        ShadowEngine.takeFrames["a.wav"] = 96000
        c.addTakeToTrack(1, "audio/a.wav", 0)
        c.addTakeToTrack(1, "audio/a.wav", 48000)
        c.addTakeToTrack(1, "audio/a.wav", 200000)
        val (first, second, far) = c.song.track(1)!!.clips
        assertEquals(listOf(first), c.clipsUnder(1, second.id))
        assertTrue(c.clipsUnder(1, far.id).isEmpty())
        assertEquals(0L, c.originalStartOf("audio/a.wav"))
        c.bringClipToTop(1, first.id)
        assertEquals(listOf(second, far, first), c.song.track(1)!!.clips)
        assertEquals(listOf(second), c.clipsUnder(1, first.id))
    }

    @Test
    fun samplesGoWhereTheyAreSent() {
        ShadowEngine.takeFrames["a.wav"] = 96000
        c.addTakeToTrack(1, "audio/a.wav", 0)
        val clipId = c.song.track(1)!!.clips.single().id
        val tracksBefore = c.song.tracks.size
        val onNew = c.sampleFromClip(1, clipId, null, "Snare")!!
        assertEquals(tracksBefore + 1, c.song.tracks.size)
        val newTrack = c.song.tracks.last()
        assertEquals(onNew, newTrack.samples.single().id)
        c.undo()
        assertEquals(tracksBefore, c.song.tracks.size)
        val onFive = c.sampleFromClip(1, clipId, 5, "Hat")!!
        c.renameSample(5, onFive, "Closed hat")
        assertEquals("Closed hat", c.song.track(5)!!.samples.single().name)
        assertNull(c.sampleFromClip(1, clipId, 2, "x"))
        assertEquals(4, c.firstBeatTrack())
    }

    @Test
    fun ideasGetUniqueNamesAndOneTrack() {
        val store = ProjectStore(context)
        val a = store.createIdea(duo, armed = true)
        val b = store.createIdea(null, armed = false)
        assertTrue(a.name.startsWith("Idea "))
        assertNotEquals(a.name, b.name)
        assertEquals(a.name, store.load(store.dirFor(a.name))!!.name)
        assertEquals(listOf("Track 1"), a.tracks.map { it.name })
        assertTrue(a.tracks[0].armed)
        assertEquals(duo, a.tracks[0].inputDevice)
        assertFalse(b.tracks[0].armed)
        assertEquals(InputDevice.Mic, b.tracks[0].inputDevice)
        store.dirFor(a.name).deleteRecursively()
        store.dirFor(b.name).deleteRecursively()
    }

    /** Waits for the sampler's own thread to push a bank. */
    private fun awaitBank(): Int {
        val until = System.currentTimeMillis() + 2000
        while (ShadowEngine.bankSize < 0 && System.currentTimeMillis() < until) {
            idle()
            Thread.sleep(10)
        }
        return ShadowEngine.bankSize
    }

    @Test
    fun openingASongLoadsItsKit() {
        assertEquals(1, awaitBank())
    }

    @Test
    fun reopeningAfterStopLoadsTheKitAgain() {
        awaitBank()
        c.stop()
        ShadowEngine.bankSize = -1
        c.start()
        assertEquals(1, awaitBank())
    }

    @Test
    fun placingSkipsPastClipsAlreadyThere() {
        // At 120 bpm in 4/4 a bar is 96000 frames; Groove's clip fills bar 1.
        assertEquals(1, c.freeBarFor(4, 1))
        c.placePattern(4, 1)
        assertEquals(2, c.song.track(4)!!.clips.size)
        assertEquals(96000L, c.song.track(4)!!.clips.last().start)
        assertEquals(2, c.freeBarFor(4, 1))
    }

    @Test
    fun stackedPatternClipsCollapseToOne() {
        val beat = song().track(4)!!
        val stacked = beat.copy(clips = beat.clips + (2..10).map { beat.clips[0].copy(id = it) })
        val cleaned = song().withTrack(stacked).withoutStackedPatterns()
        assertEquals(1, cleaned.track(4)!!.clips.size)
        // A clip of the same pattern somewhere else stays.
        val moved = beat.copy(clips = beat.clips + beat.clips[0].copy(id = 2, start = 96000))
        assertEquals(2, song().withTrack(moved).withoutStackedPatterns().track(4)!!.clips.size)
    }
}
