package io.embodit.dronesight.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.embodit.dronesight.domain.RotorSynth

/**
 * Plays [RotorSynth] on its own thread through a streaming AudioTrack (24 kHz stereo float, 20 ms blocks). The frame
 * thread only writes the synth's targets; the track is paused while the rotors are silent and resumed when they spin.
 * An underrun doubles the block (up to 80 ms), logged as `SOUND rotor underrun`. [release] ends the thread.
 */
class RotorAudio {
    val synth = RotorSynth()
    @Volatile private var running = false
    private var thread: Thread? = null
    private val lock = Object()

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "rotor-audio").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    /** Wakes the thread when the rotors start (the frame thread calls this after raising the level). */
    fun poke() {
        synchronized(lock) { lock.notifyAll() }
    }

    fun release() {
        running = false
        poke()
        runCatching { thread?.join(500) }
        thread = null
    }

    private fun loop() {
        val sr = synth.sampleRate
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track =
            runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(maxOf(min, sr / 10 * 2 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrElse {
                DsLog.w("SOUND rotor track failed: $it")
                running = false
                return
            }
        var frames = sr / 50
        var buf = FloatArray(frames * 2)
        var underruns = 0
        var playing = false
        DsLog.i("SOUND rotor synth sr=$sr block=$frames min=$min")
        try {
            while (running) {
                if (synth.silent) {
                    if (playing) {
                        // let the last block drain, then stop pulling the CPU
                        track.pause()
                        track.flush()
                        playing = false
                    }
                    synchronized(lock) { if (running && synth.silent) lock.wait(250) }
                    continue
                }
                if (!playing) {
                    track.play()
                    playing = true
                }
                synth.render(buf)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                val u = track.underrunCount
                if (u > underruns) {
                    underruns = u
                    if (frames < sr / 12) {
                        frames *= 2
                        buf = FloatArray(frames * 2)
                    }
                    DsLog.w("SOUND rotor underrun n=$u block=$frames")
                }
            }
        } catch (t: Throwable) {
            DsLog.w("SOUND rotor thread: $t")
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }
}
