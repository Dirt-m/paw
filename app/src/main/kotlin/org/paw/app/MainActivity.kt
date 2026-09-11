package org.paw.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.paw.app.model.InputDevice
import org.paw.app.model.InputOption
import org.paw.app.model.ProjectStore
import org.paw.app.ui.OptionsScreen
import org.paw.app.ui.ProjectListScreen
import org.paw.app.ui.SongScreen
import org.paw.app.ui.RecordingsScreen
import org.paw.app.ui.TrackDetailScreen

private sealed interface Nav {
    data object Projects : Nav
    data object Song : Nav
    data class TrackDetail(val trackId: Int) : Nav
    data object Takes : Nav
    data object Options : Nav
}

private const val KEEP_AWAKE_WINDOW_MS = 5 * 60_000L

class MainActivity : ComponentActivity() {

    /** Bumped by every touch; the five-minute keep-awake counts from here. */
    private var lastInteractionMs by mutableLongStateOf(SystemClock.uptimeMillis())

    override fun onUserInteraction() {
        lastInteractionMs = SystemClock.uptimeMillis()
    }

    /**
     * Bumped when the app returns to the foreground having let its streams go.
     * A key of the stream-opening effect, so returning reopens on whatever is
     * connected now.
     */
    private var foregroundEpoch by mutableStateOf(0)
    private var streamsReleased = false
    private val lifecycleStateBuf = LongArray(ENGINE_STATE_SLOTS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ProjectStore(applicationContext)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var nav by remember { mutableStateOf<Nav>(Nav.Projects) }
                var controller by remember { mutableStateOf<SongController?>(null) }
                var entries by remember { mutableStateOf(store.list()) }

                // A recorder must not die to the lock screen mid-take, so a
                // busy transport always holds the screen. Past that, On holds
                // it for good and FiveMinutes until five minutes after the
                // last touch, so a forgotten phone stops draining.
                var keepAwakeMode by remember { mutableStateOf(AppPrefs.of(this).keepAwakeMode) }
                val keepFlag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                LaunchedEffect(keepAwakeMode) {
                    when (keepAwakeMode) {
                        KeepAwakeMode.On -> window.addFlags(keepFlag)
                        KeepAwakeMode.Off -> window.clearFlags(keepFlag)
                        KeepAwakeMode.FiveMinutes -> while (true) {
                            val c = controller
                            val busy = c != null && (
                                c.status.transport != TRANSPORT_STOPPED ||
                                    c.status.recording || c.status.recPending
                                )
                            val deadline = lastInteractionMs + KEEP_AWAKE_WINDOW_MS
                            val now = SystemClock.uptimeMillis()
                            if (busy || now < deadline) {
                                window.addFlags(keepFlag)
                                delay(if (busy) 1_000L else deadline - now)
                            } else {
                                window.clearFlags(keepFlag)
                                // Sleep until the next touch brings the hold back.
                                snapshotFlow { lastInteractionMs }.first {
                                    it + KEEP_AWAKE_WINDOW_MS > SystemClock.uptimeMillis()
                                }
                            }
                        }
                    }
                }
                val endpoints = rememberAudioEndpoints()
                val devices = endpoints.usb

                var hasMic by remember { mutableStateOf(hasMicPermission()) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted -> hasMic = granted }

                fun openSong(dir: File) {
                    val song = store.load(dir)
                    if (song == null) {
                        // Both the manifest and its backup failed to parse.
                        Toast.makeText(
                            this, "Can't open ${dir.name}: song file unreadable",
                            Toast.LENGTH_LONG,
                        ).show()
                        return
                    }
                    controller?.stop()
                    // The controller owns its coroutines; nothing song-scoped
                    // runs on the composition scope.
                    controller = SongController(applicationContext, store, dir, song).also {
                        it.start()
                    }
                    nav = Nav.Song
                }

                // The input stream follows the armed tracks' chosen device;
                // the output follows the master-screen choice.
                val usbInputs = remember(devices) { devices.filter { it.isInput } }
                val inputOptions = remember(devices) {
                    usbInputs.map { InputOption(InputDevice.Usb(it.name), it.name) } +
                        InputOption(InputDevice.Mic, "Phone mic")
                }
                // Wired and Bluetooth are monitoring paths like any other, so
                // every connected playback endpoint is listed, not only the
                // interfaces.
                val outputOptions = remember(endpoints.outputs) {
                    endpoints.outputs.map { DeviceOption(it.key, it.name) }
                }
                LaunchedEffect(controller, inputOptions, outputOptions) {
                    controller?.inputOptions = inputOptions
                    controller?.outputOptions = outputOptions
                }

                // A track's saved device resolves against what is actually
                // connected; AnyUsb takes the first interface.
                fun resolve(dev: InputDevice): InputDevice? = when (dev) {
                    InputDevice.Mic -> InputDevice.Mic
                    InputDevice.AnyUsb, is InputDevice.Usb -> usbInputs.firstOrNull {
                        dev == InputDevice.AnyUsb || dev == InputDevice.Usb(it.name)
                    }?.let { InputDevice.Usb(it.name) }
                    InputDevice.None -> null
                }

                val chosenInput = controller?.let { c ->
                    c.song.tracks
                        .filter { it.armed && it.inputDevice != InputDevice.None && it.inputMode != 0 }
                        .firstNotNullOfOrNull { resolve(it.inputDevice) }
                } ?: usbInputs.firstOrNull()?.let { InputDevice.Usb(it.name) } ?: InputDevice.Mic

                // The saved route resolves against what is connected; AUTO
                // takes the first in preference order (interface, wired,
                // Bluetooth, then the speaker). A route that has gone away
                // falls back to AUTO rather than to the speaker.
                val outPref = controller?.outputDevice ?: ""
                val outDevice = endpoints.outputs.firstOrNull { it.key == outPref }
                    ?: endpoints.outputs.firstOrNull()

                fun reopenStreams(c: SongController) {
                    val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                    // Explicit ids only: Android routes "default" to whatever
                    // USB device is attached, which is what a user picking
                    // their headphones means to override.
                    val outId = outDevice?.id ?: builtinSpeakerId(am)
                    val inId = if (chosenInput == InputDevice.Mic) builtinMicId(am)
                    else usbInputs.first { InputDevice.Usb(it.name) == chosenInput }.id
                    c.openStreams(inId, outId, chosenInput, outDevice?.key ?: "phone")
                }
                val deviceIds = remember(devices) { devices.map { it.id } }
                LaunchedEffect(
                    controller, chosenInput, outDevice?.id, hasMic, deviceIds, foregroundEpoch,
                ) {
                    val c = controller ?: return@LaunchedEffect
                    if (!hasMic) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@LaunchedEffect
                    }
                    reopenStreams(c)
                    // A yanked cable flags deviceLost; reopen on the devices we
                    // still have. Watched as a flow, not as a composition read:
                    // this is the nav root, and it must not re-execute per poll.
                    snapshotFlow { c.status.deviceLost }.collect { lost ->
                        if (lost) reopenStreams(c)
                    }
                }
                LaunchedEffect(controller?.lastExport) {
                    controller?.lastExport?.let { file ->
                        shareWav(file)
                        controller?.lastExport = null
                    }
                }

