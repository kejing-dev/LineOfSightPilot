package io.embodit.dronesight.domain

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rotor synth (profile B20): pitch follows the throttle, the random drift stays inside ±2-4 % and moves slowly, the
 * four rotors are apart, distance makes it quieter, the output stays under −1 dBFS at full volume. Also renders the
 * 10 s QA sample exactly as the app plays it: app/build/outputs/rotor/rotor_10s.wav (tools/render_audio.py copies it to
 * the factory's apps/drone-sight/qa/ with its spectrogram).
 */
class RotorSynthTest {
    private val sr = 24000
    private val block = sr / 50

    /** 10 s script: spin-up, climb (throttle 0.4 -> 1.0), hold, ease off to 0.5, flying out 1.5 -> 6 m and back. */
    private fun script(t: Float, tg: RotorSynth.Targets) {
        tg.level = if (t < 0.3f) 0f else ((t - 0.3f) / 0.8f).coerceIn(0f, 1f)
        tg.throttle = interp(t, floatArrayOf(0f, 1.2f, 4f, 7f, 10f), floatArrayOf(0.2f, 0.4f, 1.0f, 1.0f, 0.5f))
        tg.distM = interp(t, floatArrayOf(0f, 3f, 6f, 9f, 10f), floatArrayOf(1.5f, 1.5f, 6f, 2f, 2f))
        tg.pan = interp(t, floatArrayOf(0f, 4f, 7f, 10f), floatArrayOf(0f, -0.6f, 0.6f, 0f))
        tg.gain = 0.6f
    }

    private fun interp(t: Float, xs: FloatArray, ys: FloatArray): Float {
        if (t <= xs[0]) return ys[0]
        for (i in 1 until xs.size) if (t <= xs[i]) return ys[i - 1] + (ys[i] - ys[i - 1]) * (t - xs[i - 1]) / (xs[i] - xs[i - 1])
        return ys.last()
    }

    @Test
    fun renderTenSecondsAndCheck() {
        val s = RotorSynth(sr)
        val out = FloatArray(sr * 10 * 2)
        val buf = FloatArray(block * 2)
        val drifts = ArrayList<Float>()
        val voiceDev = ArrayList<Float>()
        val bases = ArrayList<Pair<Float, Float>>()
        var spread = 0f
        var n = 0
        while (n < sr * 10) {
            val t = n.toFloat() / sr
            script(t, s.targets)
            s.render(buf)
            System.arraycopy(buf, 0, out, n * 2, buf.size)
            n += block
            if (t > 1.5f) {
                drifts += s.commonDrift
                for (v in 0 until RotorSynth.VOICES) voiceDev += s.pitchHz[v] / s.baseHz - 1f
                spread = max(spread, s.pitchHz.max() / s.pitchHz.min() - 1f)
                bases += s.targets.throttle to s.baseHz
            }
        }
        // random pitch inside ±2-4 % (common ±2.5 % + per rotor ±0.8 % + detune ≤ 1 %): never beyond 4.5 %
        val maxDev = voiceDev.maxOf { abs(it) }
        assertTrue("voice deviation $maxDev", maxDev <= 0.045f)
        // and it really moves: the common drift spans at least 2 % over the run
        val span = drifts.max() - drifts.min()
        assertTrue("drift span $span", span >= 0.02f)
        // slowly: no step above 0.15 % per 20 ms block (a wobble would be a few % per block)
        val step = drifts.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue("drift step per block $step", step <= 0.0015f)
        // the four rotors are apart (detune + drift): at least 0.6 % between highest and lowest at some point
        assertTrue("rotor spread $spread", spread >= 0.006f)
        // pitch follows the throttle: base at throttle 1.0 is ~290 Hz, at 0.5 ~230 Hz (after inertia)
        val hi = bases.filter { it.first > 0.98f }.map { it.second }.average()
        val lo = bases.filter { it.first < 0.55f }.map { it.second }.average()
        assertTrue("base hi=$hi lo=$lo", hi > 280 && lo in 225.0..245.0 && hi > lo * 1.15)

        // levels at the default 60 % volume
        val left = FloatArray(out.size / 2) { out[it * 2] }
        val peak = out.maxOf { abs(it) }
        assertTrue("peak ${dbfs(peak)}", dbfs(peak) <= -1.0)
        // near (1.5 m, 2-3 s) is louder than far (6 m, 5.6-6.4 s)
        val near = rms(left, 2.0f, 3.0f)
        val far = rms(left, 5.6f, 6.4f)
        assertTrue("near ${dbfs(near)} far ${dbfs(far)}", near > far * 2f)
        File("build/outputs/rotor").mkdirs()
        writeWav(File("build/outputs/rotor/rotor_10s.wav"), out, sr)
        File("build/outputs/rotor/rotor_10s.txt").writeText(
            String.format(
                java.util.Locale.US,
                "peak %.1f dBFS, rms %.1f dBFS (60 %% volume)\nnear 1.5 m rms %.1f dBFS, far 6 m rms %.1f dBFS\nmax voice deviation %.2f %%, common drift span %.2f %%, max step per 20 ms %.3f %%, rotor spread %.2f %%\nbase at throttle 1.0 %.0f Hz, at 0.5 %.0f Hz\n",
                dbfs(peak), dbfs(rms(left, 0f, 10f)), dbfs(near), dbfs(far), maxDev * 100, span * 100, step * 100, spread * 100, hi, lo,
            ),
        )
    }

    @Test
    fun fullVolumeCloseStaysUnderMinusOneDbfs() {
        val s = RotorSynth(sr)
        val buf = FloatArray(block * 2)
        var peak = 0f
        s.targets.apply { level = 1f; throttle = 1f; gain = 1f; distM = 0.5f; pan = 1f }
        repeat(50 * 8) {
            s.render(buf)
            peak = max(peak, buf.maxOf { abs(it) })
        }
        assertTrue("peak ${dbfs(peak)}", dbfs(peak) <= -1.0)
    }

    @Test
    fun silentWhenOffOrMuted() {
        val s = RotorSynth(sr)
        val buf = FloatArray(block * 2)
        s.targets.apply { level = 1f; throttle = 0.5f; gain = 1f }
        repeat(50) { s.render(buf) }
        s.targets.gain = 0f
        repeat(50) { s.render(buf) }
        assertTrue("muted peak ${buf.maxOf { abs(it) }}", buf.maxOf { abs(it) } < 1e-3f)
        assertTrue(s.silent)
    }

    private fun rms(x: FloatArray, a: Float, b: Float): Float {
        val i0 = (a * sr).toInt()
        val i1 = (b * sr).toInt().coerceAtMost(x.size)
        var acc = 0.0
        for (i in i0 until i1) acc += x[i].toDouble() * x[i]
        return sqrt(acc / (i1 - i0)).toFloat()
    }

    private fun dbfs(x: Float) = 20 * log10(max(x, 1e-9f).toDouble())

    private fun writeWav(f: File, interleaved: FloatArray, rate: Int) {
        val data = ByteArray(interleaved.size * 2)
        for (i in interleaved.indices) {
            val v = (interleaved[i].coerceIn(-1f, 1f) * 32767).toInt()
            data[i * 2] = (v and 0xff).toByte()
            data[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        RandomAccessFile(f, "rw").use { w ->
            w.setLength(0)
            fun i32(v: Int) = w.write(byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte(), (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte()))
            fun i16(v: Int) = w.write(byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte()))
            w.write("RIFF".toByteArray()); i32(36 + data.size); w.write("WAVE".toByteArray())
            w.write("fmt ".toByteArray()); i32(16); i16(1); i16(2); i32(rate); i32(rate * 4); i16(4); i16(16)
            w.write("data".toByteArray()); i32(data.size); w.write(data)
        }
    }
}
