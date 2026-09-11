package org.paw.app.snap

import android.content.Context
import java.io.File
import org.paw.app.DeviceOption
import org.paw.app.SongController
import org.paw.app.model.Clip
import org.paw.app.model.Effect
import org.paw.app.model.INPUT_CH1
import org.paw.app.model.INPUT_CH2
import org.paw.app.model.INPUT_STEREO
import org.paw.app.model.InputDevice
import org.paw.app.model.InputOption
import org.paw.app.model.MASTER_TRACK_ID
import org.paw.app.model.ProjectStore
import org.paw.app.model.SAMPLE_RATE
import org.paw.app.model.Song
import org.paw.app.model.Track

// A representative mid-session song, so every screen has something real to
// show: several tracks in different states, clips with trims and fades,
// effects on two chains, an unused spare recording, a loop, and live-looking
// meters.

private const val SR = SAMPLE_RATE
private fun sec(s: Double): Long = (s * SR).toLong()

/** Registers a fake take with the shadow and drops a stand-in file on disk.
 *  The content is never read (wavInfo and computePeaks are shadowed) but the
 *  takes screen lists the directory and the startup sweep checks file sizes. */
private fun fakeTake(audioDir: File, name: String, frames: Long): String {
    ShadowEngine.takeFrames[name] = frames
    File(audioDir, name).writeBytes(ByteArray(1024))
    return "audio/$name"
}

fun demoSong(audioDir: File): Song {
    val guitar1 = fakeTake(audioDir, "take_20260805-141210_t1.wav", sec(9.0))
    val guitar2 = fakeTake(audioDir, "take_20260805-141830_t1.wav", sec(12.0))
    val vox = fakeTake(audioDir, "take_20260805-142605_t2.wav", sec(14.0))
    val drums = fakeTake(audioDir, "take_20260805-143120_t3.wav", sec(21.0))
    // Unused spare: exercises the recordings screen's deletable state.
    fakeTake(audioDir, "take_20260805-143950_t2.wav", sec(6.0))

    val tracks = listOf(
        Track(
            id = 1, name = "Guitar",
            armed = true, monitor = true,
            inputDevice = InputDevice.Usb("M-Track Duo"), inputMode = INPUT_CH1,
            gain = 0.9f, pan = -0.25f,
            clips = listOf(
                Clip(id = 1, take = guitar1, srcStart = 0, length = sec(8.0), start = 0, fadeIn = sec(0.15)),
                Clip(
                    id = 2, take = guitar2, srcStart = sec(1.0), length = sec(10.0),
                    start = sec(10.0), fadeOut = sec(1.2),
                ),
            ),
            effects = listOf(
                Effect("builtin:paw_comp", listOf(-18f, 4f, 10f, 120f, 3f)),
                Effect("builtin:paw_eq3", listOf(1.5f, -2f, 800f, 3f), mix = 0.8f),
            ),
        ),
        Track(
            id = 2, name = "Vox",
            inputDevice = InputDevice.Usb("M-Track Duo"), inputMode = INPUT_CH2,
            gain = 1.1f, pan = 0f,
            clips = listOf(
                Clip(
                    id = 3, take = vox, srcStart = sec(0.5), length = sec(12.0),
                    start = sec(4.0), fadeIn = sec(0.3), fadeOut = sec(0.8),
                ),
            ),
            effects = listOf(
                Effect("builtin:paw_reverb", listOf(60f, 40f, 20f, 30f), mix = 0.35f),
                Effect("builtin:paw_delay", listOf(250f, 30f, 20f), bypass = true),
            ),
        ),
        Track(
            id = 3, name = "Drums",
            mute = true,
            inputDevice = InputDevice.AnyUsb, inputMode = INPUT_STEREO,
            gain = 0.75f, pan = 0.1f,
            clips = listOf(
                Clip(id = 4, take = drums, srcStart = 0, length = sec(20.0), start = 0),
            ),
        ),
        Track(
            id = 4, name = "Bass",
            inputDevice = InputDevice.Mic, inputMode = INPUT_CH1,
        ),
    )

    return Song(
        name = "Demo Song",
        tracks = tracks,
        master = Track(
            id = MASTER_TRACK_ID, name = "Master", inputMode = 0,
            gain = 0.95f,
            effects = listOf(Effect("builtin:paw_limiter", listOf(-1f, -0.3f, 50f))),
        ),
        nextTrackId = 5,
        nextClipId = 6,
        tempoBpm = 112f,
        beatsPerBar = 4,
        loopStart = sec(4.0),
        loopEnd = sec(12.0),
        loopEnabled = true,
    )
}

/** Starts a controller for [song] against the shadow engine. The controller's
 *  first poll tick runs synchronously inside start(), so any shadow state
 *  ([configureShadow]) must be in place before this. */
