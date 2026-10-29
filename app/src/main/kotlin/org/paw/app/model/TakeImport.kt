package org.paw.app.model

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Importing a file from elsewhere (a field recorder's card, a download) as a
// new take. The result is a plain RIFF WAV at 48 kHz that the
// engine's reader opens: fmt tag 1 or 3, never WAVE_FORMAT_EXTENSIBLE. A WAV
// already at 48 kHz keeps its sample bytes exactly; only the header is
// rewritten. Anything else is resampled to float32. Compressed formats go
// through the platform decoder. The take is written under a temporary name
// and renamed at the end, so a failed import leaves nothing in audio/.

/** A failed import; the message is short enough to show the user as is. */
class ImportException(message: String) : IOException(message)

// Internal reason for rejecting a file; importTake adds the file name.
private class Reject(val reason: String) : Exception(reason)

/** Opens [uri] through the content resolver and imports it into [audioDir].
 *  Blocking; run it on Dispatchers.IO. */
fun importTake(context: Context, uri: Uri, audioDir: File): File {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?: uri.lastPathSegment
        ?: "take"
    val input = resolver.openInputStream(uri) ?: throw ImportException("Could not open $name")
    return input.use { importTake(it, name, resolver.getType(uri), audioDir, context.cacheDir) }
}

/** Imports the audio in [input] as a new take in [audioDir] and returns the
 *  file. [scratchDir] holds temporary copies while it works. Blocking. */
fun importTake(
    input: InputStream,
    displayName: String,
    mimeType: String?,
    audioDir: File,
    scratchDir: File,
): File {
    audioDir.mkdirs()
    scratchDir.mkdirs()
    val target = importTarget(audioDir, displayName)
    // Not a .wav, so the startup sweep and the recordings list never see it.
    val part = File(audioDir, target.name + ".part")
    try {
        if (mimeType != null && (mimeType.startsWith("text/") || mimeType.startsWith("image/"))) {
            throw Reject("not audio")
        }
        val stream = BufferedInputStream(input, 1 shl 16)
        stream.mark(16)
        val head = ByteArray(12)
        val n = readFully(stream, head, 0, 12)
        stream.reset()
        if (n == 12 && ascii(head, 0) == "RIFF" && ascii(head, 8) == "WAVE") {
            importWav(stream, part, scratchDir)
        } else {
            importCompressed(stream, part, scratchDir)
        }
        if (!part.renameTo(target)) throw IOException("Could not save ${target.name}")
        return target
    } catch (e: Reject) {
        part.delete()
        throw ImportException("Unsupported file: $displayName (${e.reason})")
    } catch (e: Throwable) {
        part.delete()
        throw e
    }
}

/** import_<name>.wav, with _2, _3 and so on if that is taken. */
internal fun importTarget(audioDir: File, displayName: String): File {
    val base = displayName.substringAfterLast('/').substringAfterLast(':')
        .substringBeforeLast('.')
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .ifBlank { "take" }
    var f = File(audioDir, "import_$base.wav")
    var n = 2
    while (f.exists()) f = File(audioDir, "import_${base}_${n++}.wav")
    return f
}

// WAV input

/** Sample layout of the incoming audio. */
private class Pcm(val float: Boolean, val bits: Int, val channels: Int, val rate: Int) {
    val frameBytes get() = bits / 8 * channels

    /** What WavReader::open accepts, so the bytes can be kept as they are. */
    val engineReadable get() = if (float) bits == 32 else bits == 16 || bits == 24 || bits == 32

    fun check(): Pcm {
        if (channels !in 1..2) throw Reject("$channels channels")
        if (rate !in 8000..384000) throw Reject("$rate Hz")
        val ok = if (float) bits == 32 || bits == 64 else bits == 16 || bits == 24 || bits == 32
        if (!ok) throw Reject(if (float) "$bits-bit float" else "$bits-bit PCM")
        return this
    }
}

// The KSDATAFORMAT_SUBTYPE GUID after its leading format tag.
private val GUID_TAIL = byteArrayOf(
    0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71,
)

private fun parseFmt(b: ByteArray, size: Int): Pcm {
    val bb = ByteBuffer.wrap(b, 0, size).order(ByteOrder.LITTLE_ENDIAN)
    var tag = bb.getShort(0).toInt() and 0xFFFF
    val channels = bb.getShort(2).toInt() and 0xFFFF
    val rate = bb.getInt(4)
    val blockAlign = bb.getShort(12).toInt() and 0xFFFF
    val bits = bb.getShort(14).toInt() and 0xFFFF
    if (tag == 0xFFFE) {
        if (size < 40) throw Reject("broken format chunk")
        val sub = bb.getShort(24).toInt() and 0xFFFF
        if (!(0 until 14).all { b[26 + it] == GUID_TAIL[it] }) throw Reject("unknown subformat")
        tag = sub
    }
    if (tag != 1 && tag != 3) throw Reject("format $tag")
    val pcm = Pcm(tag == 3, bits, channels, rate).check()
    if (blockAlign != pcm.frameBytes) throw Reject("odd sample packing")
    return pcm
}

private fun importWav(input: InputStream, out: File, scratchDir: File) {
    skipFully(input, 12)
    var pcm: Pcm? = null
    // A data chunk ahead of fmt (rare, but legal) is parked here until the
    // format is known.
    var spill: File? = null
    val hdr = ByteArray(8)
    try {
        while (readFully(input, hdr, 0, 8) == 8) {
            val id = ascii(hdr, 0)
            val size = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and
                0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    if (size < 16 || size > 4096) throw Reject("broken format chunk")
                    val b = ByteArray(size.toInt())
                    if (readFully(input, b, 0, b.size) < b.size) throw Reject("truncated")
                    pcm = parseFmt(b, b.size)
                    skipFully(input, size and 1)
                }
                "data" -> {
                    // Zero or all ones means the writer never patched the size
                    // (a recorder that lost power, or a streaming writer): the
                    // audio runs to the end of the file.
                    val known = size != 0L && size != 0xFFFFFFFFL
                    val limit = if (known) size else Long.MAX_VALUE
                    val p = pcm
                    if (p != null) {
                        convert(p, input, limit, out)
                        return
                    }
                    if (spill != null) throw Reject("two data chunks")
                    val s = File.createTempFile("import", ".pcm", scratchDir)
                    spill = s
                    FileOutputStream(s).use { copyBytes(input, it, limit) }
                    if (!known) break
                    skipFully(input, size and 1)
                }
                else -> if (!skipFully(input, size + (size and 1))) break
            }
        }
        val p = pcm ?: throw Reject("no format chunk")
        val s = spill ?: throw Reject("no audio data")
        s.inputStream().buffered().use { convert(p, it, Long.MAX_VALUE, out) }
    } finally {
        spill?.delete()
    }
}

