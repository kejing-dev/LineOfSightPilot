package io.embodit.dronesight.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The rotor sound (profile B20 "持续的机械声要有变化、要和谐", user v0.1.1 on the headset: 「螺旋桨声音有点单调噪音，加点随机
 * pitch，再和谐一点」). Pure Kotlin so the JVM test renders exactly what the app plays (RotorSynthTest writes the 10 s QA
 * sample). Replaces the v0.1.1 SoundPool loop (one 1 s WAV, rate-shifted: the same second of noise over and over).
 *
 *  - four rotor voices, each a soft harmonic stack (blade-pass f + 2f..6f falling off fast, plus a quiet shaft
 *    sub-harmonic f/2), no broadband hiss; a little filtered air noise only at high throttle;
 *  - pitch follows throttle (blade-pass 170-290 Hz) with motor inertia (80 ms), and spins down / up with [level];
 *  - random pitch: a slow common drift (±2.5 %, new target every 0.7-1.6 s, cosine-eased) plus a smaller drift per
 *    rotor (±0.8 %, every 0.4-1.0 s): never a wobble, never the same twice;
 *  - the four rotors are detuned −0.9 / −0.3 / +0.4 / +1.0 % (a few cents): a slow, gentle beating like a real quad;
 *  - slow amplitude modulation per rotor (0.17-0.31 Hz, ±10 %) and a common one (0.11 Hz, ±6 %);
 *  - two-pole low-pass 1.5-2.7 kHz (opens with throttle), closing with distance (air), inverse-distance gain from
 *    1.5 m (×0.2 at the far end of the field), equal-power stereo pan from the drone's bearing;
 *  - every parameter is ramped per sample; the output stays under −1 dBFS at full volume (test).
 *
 * Not thread-safe: the audio thread owns it; the frame thread only writes [Targets] fields.
 */
class RotorSynth(val sampleRate: Int = 24000, seed: Long = 450L) {
    /** What the frame thread asks for; read once per block by the audio thread. */
    class Targets {
        @Volatile var level = 0f
        @Volatile var throttle = 0f
        @Volatile var gain = 0f
        @Volatile var distM = 2f
        @Volatile var pan = 0f
    }

    val targets = Targets()

    private val rnd = Random(seed)
    private val dt = 1f / sampleRate

    // smoothed parameters
    private var level = 0f
    private var throttle = 0f
    private var gain = 0f
    private var dist = 2f
    private var pan = 0f

    private val voices = Array(VOICES) { i -> Voice(DETUNE[i], rnd.nextFloat() * TABLE, AM_HZ[i], rnd.nextFloat() * 2f * PI.toFloat()) }
    private val common = Drift(COMMON_DRIFT, 0.7f, 1.6f)
    private var t = 0f
    private var lp1L = 0f
    private var lp2L = 0f
    private var lp1R = 0f
    private var lp2R = 0f
    private var noiseLp = 0f
    private var noiseHp = 0f

    /** Last pitch of each voice (Hz), for the test and the log. */
    val pitchHz = FloatArray(VOICES)

    /** Last base blade-pass frequency (Hz) before the random drift and detune. */
    var baseHz = 0f
        private set

    /** Last common drift (fraction, ±[COMMON_DRIFT]). */
    var commonDrift = 0f
        private set

    /** True while the output is (almost) silent and stays so: the audio thread may pause its track. */
    val silent: Boolean
        get() = (level < 1e-4f && targets.level <= 0f) || (gain < 1e-4f && targets.gain <= 0f)