private fun startController(
    context: Context,
    name: String,
    song: Song,
    configureShadow: () -> Unit,
): SongController {
    val store = ProjectStore(context)
    val dir = store.dirFor(name)
    store.save(dir, song)
    configureShadow()
    val controller = SongController(context, store, dir, song)
    controller.inputOptions = listOf(
        InputOption(InputDevice.Usb("M-Track Duo"), "M-Track Duo"),
        InputOption(InputDevice.Mic, "Phone mic"),
    )
    controller.outputOptions = listOf(
        DeviceOption("usb:M-Track Duo", "M-Track Duo"),
        DeviceOption("bt:WH-1000XM4", "WH-1000XM4"),
        DeviceOption("phone", "Phone speaker"),
    )
    controller.start()
    controller.openStreams(7, 8, InputDevice.Usb("M-Track Duo"), "usb:M-Track Duo")
    return controller
}

/** Builds the demo project on disk and returns a started controller wired to
 *  the shadow engine, mid-playback with live meters. [configureShadow] can
 *  override the shadow state (e.g. a live recording) before the controller's
 *  first poll adopts it. */
fun demoController(context: Context, configureShadow: () -> Unit = {}): SongController {
    ShadowEngine.reset()
    val store = ProjectStore(context)
    val audioDir = store.audioDir(store.dirFor("Demo Song"))
    val song = demoSong(audioDir)
    return startController(context, "Demo Song", song, {
        ShadowEngine.transport = 1
        // Inside the portrait view window (~5 s at default zoom from 0).
        ShadowEngine.playheadFrame = sec(3.0)
        ShadowEngine.meters = mapOf(
            1 to 0.72f,
            2 to 0.45f,
            3 to 0.0f,
            4 to 0.12f,
            MASTER_TRACK_ID to 0.81f,
        )
        configureShadow()
    })
}

/** A brand-new song exactly as ProjectStore.create makes it: two default
 *  tracks, no clips, transport stopped. */
fun emptyController(context: Context): SongController {
    ShadowEngine.reset()
    val store = ProjectStore(context)
    if (!store.exists("New Song")) store.create("New Song")
    val song = store.load(store.dirFor("New Song"))!!
    return startController(context, "New Song", song) {}
}

/** Stress state: eight tracks with long names, a solo, stacked clips, a long
 *  song title. Catches scroll, truncation, and header-width problems. */
fun crowdedController(context: Context): SongController {
    ShadowEngine.reset()
    val store = ProjectStore(context)
    val audioDir = store.audioDir(store.dirFor("Late Night Session 4 Full Band"))
    val takes = listOf(
        fakeTake(audioDir, "take_20260806-221011_t1.wav", sec(30.0)),
        fakeTake(audioDir, "take_20260806-221545_t2.wav", sec(25.0)),
        fakeTake(audioDir, "take_20260806-222130_t3.wav", sec(28.0)),
    )
    val names = listOf(
        "Acoustic Rhythm Guitar", "Lead Vocal Double Tk 2", "Overhead Drums L+R",
        "Bass DI (Compressed)", "Electric Lead Guitar", "Backing Vox Stack",
        "Percussion + Shaker", "Room Mic (Far Corner)",
    )
    val tracks = names.mapIndexed { i, name ->
        Track(
            id = i + 1, name = name,
            armed = i == 0,
            solo = i == 4,
            mute = i == 6,
            monitor = i == 0,
            gain = 0.6f + (i % 4) * 0.15f,
            pan = ((i % 5) - 2) * 0.4f,
            inputDevice = if (i % 3 == 2) InputDevice.Mic else InputDevice.Usb("M-Track Duo"),
            inputMode = (i % 2) + 1,
            clips = listOf(
                Clip(
                    id = i * 2 + 1, take = takes[i % 3],
                    srcStart = 0, length = sec(6.0 + i), start = sec(1.0 * i),
                    fadeIn = sec(0.2),
                ),
                Clip(
                    id = i * 2 + 2, take = takes[(i + 1) % 3],
                    srcStart = sec(2.0), length = sec(5.0), start = sec(14.0 + i),
                ),
            ),
        )
    }
    val song = Song(
        name = "Late Night Session 4 Full Band",
        tracks = tracks,
        nextTrackId = 9,
        nextClipId = 20,
        tempoBpm = 91.5f,
        beatsPerBar = 7,
    )
    return startController(context, "Late Night Session 4 Full Band", song) {
        ShadowEngine.transport = 1
        ShadowEngine.playheadFrame = sec(9.0)
        ShadowEngine.meters =
            (1..8).associateWith { 0.15f + (it * 37 % 70) / 100f } + (MASTER_TRACK_ID to 0.93f)
    }
}