private fun convert(pcm: Pcm, input: InputStream, limit: Long, out: File) {
    Pipeline(pcm, out).use { pipe ->
        val buf = ByteArray(1 shl 16)
        var left = limit
        while (left > 0) {
            val n = input.read(buf, 0, min(left, buf.size.toLong()).toInt())
            if (n < 0) break
            pipe.write(buf, 0, n)
            left -= n
        }
        if (pipe.finish() == 0L) throw Reject("no audio")
    }
}

// Compressed input

private fun importCompressed(input: InputStream, out: File, scratchDir: File) {
    // MediaExtractor wants a seekable source, and a SAF stream is not one.
    val src = File.createTempFile("import", ".src", scratchDir)
    try {
        FileOutputStream(src).use { copyBytes(input, it, Long.MAX_VALUE) }
        decode(src, out)
    } finally {
        src.delete()
    }
}

private fun decode(src: File, out: File) {
    val extractor = try {
        MediaExtractor().apply { setDataSource(src.path) }
    } catch (e: Exception) {
        throw Reject("not an audio file")
    }
    var codec: MediaCodec? = null
    var pipe: Pipeline? = null
    try {
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw Reject("no audio track")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        // Float output where the decoder offers it; otherwise its default 16-bit.
        codec = try {
            val c = MediaCodec.createDecoderByType(mime)
            try {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                c.configure(format, null, null, 0)
                c
            } catch (e: Exception) {
                c.release()
                format.removeKey(MediaFormat.KEY_PCM_ENCODING)
                MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0) }
            }
        } catch (e: IOException) {
            throw Reject("no decoder for $mime")
        }
        codec.start()

        val info = MediaCodec.BufferInfo()
        var bytes = ByteArray(1 shl 16)
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o < 0) continue
            if (info.size > 0) {
                val p = pipe ?: Pipeline(decodedPcm(codec.outputFormat), out).also { pipe = it }
                if (bytes.size < info.size) bytes = ByteArray(info.size)
                val buf = codec.getOutputBuffer(o)!!
                buf.position(info.offset)
                buf.get(bytes, 0, info.size)
                p.write(bytes, 0, info.size)
            }
            codec.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        val p = pipe ?: throw Reject("no audio")
        if (p.finish() == 0L) throw Reject("no audio")
    } catch (e: IllegalStateException) {
        // MediaCodec.CodecException lands here too.
        throw Reject("cannot decode")
    } catch (e: IllegalArgumentException) {
        throw Reject("cannot decode")
    } finally {
        pipe?.close()
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        codec?.release()
        extractor.release()
    }
}

