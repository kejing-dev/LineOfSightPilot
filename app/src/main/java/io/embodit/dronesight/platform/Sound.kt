package io.embodit.dronesight.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import io.embodit.dronesight.domain.Touchdown

/**
 * The app's sounds (design audio): offline-rendered WAVs in assets/sfx (tools/render_audio.py; rotor peak -6 dBFS, cues
 * -3 dBFS). The rotor is one seamless 1 s loop whose rate follows the throttle (pitch 170-290 Hz) and whose gain follows
 * it too (40-100 %). Master volume 0-100 % (default 60) and the mute switch on the panel (D15) scale everything.
 */
class Sound(private val context: Context) {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private var rotorStream = 0
    private var master = 0.6f
    private var muted = false
    private var rotorGain = -1f
    private var rotorRate = -1f

    fun start() {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = p
        for (n in listOf("rotor", "ding", "tick", "beep", "land", "thud", "spinup")) {
            runCatching { context.assets.openFd("sfx/$n.wav").use { fd -> ids[n] = p.load(fd, 1) } }.onFailure { DsLog.w("SOUND load $n failed: $it") }
        }
        DsLog.i("SOUND ready ${ids.keys}")
    }

    fun release() {
        runCatching { pool?.release() }
        pool = null
        ids.clear()
        rotorStream = 0
    }

    fun setVolume(percent: Int, mute: Boolean) {
        master = (percent.coerceIn(0, 100) / 100f)
        muted = mute
        rotorGain = -1f
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

    fun spinUp() = play("spinup", 0.8f)

    fun takeoff() = Unit

    fun landed(kind: Touchdown) = play(if (kind == Touchdown.SOFT) "land" else "thud", if (kind == Touchdown.SOFT) 0.8f else 0.7f)

    fun crash() = play("thud", 1f)

    /**
     * Every frame: [level] 0 (off) .. 1 (full), [throttle] 0..1. Paused flights run at 30 % (design S15).
     */
    fun rotor(level: Float, throttle: Float) {
        val p = pool ?: return
        val id = ids["rotor"] ?: return
        val g = if (level <= 0.02f) 0f else gain * level * (0.4f + 0.6f * throttle.coerceIn(0f, 1f))
        val r = (0.85f + 0.6f * throttle.coerceIn(0f, 1f)).coerceIn(0.5f, 2f)
        if (g <= 0f) {
            if (rotorStream != 0) {
                p.stop(rotorStream)
                rotorStream = 0
                rotorGain = -1f
            }
            return
        }
        if (rotorStream == 0) {
            rotorStream = p.play(id, g, g, 2, -1, r)
            rotorGain = g
            rotorRate = r
            return
        }
        if (kotlin.math.abs(g - rotorGain) > 0.01f) {
            p.setVolume(rotorStream, g, g)
            rotorGain = g
        }
        if (kotlin.math.abs(r - rotorRate) > 0.01f) {
            p.setRate(rotorStream, r)
            rotorRate = r
        }
    }
}