                when (val here = nav) {
                    Nav.Projects -> ProjectListScreen(
                        entries = entries,
                        onOpen = { openSong(it.dir) },
                        onCreate = { name ->
                            if (!store.exists(name)) store.create(name)
                            entries = store.list()
                            openSong(store.dirFor(name))
                        },
                        onRename = { entry, newName ->
                            store.rename(entry.dir, newName).also { entries = store.list() }
                        },
                        onDelete = { entry, keepTakes ->
                            store.delete(entry.dir, keepTakes)
                            entries = store.list()
                        },
                    )

                    Nav.Song -> {
                        val c = controller
                        if (c == null) {
                            nav = Nav.Projects
                        } else {
                            BackHandler {
                                c.stop()
                                Engine.closeStreams()
                                controller = null
                                entries = store.list()
                                nav = Nav.Projects
                            }
                            SongScreen(
                                controller = c,
                                onBack = {
                                    c.stop()
                                    Engine.closeStreams()
                                    controller = null
                                    entries = store.list()
                                    nav = Nav.Projects
                                },
                                onOpenTrack = { nav = Nav.TrackDetail(it) },
                                onOpenTakes = { nav = Nav.Takes },
                                onOpenOptions = { nav = Nav.Options },
                            )
                        }
                    }

                    is Nav.TrackDetail -> {
                        val c = controller
                        if (c == null) {
                            nav = Nav.Projects
                        } else {
                            BackHandler { nav = Nav.Song }
                            TrackDetailScreen(c, here.trackId, onBack = { nav = Nav.Song })
                        }
                    }

                    Nav.Takes -> {
                        val c = controller
                        if (c == null) {
                            nav = Nav.Projects
                        } else {
                            BackHandler { nav = Nav.Song }
                            RecordingsScreen(c, onBack = { nav = Nav.Song })
                        }
                    }

                    Nav.Options -> {
                        val c = controller
                        if (c == null) {
                            nav = Nav.Projects
                        } else {
                            BackHandler { nav = Nav.Song }
                            // A JNI call that takes the engine's control lock:
                            // read it once per route, not once per recomposition.
                            val latencyFrames = remember(c.activeOutputRoute) {
                                Latency.frames(
                                    this@MainActivity,
                                    c.activeOutputRoute,
                                    Engine.routeLatencyFrames(),
                                )
                            }
                            OptionsScreen(
                                controller = c,
                                keepAwakeMode = keepAwakeMode,
                                onKeepAwakeMode = { mode ->
                                    keepAwakeMode = mode
                                    AppPrefs.of(this).keepAwakeMode = mode
                                },
                                latencyMs = latencyFrames * 1000f /
                                    org.paw.app.model.SAMPLE_RATE,
                                effectsPath = File(
                                    getExternalFilesDir(null), "effects",
                                ).absolutePath,
                                onBack = { nav = Nav.Song },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun shareWav(file: File) {
        val uri = FileProvider.getUriForFile(this, "org.paw.app.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share mixdown"))
    }

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        if (streamsReleased) {
            streamsReleased = false
            foregroundEpoch++
        }
    }

    /**
     * Let the hardware go when we leave the foreground. An exclusive stream
     * held open on an explicit device id pins the platform's routing there for
     * everything else on the phone, so a backgrounded PAW! would force
     * every other app onto whatever it last opened.
     *
     * Rotation does not come through here (configChanges keeps the activity
     * alive), and neither does a take in progress: closing a live capture
     * stream would truncate the recording, so a busy transport keeps its
     * streams and the routing that goes with them.
     */
    override fun onStop() {
        super.onStop()
        Engine.engineState(lifecycleStateBuf)
        val open = lifecycleStateBuf[ST_OUTPUT_OPEN] != 0L
        val busy = lifecycleStateBuf[ST_TRANSPORT] != TRANSPORT_STOPPED.toLong() ||
            lifecycleStateBuf[ST_RECORDING] != 0L ||
            lifecycleStateBuf[ST_REC_PENDING] != 0L
        if (!open || busy) return
        Engine.closeStreams()
        streamsReleased = true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            Engine.engineShutdown()
        }
    }
}