private fun decodedPcm(f: MediaFormat): Pcm {
    val enc = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
        f.getInteger(MediaFormat.KEY_PCM_ENCODING)
    } else {
        AudioFormat.ENCODING_PCM_16BIT
    }
    val channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    return when (enc) {
        AudioFormat.ENCODING_PCM_FLOAT -> Pcm(true, 32, channels, rate)
        AudioFormat.ENCODING_PCM_16BIT -> Pcm(false, 16, channels, rate)
        else -> throw Reject("decoder output $enc")
    }.check()
}

// Conversion

private const val CHUNK_FRAMES = 8192

/** Turns incoming bytes of [pcm] into the take: a byte copy when the format
 *  can stay as it is, otherwise float32 at 48 kHz. */
private class Pipeline(private val pcm: Pcm, file: File) : Closeable {
    private val channels = pcm.channels
    private val copy = pcm.rate == SAMPLE_RATE && pcm.engineReadable
    private val writer =
        if (copy) TakeWriter(file, channels, pcm.float, pcm.bits) else TakeWriter(file, channels, true, 32)
    private val resampler =
        if (pcm.rate == SAMPLE_RATE) null
        else Resampler(channels, pcm.rate, SAMPLE_RATE) { b, n -> writer.writeFloats(b, n) }
    private val acc = ByteArray(CHUNK_FRAMES * pcm.frameBytes)
    private var accLen = 0
    private val floats = FloatArray(CHUNK_FRAMES * channels)

    fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var left = len
        while (left > 0) {
            val n = min(left, acc.size - accLen)
            System.arraycopy(b, o, acc, accLen, n)
            accLen += n
            o += n
            left -= n
            if (accLen == acc.size) flush()
        }
    }

    /** Writes what is buffered, drops a trailing partial frame, closes the
     *  file and returns the frames in the take. */
    fun finish(): Long {
        flush()
        resampler?.finish()
        return writer.finish()
    }

    override fun close() = writer.close()

    private fun flush() {
        val frames = accLen / pcm.frameBytes
        accLen = 0
        if (frames == 0) return
        if (copy) {
            writer.writeBytes(acc, frames * pcm.frameBytes)
            return
        }
        toFloats(acc, frames * channels, pcm, floats)
        if (resampler != null) resampler.process(floats, frames)
        else writer.writeFloats(floats, frames * channels)
    }
}

private fun toFloats(b: ByteArray, samples: Int, pcm: Pcm, dst: FloatArray) {
    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    when {
        pcm.float && pcm.bits == 32 -> bb.asFloatBuffer().get(dst, 0, samples)
        pcm.float -> for (i in 0 until samples) dst[i] = bb.getDouble(i * 8).toFloat()
        pcm.bits == 16 -> for (i in 0 until samples) dst[i] = bb.getShort(i * 2) / 32768f
        pcm.bits == 24 -> for (i in 0 until samples) {
            val k = i * 3
            val v = (b[k].toInt() and 0xFF) or ((b[k + 1].toInt() and 0xFF) shl 8) or
                (b[k + 2].toInt() shl 16)
            dst[i] = v / 8388608f
        }
        else -> for (i in 0 until samples) dst[i] = bb.getInt(i * 4) / 2147483648f
    }
}

