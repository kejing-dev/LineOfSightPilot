package io.embodit.dronesight.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import io.embodit.dronesight.domain.Touchdown

/**
 * The app's sounds (design audio). Cues are offline-rendered WAVs in assets/sfx (tools/render_audio.py, -3 dBFS) on a
 * SoundPool. The rotor is synthesised live ([RotorAudio] / [io.embodit.dronesight.domain.RotorSynth], v0.1.2, profile
 * B20): pitch follows the throttle, slow random drift, four detuned rotors, low-pass and distance gain. Master volume
 * 0-100 % (default 60) and the mute switch on the panel (D15) scale everything.
 */
class Sound(private val context: Context) {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private var rotorAudio: RotorAudio? = null
    private var master = 0.6f
    private var muted = false
    private var rotorLogMs = 0L

    fun start() {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = p
        rotorAudio = RotorAudio().also { it.start() }
        for (n in listOf("ding", "tick", "beep", "land", "thud")) {
            runCatching { context.assets.openFd("sfx/$n.wav").use { fd -> ids[n] = p.load(fd, 1) } }.onFailure { DsLog.w("SOUND load $n failed: $it") }
        }
        DsLog.i("SOUND ready ${ids.keys}")
    }

    fun release() {
        runCatching { pool?.release() }
        pool = null
        ids.clear()
        runCatching { rotorAudio?.release() }
        rotorAudio = null
    }

    fun setVolume(percent: Int, mute: Boolean) {
        master = (percent.coerceIn(0, 100) / 100f)
        muted = mute
        DsLog.i("SOUND volume=$percent muted=$mute")
    }

    private val gain: Float
        get() = if (muted) 0f else master

    private fun play(name: String, g: Float = 1f) {
        val p = pool ?: return
        val id = ids[name] ?: return
        val v = gain * g
        if (v <= 0f) return
        p.play(id, v, v, 1, 0, 1f)
    }

    fun ding() = play("ding", 0.8f)

    fun tick() = play("tick", 0.6f)

    fun beep() = play("beep", 0.7f)

    /** The synth spins the rotors up itself (pitch and level ramp with the flight model's rotor level). */
    fun spinUp() = Unit

    fun takeoff() = Unit

    fun landed(kind: Touchdown) = play(if (kind == Touchdown.SOFT) "land" else "thud", if (kind == Touchdown.SOFT) 0.8f else 0.7f)

    fun crash() = play("thud", 1f)

    /**
     * Every frame: [level] 0 (off) .. 1 (full), [throttle] 0..1, [distM] head to drone, [pan] -1 (left) .. 1 (right).
     * Paused flights run at 30 % (design S15).
     */
    fun rotor(level: Float, throttle: Float, distM: Float = 2f, pan: Float = 0f) {
        val ra = rotorAudio ?: return
        val tg = ra.synth.targets
        val wasOff = tg.level <= 0f
        tg.throttle = throttle
        tg.distM = distM
        tg.pan = pan
        tg.gain = gain
        tg.level = if (level <= 0.02f) 0f else level
        if (wasOff && tg.level > 0f) ra.poke()
        val now = android.os.SystemClock.elapsedRealtime()
        if (tg.level > 0f && now - rotorLogMs >= 2000) {
            rotorLogMs = now
            val sy = ra.synth
            DsLog.i(String.format(java.util.Locale.US, "SOUND rotor level=%.2f thr=%.2f dist=%.1f pan=%.2f base=%.0fHz drift=%+.1f%% voices=%s", level, throttle, distM, pan, sy.baseHz, sy.commonDrift * 100, sy.pitchHz.joinToString("/") { "%.0f".format(java.util.Locale.US, it) }))
        }
    }
}
