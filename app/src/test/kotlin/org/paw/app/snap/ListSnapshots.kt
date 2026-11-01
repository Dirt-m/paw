package org.paw.app.snap

import java.io.File
import org.junit.Test
import org.paw.app.model.ProjectStore
import org.paw.app.ui.OptionsScreen
import org.paw.app.ui.ProjectListScreen
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

// The screens outside a song: the song list and Settings.

class ListSnapshots : SnapshotBase() {

    private fun projectList() {
        val entries = listOf(
            ProjectStore.Entry("Demo Song", File("/x/Demo Song"), 1786043160000L),
            ProjectStore.Entry("Rehearsal 3 Aug", File("/x/Rehearsal 3 Aug"), 1785743160000L),
            ProjectStore.Entry("Riff ideas", File("/x/Riff ideas"), 1785443160000L),
        )
        val sr = 48_000L
        fun span(a: Double, b: Double) = (a * sr).toLong() to (b * sr).toLong()
        val summaries = mapOf(
            File("/x/Demo Song") to ProjectStore.Summary(
                clips = listOf(
                    listOf(span(0.0, 8.0), span(10.0, 21.0)),
                    listOf(span(4.0, 18.0)),
                    listOf(span(0.0, 21.0)),
                    listOf(span(6.0, 14.0)),
                    listOf(span(2.0, 4.0), span(4.0, 6.0), span(12.0, 16.0)),
                ),
                patternTracks = setOf(4),
                lengthFrames = 21 * sr, sampleRate = 48_000, tempoBpm = 112f,
            ),
            File("/x/Rehearsal 3 Aug") to ProjectStore.Summary(
                clips = listOf(listOf(span(0.0, 312.0)), listOf(span(0.0, 312.0))),
                patternTracks = emptySet(),
                lengthFrames = 312 * sr, sampleRate = 48_000, tempoBpm = 120f,
            ),
            File("/x/Riff ideas") to ProjectStore.Summary(
                clips = listOf(emptyList(), emptyList()),
                patternTracks = emptySet(),
                lengthFrames = 0, sampleRate = 48_000, tempoBpm = 120f,
            ),
        )
        content {
            ProjectListScreen(
                entries = entries,
                summaries = summaries,
                onOpen = {}, onCreate = {}, onRename = { _, _ -> true },
                onDelete = { _, _ -> }, onOpenSettings = {},
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

    @Test
    fun options_portrait() {
        val c = controller()
        content {
            OptionsScreen(
                prefs = org.paw.app.AppPrefs.of(ApplicationProvider.getApplicationContext()),
                songsPath = "Internal storage / Documents / PAW",
                effectsPath = "/storage/emulated/0/Android/data/org.paw.app/files/effects",
                onBack = {},
                backLabel = "Song",
                controller = c,
                latencyMs = 10.4f,
            )
        }
        snap("options-portrait")
    }

    @Test
    @Config(qualifiers = "+land")
    fun options_landscape() {
        val c = controller()
        content {
            OptionsScreen(
                prefs = org.paw.app.AppPrefs.of(ApplicationProvider.getApplicationContext()),
                songsPath = "Internal storage / Documents / PAW",
                effectsPath = "/storage/emulated/0/Android/data/org.paw.app/files/effects",
                onBack = {},
                backLabel = "Song",
                controller = c,
                latencyMs = 10.4f,
            )
        }
        snap("options-landscape")
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
                onDelete = { _, _ -> }, onOpenSettings = {},
                initialNaming = true,
            )
        }
        snap("projects-naming-portrait")
    }
}