/** A plain 44-byte header WAV; sizes are patched in when it is finished. */
private class TakeWriter(
    file: File,
    private val channels: Int,
    float: Boolean,
    private val bits: Int,
) : Closeable {
    private val fos = FileOutputStream(file)
    private val out = BufferedOutputStream(fos, 1 shl 16)
    private var dataBytes = 0L
    private var scratch = ByteBuffer.allocate(0)
    private var closed = false

    init {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(0).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16)
        h.putShort((if (float) 3 else 1).toShort()).putShort(channels.toShort())
        h.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * channels * bits / 8)
        h.putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        h.put("data".toByteArray()).putInt(0)
        out.write(h.array())
    }

    fun writeBytes(b: ByteArray, len: Int) {
        out.write(b, 0, len)
        dataBytes += len
    }

    fun writeFloats(src: FloatArray, n: Int) {
        if (scratch.capacity() < n * 4) scratch = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        scratch.clear()
        scratch.asFloatBuffer().put(src, 0, n)
        writeBytes(scratch.array(), n * 4)
    }

    fun finish(): Long {
        val pad = (dataBytes and 1).toInt()
        if (36 + dataBytes + pad > 0xFFFFFFFFL) throw Reject("longer than a WAV can hold")
        if (pad == 1) out.write(0)
        out.flush()
        val sizes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        sizes.putInt(0, (36 + dataBytes + pad).toInt())
        fos.channel.write(sizes, 4)
        sizes.clear()
        sizes.putInt(0, dataBytes.toInt())
        fos.channel.write(sizes, 40)
        // The rename that follows makes this a take; its bytes go first.
        fos.fd.sync()
        close()
        return dataBytes / (channels * bits / 8)
    }

    override fun close() {
        if (closed) return
        closed = true
        out.close()
    }
}

// Resampling

private const val STOPBAND_DB = 100.0
private const val MAX_ROWS = 4096
private const val OUT_FRAMES = 4096

/**
 * Band limited sample rate conversion: a Kaiser windowed sinc evaluated at
 * each output instant. For the usual rational ratios (44.1k, 96k and their
 * relatives) every output phase has its own exact row of coefficients; an
 * odd rate with more than [MAX_ROWS] phases interpolates between rows.
 *
 * Going up, the passband ends at 90% of the input Nyquist and the stopband
 * starts at the input Nyquist, so no image lands anywhere. Going down, the
 * stopband starts 10% past the output Nyquist; what aliases from that band
 * folds back above 90% of the output Nyquist (above 21.6 kHz at 48k). The
 * filter is centered on each output instant, so timing is preserved.
 */
