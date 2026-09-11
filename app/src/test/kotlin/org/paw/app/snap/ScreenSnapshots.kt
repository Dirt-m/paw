package org.paw.app.snap

import android.app.Application
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Insets
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf
import org.paw.app.model.ProjectStore
import org.paw.app.ui.OptionsScreen
import org.paw.app.ui.ProjectListScreen
import org.paw.app.ui.SongScreen
import org.paw.app.ui.RecordingsScreen
import org.paw.app.ui.TrackDetailScreen
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Renders every screen with the demo song and writes PNGs to app/snapshots/.
// Run via tools/snapshots.sh. Portrait is the reference phone's panel: a
// Pixel 9a is 1080x2424 px at 420 dpi = 411x923 dp; "+land" swaps it.

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], shadows = [ShadowEngine::class], qualifiers = "w411dp-h923dp-420dpi")
class ScreenSnapshots {

    // The compose rule hosts content in ui-test-manifest's ComponentActivity,
    // which is not in the app manifest; register it with the shadow package
    // manager before the rule launches it.
    private val registerHostActivity = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }

    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val compose get() = composeRule

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(registerHostActivity).around(composeRule)

    /**
     * Robolectric windows have no system bars, so the screens' safeDrawing
     * padding collapses to zero; dispatch the reference phone's insets by hand.
     * Values approximate a Pixel 9a with gesture nav (status bar over the punch
     * hole, gesture pill strip, cutout on the left in landscape).
     */
    private fun applyPhoneInsets() {
        compose.waitForIdle()
        val activity = composeRule.activity
        val density = activity.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val land =
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val insets = WindowInsets.Builder()
            .setInsets(
                WindowInsets.Type.statusBars(),
                Insets.of(0, px(if (land) 24 else 32), 0, 0),
            )
            .setInsets(
                WindowInsets.Type.displayCutout(),
                if (land) Insets.of(px(32), 0, 0, 0) else Insets.of(0, px(32), 0, 0),
            )
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, px(24)))
            .build()
        composeRule.runOnUiThread {
            activity.findViewById<ViewGroup>(android.R.id.content)
                .dispatchApplyWindowInsets(insets)
        }
        compose.waitForIdle()
    }

    private fun content(body: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { body() }
        }
    }

    /** Lets Dispatchers.IO work (peaks, take lists) land back on the UI. */
    private fun settle() {
        repeat(20) {
            compose.waitForIdle()
            Thread.sleep(15)
        }
        compose.waitForIdle()
    }

    private fun snap(name: String) {
        applyPhoneInsets()
        settle()
        compose.onRoot().captureRoboImage("snapshots/$name.png")
    }

    private fun controller() = demoController(ApplicationProvider.getApplicationContext())

    // ---- project list ----

    private fun projectList() {
        val entries = listOf(
            ProjectStore.Entry("Demo Song", File("/x/Demo Song"), 1786043160000L),
            ProjectStore.Entry("Rehearsal 3 Aug", File("/x/Rehearsal 3 Aug"), 1785743160000L),
            ProjectStore.Entry("Riff ideas", File("/x/Riff ideas"), 1785443160000L),
        )
        content {
            ProjectListScreen(
                entries = entries,
                onOpen = {}, onCreate = {}, onRename = { _, _ -> true },
                onDelete = { _, _ -> },
            )
        }
    }

    @Test
    fun projects_portrait() {
        projectList()
        snap("projects-portrait")
    }

    @Test
    @Config(qualifiers = "+land")
    fun projects_landscape() {
        projectList()
        snap("projects-landscape")
    }

    // ---- song / timeline ----

    private fun song() {
        val c = controller()
        content {
            SongScreen(
                controller = c,
                onBack = {}, onOpenTrack = {}, onOpenTakes = {}, onOpenOptions = {},
            )
        }
    }

    @Test
    fun song_portrait() {
        song()
        snap("song-portrait")
    }

    @Test
    @Config(qualifiers = "+land")
    fun song_landscape() {
        song()
        snap("song-landscape")
    }

    // ---- track detail ----

    private fun trackDetail(trackId: Int) {
        val c = controller()
        content {
            TrackDetailScreen(controller = c, trackId = trackId, onBack = {})
        }
    }

    @Test
    fun track_portrait() {
        trackDetail(1)
        snap("track-portrait")
    }

    @Test
    @Config(qualifiers = "+land")
    fun track_landscape() {
        trackDetail(1)
        snap("track-landscape")
    }

    @Test
    fun master_portrait() {
        trackDetail(org.paw.app.model.MASTER_TRACK_ID)
        snap("master-portrait")
    }

    @Test
    @Config(qualifiers = "+land")
    fun master_landscape() {
        trackDetail(org.paw.app.model.MASTER_TRACK_ID)
        snap("master-landscape")
    }

    // ---- takes ----

    @Test
    fun recordings_portrait() {
        val c = controller()
        content { RecordingsScreen(controller = c, onBack = {}) }
        snap("recordings-portrait")
    }

    @Test
    @Config(qualifiers = "+land")
    fun recordings_landscape() {
        val c = controller()
        content { RecordingsScreen(controller = c, onBack = {}) }
        snap("recordings-landscape")
    }

    // ---- options ----

    @Test
    fun options_portrait() {
        val c = controller()
        content {
            OptionsScreen(
                controller = c,
                keepAwakeMode = org.paw.app.KeepAwakeMode.FiveMinutes,
                onKeepAwakeMode = {},
                latencyMs = 10.4f,
                effectsPath = "/storage/emulated/0/Android/data/org.paw.app/files/effects",
                onBack = {},
            )
        }
        snap("options-portrait")
    }

    // ---- edge cases ----

    private fun songWith(c: org.paw.app.SongController) {
        content {
            SongScreen(
                controller = c,
                onBack = {}, onOpenTrack = {}, onOpenTakes = {}, onOpenOptions = {},
            )
        }
    }

    /** A clip selected on the timeline (Guitar's first clip, in view). */
    @Test
    fun song_selected_portrait() {
        val c = controller()
        c.selectedClip = 1 to 1
        songWith(c)
        snap("song-selected-portrait")
    }

    @Test
    @Config(qualifiers = "+land")
    fun song_selected_landscape() {
        val c = controller()
        c.selectedClip = 1 to 1
        songWith(c)
        snap("song-selected-landscape")
    }

    /** Mid-recording: live take growing on the armed track, hot meters.
     *  Kept inside the initial view window so the region is on screen. */
    private fun recordingController() =
        demoController(ApplicationProvider.getApplicationContext()) {
            ShadowEngine.transport = org.paw.app.TRANSPORT_RECORDING
            ShadowEngine.recording = true
            ShadowEngine.recStartFrame = 48000L * 2
            ShadowEngine.recFrames = (48000L * 3.5).toLong()
            ShadowEngine.playheadFrame = (48000L * 5.5).toLong()
            ShadowEngine.meters = ShadowEngine.meters + (1 to 0.94f)
        }

    @Test
    fun song_recording_portrait() {
        songWith(recordingController())
        snap("song-recording-portrait")
    }

    @Test
    @Config(qualifiers = "+land")
    fun song_recording_landscape() {
        songWith(recordingController())
        snap("song-recording-landscape")
    }

    /** A brand-new song: two default tracks, nothing recorded. */
    @Test
    fun song_empty_portrait() {
        songWith(emptyController(ApplicationProvider.getApplicationContext()))
        snap("song-empty-portrait")
    }

    /** Transient error banner. */
    @Test
    fun song_message_portrait() {
        val c = controller()
        c.message = "Recording dropped 480 frames"
        songWith(c)
        snap("song-message-portrait")
    }

    /** Eight long-named tracks, solo + mute engaged, stacked clips. */
    @Test
    fun song_crowded_portrait() {
        songWith(crowdedController(ApplicationProvider.getApplicationContext()))
        snap("song-crowded-portrait")
    }

    @Test
    @Config(qualifiers = "+land")
    fun song_crowded_landscape() {
        songWith(crowdedController(ApplicationProvider.getApplicationContext()))
        snap("song-crowded-landscape")
    }

    @Test
    @Config(qualifiers = "+land")
    fun options_landscape() {
        val c = controller()
        content {
            OptionsScreen(
                controller = c,
                keepAwakeMode = org.paw.app.KeepAwakeMode.FiveMinutes,
                onKeepAwakeMode = {},
                latencyMs = 10.4f,
                effectsPath = "/storage/emulated/0/Android/data/org.paw.app/files/effects",
                onBack = {},
            )
        }
        snap("options-landscape")
    }

    // ---- mixer view, effect picker, new-song naming ----

    private fun mixer() {
        val c = controller()
        content {
            SongScreen(
                controller = c,
                onBack = {}, onOpenTrack = {}, onOpenTakes = {}, onOpenOptions = {},
                initialView = org.paw.app.ui.SongView.Mix,
            )
        }
    }

    @Test
    fun mix_portrait() {
        mixer()
        snap("mix-portrait")
    }

    @Test
    @Config(qualifiers = "+land")
    fun mix_landscape() {
        mixer()
        snap("mix-landscape")
    }

    @Test
    fun mix_crowded_portrait() {
        val c = crowdedController(ApplicationProvider.getApplicationContext())
        content {
            SongScreen(
                controller = c,
                onBack = {}, onOpenTrack = {}, onOpenTakes = {}, onOpenOptions = {},
                initialView = org.paw.app.ui.SongView.Mix,
            )
        }
        snap("mix-crowded-portrait")
    }

    @Test
    fun track_picker_portrait() {
        val c = controller()
        content {
            TrackDetailScreen(controller = c, trackId = 2, onBack = {}, initialPicking = true)
        }
        snap("track-picker-portrait")
    }

    @Test
    fun track_mic_portrait() {
        val c = controller()
        content { TrackDetailScreen(controller = c, trackId = 4, onBack = {}) }
        snap("track-mic-portrait")
    }

    @Test
    fun projects_naming_portrait() {
        val entries = listOf(
            ProjectStore.Entry("Demo Song", File("/x/Demo Song"), 1786043160000L),
        )
        content {
            ProjectListScreen(
                entries = entries,
                onOpen = {}, onCreate = {}, onRename = { _, _ -> true },
                onDelete = { _, _ -> },
                initialNaming = true,
            )
        }
        snap("projects-naming-portrait")
    }

    /** Playhead far ahead of the view: the playhead chip on the right edge. */
    @Test
    fun song_playhead_offscreen_portrait() {
        val c = demoController(ApplicationProvider.getApplicationContext()) {
            ShadowEngine.playheadFrame = 48000L * 40
        }
        songWith(c)
        snap("song-playhead-offscreen-portrait")
    }
}
