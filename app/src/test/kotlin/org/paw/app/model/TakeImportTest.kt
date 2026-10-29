package org.paw.app.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Imports synthesized WAVs and reads the takes back. A 48 kHz file must keep
 * its sample bytes exactly; anything else must come out at 48 kHz with its
 * tones intact and nothing folded into the audible band.
 */
class TakeImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private val audio by lazy { tmp.newFolder("audio") }
    private val scratch by lazy { tmp.newFolder("scratch") }

    // Building input files

    private class Chunk(val id: String, val body: ByteArray)

    private fun riff(vararg chunks: Chunk): ByteArray {
        val out = ByteArrayOutputStream()
        for (c in chunks) {
            out.write(c.id.toByteArray())
            out.write(le32(c.body.size))
            out.write(c.body)
            if (c.body.size % 2 == 1) out.write(0)
        }
        val body = out.toByteArray()
        return "RIFF".toByteArray() + le32(body.size + 4) + "WAVE".toByteArray() + body
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun fmt(tag: Int, channels: Int, rate: Int, bits: Int): Chunk {
        val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(tag.toShort()).putShort(channels.toShort()).putInt(rate)
        b.putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort())
        b.putShort(bits.toShort())
        return Chunk("fmt ", b.array())
    }

    private fun fmtExtensible(subTag: Int, channels: Int, rate: Int, bits: Int): Chunk {
        val b = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0xFFFE.toShort()).putShort(channels.toShort()).putInt(rate)
        b.putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort())
        b.putShort(bits.toShort()).putShort(22).putShort(bits.toShort()).putInt(3)
        b.putShort(subTag.toShort())
        b.put(byteArrayOf(0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38,
            0x9B.toByte(), 0x71))
        return Chunk("fmt ", b.array())
    }

    /** Interleaved samples, one generator per channel. */
    private fun tone(frames: Int, rate: Int, vararg gen: (Double) -> Double): Array<DoubleArray> =
        Array(gen.size) { c -> DoubleArray(frames) { n -> gen[c](n.toDouble() / rate) } }

    private fun sine(freq: Double, amp: Double): (Double) -> Double = { t -> amp * sin(2 * PI * freq * t) }

    private fun pcm16(ch: Array<DoubleArray>): ByteArray {
        val b = ByteBuffer.allocate(ch[0].size * ch.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (n in ch[0].indices) for (c in ch) b.putShort((c[n] * 32767).roundToInt().toShort())
        return b.array()
    }

    private fun pcm24(ch: Array<DoubleArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in ch[0].indices) for (c in ch) {
            val v = (c[n] * 8388607).roundToInt()
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun float32(ch: Array<DoubleArray>): ByteArray {
        val b = ByteBuffer.allocate(ch[0].size * ch.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (n in ch[0].indices) for (c in ch) b.putFloat(c[n].toFloat())
        return b.array()
    }

    // Reading takes back

    private class Take(val tag: Int, val channels: Int, val rate: Int, val bits: Int, val data: ByteArray) {
        val frames get() = data.size / (channels * bits / 8)

        fun channel(c: Int): DoubleArray {
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            check(tag == 3 && bits == 32)
            return DoubleArray(frames) { n -> b.getFloat((n * channels + c) * 4).toDouble() }
        }
    }

    private fun readTake(f: File): Take {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(f.readBytes(), 0, 4))
        assertEquals(f.length() - 8, b.getInt(4).toLong())
        // The engine reads a plain header: fmt first, 16 bytes, data next.
        assertEquals(16, b.getInt(16))
        val tag = b.getShort(20).toInt()
        assertTrue("fmt tag $tag", tag == 1 || tag == 3)
        assertEquals("data", String(f.readBytes(), 36, 4))
        val size = b.getInt(40)
        return Take(tag, b.getShort(22).toInt(), b.getInt(24), b.getShort(34).toInt(),
            f.readBytes().copyOfRange(44, 44 + size))
    }

    private fun import(bytes: ByteArray, name: String, mime: String? = "audio/wav"): File =
        importTake(ByteArrayInputStream(bytes), name, mime, audio, scratch)

    /** Amplitude of the [freq] component and the RMS of everything else,
     *  over one second starting at [from]. */
    private fun analyze(x: DoubleArray, freq: Double, from: Int = 24000): Pair<Double, Double> {
        val n = 48000
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            val w = 2 * PI * freq * (from + i) / 48000
            re += x[from + i] * cos(w)
            im += x[from + i] * sin(w)
        }
        re *= 2.0 / n
        im *= 2.0 / n
        var resid = 0.0
        for (i in 0 until n) {
            val w = 2 * PI * freq * (from + i) / 48000
            val r = x[from + i] - re * cos(w) - im * sin(w)
            resid += r * r
        }
        return hypot(re, im) to sqrt(resid / n)
    }

    private fun rms(x: DoubleArray, from: Int, n: Int): Double {
        var s = 0.0
        for (i in from until from + n) s += x[i] * x[i]
        return sqrt(s / n)
    }

    // Tests

    @Test
    fun pcm16At48kKeepsItsBytes() {
        val data = pcm16(tone(48000, 48000, sine(440.0, 0.6)))
        val take = readTake(import(riff(fmt(1, 1, 48000, 16), Chunk("data", data)), "XV_0001.wav"))
        assertEquals(1, take.tag)
        assertEquals(1, take.channels)
        assertEquals(48000, take.rate)
        assertEquals(16, take.bits)
        assertArrayEquals(data, take.data)
    }

    @Test
    fun extensible24BitStereoAt48kKeepsItsBytesAndDropsTheOtherChunks() {
        val data = pcm24(tone(48001, 48000, sine(1000.0, 0.5), sine(250.0, 0.25)))
        val bext = Chunk("bext", ByteArray(601) { it.toByte() })   // odd: padded too
        val list = Chunk("LIST", "INFOISFT\u0005\u0000\u0000\u0000XV1R\u0000".toByteArray())
        val f = import(riff(bext, fmtExtensible(1, 2, 48000, 24), Chunk("data", data), list),
            "XV_0002.WAV")
        assertEquals("import_XV_0002.wav", f.name)
        val take = readTake(f)
        assertEquals(1, take.tag)
        assertEquals(2, take.channels)
        assertEquals(24, take.bits)
        assertEquals(48001, take.frames)
        assertArrayEquals(data, take.data)
        assertEquals(44L + data.size, f.length())
    }

    @Test
    fun dataBeforeFmtStillImports() {
        // Mono 24-bit with an odd frame count: an odd sized data chunk, padded.
        val data = pcm24(tone(4801, 48000, sine(440.0, 0.5)))
        val f = import(riff(Chunk("data", data), fmt(1, 1, 48000, 24)), "late.wav")
        assertArrayEquals(data, readTake(f).data)
        assertEquals(44L + data.size + 1, f.length())
    }

    @Test
    fun float44kSineIsResampledAtTheRightPitchAndLevel() {
        val frames = 44100 * 2
        val amp = 0.5
        val take = readTake(import(
            riff(fmt(3, 1, 44100, 32), Chunk("data", float32(tone(frames, 44100, sine(1000.0, amp))))),
            "sine.wav"))
        assertEquals(3, take.tag)
        assertEquals(32, take.bits)
        assertEquals(48000, take.rate)
        assertEquals(1, take.channels)
        assertEquals(frames * 48000.0 / 44100, take.frames.toDouble(), 3.0)
        val x = take.channel(0)
        assertEquals(0.707 * amp, rms(x, 24000, 48000), 0.01 * 0.707 * amp)
        val (level, rest) = analyze(x, 1000.0)
        assertEquals(amp, level, 0.001)
        assertTrue("residual $rest", rest < 1e-4)
    }

    @Test
    fun twentyKilohertzAt44kDoesNotAlias() {
        val frames = 44100 * 2
        val take = readTake(import(
            riff(fmt(3, 1, 44100, 32), Chunk("data", float32(tone(frames, 44100, sine(20000.0, 0.8))))),
            "high.wav"))
        val (level, rest) = analyze(take.channel(0), 20000.0)
        // 20 kHz sits at the top of the passband: kept, slightly rolled off.
        assertTrue("level $level", level > 0.4 && level < 0.81)
        // Its image (24.1 kHz, folding to 23.9 kHz) and anything else: below -80 dB.
        assertTrue("residual $rest", rest < 0.8 * 1e-4)
    }

    @Test
    fun pcm24At96kStereoIsHalvedWithBothChannelsIntact() {
        val frames = 96000 * 2 + 7
        val take = readTake(import(
            riff(fmt(1, 2, 96000, 24),
                Chunk("data", pcm24(tone(frames, 96000, sine(1000.0, 0.5), sine(3000.0, 0.3))))),
            "XV_0003.wav"))
        assertEquals(3, take.tag)
        assertEquals(2, take.channels)
        assertEquals(frames / 2.0, take.frames.toDouble(), 3.0)
        val (l, lRest) = analyze(take.channel(0), 1000.0)
        val (r, rRest) = analyze(take.channel(1), 3000.0)
        assertEquals(0.5, l, 0.001)
        assertEquals(0.3, r, 0.001)
        assertTrue("left residual $lRest", lRest < 1e-4)
        assertTrue("right residual $rRest", rRest < 1e-4)
    }

    @Test
    fun ultrasonicContentAt96kDoesNotFoldIntoTheAudibleBand() {
        // 30 kHz would land at 18 kHz if it folded.
        val frames = 96000 * 2
        val take = readTake(import(
            riff(fmt(3, 1, 96000, 32), Chunk("data", float32(tone(frames, 96000, sine(30000.0, 0.8))))),
            "bat.wav"))
        assertTrue(rms(take.channel(0), 24000, 48000) < 0.8 * 1e-4)
    }

    @Test
    fun namesAreSanitizedAndNeverReused() {
        val bytes = riff(fmt(1, 1, 48000, 16), Chunk("data", ByteArray(200)))
        assertEquals("import_My_take__1_.wav", import(bytes, "My take (1).wav").name)
        assertEquals("import_My_take__1__2.wav", import(bytes, "My take (1).wav").name)
        assertEquals("import_XV_0001.wav", import(bytes, "primary:XVIVE/XV_0001.wav").name)
    }

    @Test
    fun unsupportedWavThrowsAndLeavesNothing() {
        val eightBit = riff(fmt(1, 1, 48000, 8), Chunk("data", ByteArray(100) { 0x80.toByte() }))
        expectRejected { import(eightBit, "old.wav") }
        val sixChannels = riff(fmt(1, 6, 48000, 16), Chunk("data", ByteArray(1200)))
        expectRejected { import(sixChannels, "surround.wav") }
        val noData = riff(fmt(1, 1, 48000, 16), Chunk("data", ByteArray(0)))
        expectRejected { import(noData, "empty.wav") }
    }

    @Test
    fun nonAudioThrowsAndLeavesNothing() {
        expectRejected { import("just some text".toByteArray(), "notes.txt", "text/plain") }
        // Not a WAV, so it goes to the platform decoder, which cannot make sense
        // of it (and on the JVM is not there at all).
        expectRejected { import(ByteArray(4096) { (it * 31).toByte() }, "noise.bin", null) }
    }

    private fun expectRejected(block: () -> Unit) {
        try {
            block()
            fail("expected an ImportException")
        } catch (e: ImportException) {
            assertTrue(e.message!!, e.message!!.startsWith("Unsupported file: "))
        }
        assertFalse(audio.list()!!.joinToString(), audio.list()!!.isNotEmpty())
        assertFalse(scratch.list()!!.joinToString(), scratch.list()!!.isNotEmpty())
    }
}