internal class Resampler(
    private val channels: Int,
    inRate: Int,
    outRate: Int,
    private val emit: (FloatArray, Int) -> Unit,  // interleaved samples, count
) {
    private val up: Int
    private val down: Int
    private val half: Int   // taps per side, in input samples
    private val rows: Int
    private val exact: Boolean
    private val table: FloatArray

    init {
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        val nyquist = min(inRate, outRate) / 2.0
        val pass = 0.9 * nyquist
        val stop = if (inRate < outRate) nyquist else 1.1 * nyquist
        val cutoff = (pass + stop) / 2 / inRate          // cycles per input sample
        val width = 2 * PI * (stop - pass) / inRate      // radians per input sample
        half = ceil((STOPBAND_DB - 7.95) / (2.285 * width) / 2).toInt().coerceAtLeast(2)
        exact = up <= MAX_ROWS
        rows = if (exact) up else MAX_ROWS
        val taps = 2 * half
        table = FloatArray((rows + 1) * taps)
        val beta = 0.1102 * (STOPBAND_DB - 8.7)
        val i0Beta = besselI0(beta)
        val row = DoubleArray(taps)
        for (r in 0..rows) {
            // Row r serves output instants r/rows of the way past input
            // sample i; tap k weighs input sample i + k + 1 - half.
            val a = r.toDouble() / rows
            var sum = 0.0
            for (k in 0 until taps) {
                val t = a - (k + 1 - half)
                val x = t / half
                val w = if (abs(x) >= 1) 0.0 else besselI0(beta * sqrt(1 - x * x)) / i0Beta
                val s = if (t == 0.0) 2 * cutoff else sin(2 * PI * cutoff * t) / (PI * t)
                row[k] = s * w
                sum += row[k]
            }
            // Unity gain at DC for every phase.
            for (k in 0 until taps) table[r * taps + k] = (row[k] / sum).toFloat()
        }
    }

    // Input history per channel; buf[c][0] is input frame `base`. The leading
    // zeros stand in for silence before the file starts.
    private val buf = Array(channels) { FloatArray(CHUNK_FRAMES + 4 * half) }
    private var base = -(half - 1).toLong()
    private var count = half - 1
    private var pos = 0L        // input frame at or before the next output instant
    private var phase = 0       // how far past it, in 1/up of an input frame
    private var consumed = 0L
    private var produced = 0L
    private val out = FloatArray(OUT_FRAMES * channels)
    private var outLen = 0

    fun process(src: FloatArray, frames: Int) {
        ensure(count + frames)
        for (c in 0 until channels) {
            val b = buf[c]
            for (f in 0 until frames) b[count + f] = src[f * channels + c]
        }
        count += frames
        consumed += frames
        run(Long.MAX_VALUE)
        val drop = (pos - half + 1 - base).toInt()
        if (drop > 0) {
            for (b in buf) System.arraycopy(b, drop, b, 0, count - drop)
            count -= drop
            base += drop
        }
    }

    /** Pads the tail with silence and emits the remaining output: exactly
     *  ceil(inputFrames * outRate / inRate) frames in all. */
    fun finish() {
        ensure(count + half)
        for (b in buf) b.fill(0f, count, count + half)
        count += half
        run((consumed * up + down - 1) / down)
        if (outLen > 0) emit(out, outLen)
        outLen = 0
    }

    private fun run(limit: Long) {
        val taps = 2 * half
        while (produced < limit && pos + half < base + count) {
            val start = (pos - half + 1 - base).toInt()
            if (exact) {
                val r = phase * taps
                for (c in 0 until channels) {
                    val x = buf[c]
                    var s = 0f
                    for (k in 0 until taps) s += table[r + k] * x[start + k]
                    out[outLen + c] = s
                }
            } else {
                val at = phase.toDouble() * rows / up
                val r0 = at.toInt()
                val frac = (at - r0).toFloat()
                val a = r0 * taps
                val b = a + taps
                for (c in 0 until channels) {
                    val x = buf[c]
                    var s0 = 0f
                    var s1 = 0f
                    for (k in 0 until taps) {
                        s0 += table[a + k] * x[start + k]
                        s1 += table[b + k] * x[start + k]
                    }
                    out[outLen + c] = s0 + frac * (s1 - s0)
                }
            }
            outLen += channels
            if (outLen == out.size) {
                emit(out, outLen)
                outLen = 0
            }
            produced++
            phase += down
            pos += phase / up
            phase %= up
        }
    }

    private fun ensure(frames: Int) {
        if (frames <= buf[0].size) return
        val size = maxOf(frames, buf[0].size * 2)
        for (c in 0 until channels) buf[c] = buf[c].copyOf(size)
    }
}

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

private fun besselI0(x: Double): Double {
    var sum = 1.0
    var term = 1.0
    val q = x * x / 4
    var k = 1
    while (term > sum * 1e-12) {
        term *= q / (k.toDouble() * k)
        sum += term
        k++
    }
    return sum
}

// Stream helpers

private fun ascii(b: ByteArray, off: Int) = String(b, off, 4, Charsets.US_ASCII)

/** Reads until [len] bytes or the end of the stream; returns the count. */
private fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int): Int {
    var n = 0
    while (n < len) {
        val r = input.read(b, off + n, len - n)
        if (r < 0) break
        n += r
    }
    return n
}

/** False if the stream ended first. */
private fun skipFully(input: InputStream, count: Long): Boolean {
    var left = count
    while (left > 0) {
        val s = input.skip(left)
        if (s > 0) {
            left -= s
        } else {
            if (input.read() < 0) return false
            left--
        }
    }
    return true
}

private fun copyBytes(input: InputStream, out: java.io.OutputStream, limit: Long) {
    val buf = ByteArray(1 shl 16)
    var left = limit
    while (left > 0) {
        val n = input.read(buf, 0, min(left, buf.size.toLong()).toInt())
        if (n < 0) break
        out.write(buf, 0, n)
        left -= n
    }
}
