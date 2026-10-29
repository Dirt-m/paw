package org.paw.app.model

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

// One folder per song: project.json beside audio/ (immutable takes) and
// mixdowns/ (exports). The manifest is tiny and saved on every edit, so there
// is no explicit save. Writes go through a temp file so a crash mid-write
// can't corrupt the last good manifest, and takes are never touched here.
//
// Songs live in Documents/PAW, where the Files app and a computer over USB
// can find them. Older installs kept them in the app's private folder;
// migrate() brings those over.
class ProjectStore(
    context: Context,
    val root: File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PAW",
    ),
) {
    private val legacyRoot: File = File(context.getExternalFilesDir(null), "projects")
    private val prefs = org.paw.app.AppPrefs.of(context)

    data class Entry(val name: String, val dir: File, val lastModified: Long)

    private fun songDirs(parent: File): Array<File> =
        // A song whose main manifest is mid-rotation (or torn) still lists via
        // its .bak; load() recovers from the same file.
        parent.listFiles { f ->
            f.isDirectory &&
                (File(f, "project.json").exists() || File(f, "project.json.bak").exists())
        } ?: emptyArray()

    fun list(): List<Entry> {
        // A song that failed to migrate is still listed from where it is.
        val dirs = songDirs(root) + songDirs(legacyRoot)
        return dirs
            .map {
                val main = File(it, "project.json")
                val stamp =
                    if (main.exists()) main.lastModified()
                    else File(it, "project.json.bak").lastModified()
                Entry(it.name, it, stamp)
            }
            .sortedByDescending { it.lastModified }
    }

    fun dirFor(name: String): File = File(root, sanitize(name))

    // Takes are WAVs in a shared folder; without this every music player on
    // the phone would list them.
    private fun ensureRoot() {
        root.mkdirs()
        File(root, ".nomedia").let { if (!it.exists()) runCatching { it.createNewFile() } }
    }

    fun audioDir(dir: File): File = File(dir, "audio").apply { mkdirs() }

    fun mixdownDir(dir: File): File = File(dir, "mixdowns").apply { mkdirs() }

    fun exists(name: String): Boolean =
        File(dirFor(name), "project.json").exists() ||
            File(dirFor(name), "project.json.bak").exists()

    /**
     * A new song with two tracks on [input], the input that is actually
     * available right now: an interface gets one track per channel, the phone
     * mic both tracks. The choice is saved, so the song keeps it when it is
     * later opened with something else plugged in.
     */
    fun create(name: String, input: InputDevice): Song {
        ensureRoot()
        val dir = dirFor(name)
        audioDir(dir)
        val second = if (input.isUsb) INPUT_CH2 else INPUT_CH1
        val song = Song(
            name = name,
            tracks = listOf(
                Track(id = 1, name = "Track 1", inputDevice = input, inputMode = INPUT_CH1),
                Track(id = 2, name = "Track 2", inputDevice = input, inputMode = second),
            ),
            nextTrackId = 3,
        )
        save(dir, song)
        return song
    }

    /** Renames the song and its folder. False if the name is taken or invalid. */
    fun rename(dir: File, newName: String): Boolean {
        val song = load(dir) ?: return false
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val newDir = dirFor(trimmed)
        if (newDir.absolutePath == dir.absolutePath) {
            save(dir, song.copy(name = trimmed))
            return true
        }
        if (newDir.exists() || !dir.renameTo(newDir)) return false
        save(newDir, song.copy(name = trimmed))
        return true
    }

    /** Deletes a song. keepTakes removes only the manifest; the folder stays
     *  on disk with its recorded audio, it just stops being a song. */
    fun delete(dir: File, keepTakes: Boolean) {
        if (keepTakes) {
            File(dir, "project.json.tmp").delete()
            File(dir, "project.json.bak").delete()
            File(dir, "project.json").delete()
        } else {
            dir.deleteRecursively()
        }
    }

    fun load(dir: File): Song? =
        readSong(File(dir, "project.json")) ?: readSong(File(dir, "project.json.bak"))

    private fun readSong(f: File): Song? = try {
        val legacy = Song(
            name = "",
            clickEnabled = prefs.legacyClickEnabled,
            countInBars = prefs.legacyCountInBars,
            micBoostDb = prefs.legacyMicBoostDb,
        )
        songFromJson(JSONObject(f.readText()), legacy)
    } catch (e: Exception) {
        null
    }

    fun save(dir: File, song: Song) {
        dir.mkdirs()
        val json = song.toJson().toString(2)
        val tmp = File(dir, "project.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.toByteArray())
            // The rename below makes this file current; its bytes must be on
            // disk first or a power loss can leave a well-named empty manifest.
            out.fd.sync()
        }
        val real = File(dir, "project.json")
        val bak = File(dir, "project.json.bak")
        if (real.exists()) {
            bak.delete()
            real.renameTo(bak)  // previous good manifest becomes the fallback
        }
        if (!tmp.renameTo(real)) {
            // Rename across the same dir shouldn't fail; fall back to direct write.
            real.writeText(json)
            tmp.delete()
        }
    }

    /**
     * Moves songs out of the old private folder into [root]. Each song is
     * copied whole and every file's size checked before the old copy is
     * removed, so a failure part way leaves the original untouched (and
     * still listed). Returns how many songs failed to move.
     */
    fun migrate(): Int {
        val old = songDirs(legacyRoot)
        if (old.isEmpty()) return 0
        ensureRoot()
        var failed = 0
        for (src in old) {
            var dst = File(root, src.name)
            var n = 2
            while (dst.exists()) dst = File(root, "${src.name} ${n++}")
            val ok = try {
                src.copyRecursively(dst, overwrite = false)
                src.walkTopDown().filter { it.isFile }.all { f ->
                    File(dst, f.relativeTo(src).path).length() == f.length()
                }
            } catch (e: Exception) {
                false
            }
            if (ok) {
                src.deleteRecursively()
            } else {
                dst.deleteRecursively()
                failed++
            }
        }
        return failed
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "Song" }
}
