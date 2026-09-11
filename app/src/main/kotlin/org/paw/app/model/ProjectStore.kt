package org.paw.app.model

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

// One folder per song: project.json beside audio/ (immutable takes) and
// mixdowns/ (exports). The manifest is tiny and saved on every edit, so there
// is no explicit save. Writes go through a temp file so a crash mid-write
// can't corrupt the last good manifest, and takes are never touched here.
class ProjectStore(context: Context) {

    val root: File = File(context.getExternalFilesDir(null), "projects")

    data class Entry(val name: String, val dir: File, val lastModified: Long)

    fun list(): List<Entry> {
        // A song whose main manifest is mid-rotation (or torn) still lists via
        // its .bak; load() recovers from the same file.
        val dirs = root.listFiles { f ->
            f.isDirectory &&
                (File(f, "project.json").exists() || File(f, "project.json.bak").exists())
        }
        return (dirs ?: emptyArray())
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

    fun audioDir(dir: File): File = File(dir, "audio").apply { mkdirs() }

    fun mixdownDir(dir: File): File = File(dir, "mixdowns").apply { mkdirs() }

    fun exists(name: String): Boolean =
        File(dirFor(name), "project.json").exists() ||
            File(dirFor(name), "project.json.bak").exists()

    fun create(name: String): Song {
        val dir = dirFor(name)
        audioDir(dir)
        // Defaults for the reference hardware: one track per Duo input.
        val song = Song(
            name = name,
            tracks = listOf(
                Track(id = 1, name = "Track 1", inputMode = INPUT_CH1),
                Track(id = 2, name = "Track 2", inputMode = INPUT_CH2),
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
        songFromJson(JSONObject(f.readText()))
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

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "Song" }
}