    /** Fills [out] with interleaved stereo float samples (frames = out.size / 2). */
    fun render(out: FloatArray) {
        val tg = targets
        val tLevel = tg.level.coerceIn(0f, 1f)
        val tThr = tg.throttle.coerceIn(0f, 1f)
        val tGain = tg.gain.coerceIn(0f, 1f)
        val tDist = tg.distM.coerceIn(0.3f, 30f)
        val tPan = tg.pan.coerceIn(-1f, 1f)
        val kThr = 1f - exp(-dt / 0.08f)
        val kLevel = 1f - exp(-dt / 0.12f)
        val kGain = 1f - exp(-dt / 0.05f)
        val kSlow = 1f - exp(-dt / 0.15f)
        var i = 0
        while (i < out.size) {
            throttle += (tThr - throttle) * kThr
            level += (tLevel - level) * kLevel
            gain += (tGain - gain) * kGain
            dist += (tDist - dist) * kSlow
            pan += (tPan - pan) * kSlow
            t += dt

            // pitch: throttle -> blade-pass, spun down below level 0.7 (spin-up, paused)
            val spin = 0.5f + 0.5f * min(level / 0.7f, 1f)
            val base = (MIN_HZ + (MAX_HZ - MIN_HZ) * throttle) * spin
            baseHz = base
            val c = common.step(dt, rnd)
            commonDrift = c
            var mix = 0f
            for (v in 0 until VOICES) {
                val vo = voices[v]
                val f = base * (1f + vo.detune + c + vo.drift.step(dt, rnd))
                pitchHz[v] = f
                // the table holds one shaft turn (two blade passes): advance at f / 2
                vo.phase += f * 0.5f * TABLE * dt
                if (vo.phase >= TABLE) vo.phase -= TABLE
                val p = vo.phase.toInt()
                val fr = vo.phase - p
                val s = WAVE[p] + (WAVE[(p + 1) and (TABLE - 1)] - WAVE[p]) * fr
                val am = 1f + 0.10f * sin(2f * PI.toFloat() * vo.amHz * t + vo.amPhase)
                mix += s * am
            }
            mix *= 0.25f * (1f + 0.06f * sin(2f * PI.toFloat() * 0.11f * t))
            // a little air (band 150-900 Hz noise), only as the throttle opens
            val w = rnd.nextFloat() * 2f - 1f
            noiseLp += (w - noiseLp) * NOISE_LP
            noiseHp += (noiseLp - noiseHp) * NOISE_HP
            mix += (noiseLp - noiseHp) * 0.10f * throttle * throttle

            // low-pass: opens with throttle, closes with distance; distance gain (inverse, ref 1.5 m)
            val air = (2.5f / dist).coerceIn(0.45f, 1f)
            val fc = (1500f + 1200f * throttle) * air
            val k = 1f - exp(-2f * PI.toFloat() * fc * dt)
            val dGain = (1.5f / dist).coerceIn(0.2f, 1f)
            val amp = mix * gain * min(level / 0.15f, 1f) * dGain * OUT
            // equal-power pan, kept narrow (a drone 90° to the side is still heard in both ears)
            val a = (PI.toFloat() / 4f) * (1f + 0.5f * pan)
            val l = amp * cos(a) * SQRT2
            val r = amp * sin(a) * SQRT2
            lp1L += (l - lp1L) * k
            lp2L += (lp1L - lp2L) * k
            lp1R += (r - lp1R) * k
            lp2R += (lp1R - lp2R) * k
            out[i] = soft(lp2L)
            out[i + 1] = soft(lp2R)
            i += 2
        }
    }

    private class Voice(val detune: Float, var phase: Float, val amHz: Float, val amPhase: Float) {
        val drift = Drift(VOICE_DRIFT, 0.4f, 1.0f)
    }

    /** Smooth random value in ±[amp]: a new random target every [minS]..[maxS] seconds, cosine-eased between. */
    private class Drift(val amp: Float, val minS: Float, val maxS: Float) {
        private var from = 0f
        private var to = 0f
        private var span = 1f
        private var at = 1f

        fun step(dt: Float, rnd: Random): Float {
            at += dt
            if (at >= span) {
                from = to
                to = (rnd.nextFloat() * 2f - 1f) * amp
                span = minS + rnd.nextFloat() * (maxS - minS)
                at = 0f
            }
            val e = 0.5f - 0.5f * cos(PI.toFloat() * at / span)
            return from + (to - from) * e
        }
    }

    companion object {
        const val VOICES = 4
        const val MIN_HZ = 170f
        const val MAX_HZ = 290f
        const val COMMON_DRIFT = 0.025f
        const val VOICE_DRIFT = 0.008f
        val DETUNE = floatArrayOf(-0.009f, -0.003f, 0.004f, 0.010f)
        private val AM_HZ = floatArrayOf(0.17f, 0.23f, 0.29f, 0.31f)
        private const val TABLE = 2048
        private const val OUT = 0.62f
        private const val SQRT2 = 1.4142135f
        private const val NOISE_LP = 0.21f // ~900 Hz at 24 kHz
        private const val NOISE_HP = 0.04f // ~150 Hz

        /**
         * One shaft turn: the shaft sub-harmonic (k = 1, 3) quiet, the blade-pass stack k = 2, 4, 6 … 12 falling off fast
         * (1, .45, .22, .12, .06, .03), normalised to peak 1.
         */
        private val WAVE: FloatArray = run {
            val parts = listOf(1 to 0.12, 2 to 1.0, 3 to 0.05, 4 to 0.45, 6 to 0.22, 8 to 0.12, 10 to 0.06, 12 to 0.03)
            val ph = listOf(0.0, 0.0, 1.3, 0.7, 2.1, 0.4, 1.7, 2.9)
            val w = DoubleArray(TABLE) { n ->
                var s = 0.0
                parts.forEachIndexed { j, (k, a) -> s += a * sin(2 * PI * k * n / TABLE + ph[j]) }
                s
            }
            val peak = w.maxOf { abs(it) }
            FloatArray(TABLE) { (w[it] / peak).toFloat() }
        }

        /** Soft limit above 0.85 (never reached at the levels above; a guard, not a sound). */
        private fun soft(x: Float): Float {
            val a = abs(x)
            if (a <= 0.85f) return x
            val y = 0.85f + 0.15f * (1f - exp(-(a - 0.85f) / 0.15f))
            return if (x < 0) -y else y
        }
    }
}
