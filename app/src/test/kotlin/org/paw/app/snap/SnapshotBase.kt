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
import org.paw.app.ui.SongScreen
import org.paw.app.ui.SongUi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The harness every snapshot file shares: the reference phone, its insets,
// the dark theme and the demo song. Each area of the app has its own file of
// snapshots on top of this one. Run them all with tools/snapshots.sh; a
// Pixel 9a is 1080x2424 px at 420 dpi = 411x923 dp, and "+land" swaps it.

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], shadows = [ShadowEngine::class], qualifiers = "w411dp-h923dp-420dpi")
abstract class SnapshotBase {

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

    protected val composeRule = createAndroidComposeRule<ComponentActivity>()
    protected val compose get() = composeRule

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(registerHostActivity).around(composeRule)

    /**
     * Robolectric windows have no system bars, so the screens' safeDrawing
     * padding collapses to zero; dispatch the reference phone's insets by hand.
     * Values approximate a Pixel 9a with gesture nav (status bar over the punch
     * hole, gesture pill strip, cutout on the left in landscape).
     */
    protected fun applyPhoneInsets() {
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

    protected fun content(body: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { body() }
        }
    }

    /** Lets Dispatchers.IO work (peaks, take lists) land back on the UI. */
    protected fun settle() {
        repeat(20) {
            compose.waitForIdle()
            Thread.sleep(15)
        }
        compose.waitForIdle()
    }

    protected fun snap(name: String) {
        applyPhoneInsets()
        settle()
        compose.onRoot().captureRoboImage("snapshots/$name.png")
    }

    protected fun controller() = demoController(ApplicationProvider.getApplicationContext())

    /** Prefs are a process singleton; a zoom one test saved must not reach the next. */
    @org.junit.Before
    fun freshPrefs() {
        val f = org.paw.app.AppPrefs::class.java.getDeclaredField("instance")
        f.isAccessible = true
        f.set(null, null)
    }

    protected val app: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /** The song screen around [c], in whatever mode and panel [ui] holds. */
    protected fun songWith(c: org.paw.app.SongController, ui: SongUi = SongUi()) {
        content { SongScreen(controller = c, onBack = {}, onOpenSettings = {}, ui = ui) }
    }
}
