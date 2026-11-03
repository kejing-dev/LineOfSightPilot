#!/usr/bin/env python3
"""Drone Sight sounds (design audio): rendered offline, bundled as app/src/main/assets/sfx/*.wav, and copied with a
10 s rotor sample + spectrograms + peak / RMS table to the factory's apps/drone-sight/qa/ (design audio.deliverable).

    python3 tools/render_audio.py [QA_DIR]

rotor      v0.1.2: synthesised live in the app (domain/RotorSynth.kt), no WAV. RotorSynthTest renders the 10 s QA
           sample with the app's own code to app/build/outputs/rotor/rotor_10s.wav; this script copies it to QA_DIR
           with a spectrogram (run `tools/dev.sh test` first). The v0.1.1 loop is still rendered as rotor_10s_v011.wav
           for an A/B listen.
ding.wav   in-box / gate / ring (two soft partials), -3 dBFS
tick.wav   out of the box / wrong gate (short wood tick), -3 dBFS
beep.wav   out of the field (two short beeps), -3 dBFS
land.wav   soft landing "click", -3 dBFS
thud.wav   hard landing (low thump), -3 dBFS
(spinup.wav is gone in v0.1.2: the synth spins up with the flight model's rotor level.)
"""
import os
import sys
import wave

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sfx")
SR = 22050
rng = np.random.default_rng(7)


def db(x):
    return 20 * np.log10(max(x, 1e-9))


def norm(x, peak_db):
    x = np.asarray(x, dtype=np.float64)
    p = np.max(np.abs(x)) or 1.0
    return x / p * (10 ** (peak_db / 20))


def write(path, x):
    pcm = np.clip(np.round(x * 32767), -32767, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def rotor_v011():
    """The v0.1.1 rotor loop (for the A/B sample only)."""
    n = SR  # 1 s loop: bin k = k Hz
    spec = np.zeros(n // 2 + 1, dtype=np.complex128)
    f0 = 200
    for h, a in [(1, 1.0), (2, 0.55), (3, 0.32), (4, 0.18), (5, 0.10), (6, 0.06)]:
        k = f0 * h
        spec[k] = a * n / 2 * np.exp(1j * rng.uniform(0, 2 * np.pi))
        # a little spread: neighbouring bins make it breathe instead of a pure organ tone
        for dk, b in [(-1, 0.25), (1, 0.25), (-2, 0.08), (2, 0.08)]:
            spec[k + dk] += a * b * n / 2 * np.exp(1j * rng.uniform(0, 2 * np.pi))
    # band noise 300-3000 Hz (air), rolled off
    k = np.arange(len(spec))
    shape = np.where((k > 150) & (k < 4000), 1.0 / (1 + ((k - 900) / 1200.0) ** 2), 0.0)
    spec += shape * 0.06 * n / 2 * np.exp(1j * rng.uniform(0, 2 * np.pi, len(spec)))
    x = np.fft.irfft(spec, n)
    # slow amplitude flutter of 4 Hz (4 whole cycles per loop): the props beating
    t = np.arange(n) / SR
    x *= 1 + 0.12 * np.sin(2 * np.pi * 4 * t)
    return norm(x, -6)


def env(n, a, d):
    t = np.arange(n) / SR
    return (1 - np.exp(-t / a)) * np.exp(-t / d)


def ding():
    n = int(SR * 0.6)
    t = np.arange(n) / SR
    x = np.sin(2 * np.pi * 1046.5 * t) * env(n, 0.004, 0.18) + 0.5 * np.sin(2 * np.pi * 1568 * t) * env(n, 0.004, 0.12)
    return norm(x, -3)


def tick():
    n = int(SR * 0.12)
    t = np.arange(n) / SR
    x = (np.sin(2 * np.pi * 620 * t) + 0.4 * rng.standard_normal(n) * 0.3) * env(n, 0.001, 0.025)
    return norm(x, -3)


def beep():
    n = int(SR * 0.5)
    t = np.arange(n) / SR
    gate = ((t < 0.14) | ((t > 0.22) & (t < 0.36))).astype(float)
    # soft edges
    k = int(SR * 0.006)
    g = np.convolve(gate, np.ones(k) / k, mode="same")
    x = np.sin(2 * np.pi * 880 * t) * g
    return norm(x, -3)


def land():
    n = int(SR * 0.2)
    t = np.arange(n) / SR
    x = (np.sin(2 * np.pi * 300 * t) * 0.6 + rng.standard_normal(n) * 0.4) * env(n, 0.0008, 0.03)
    return norm(x, -3)


def thud():
    n = int(SR * 0.5)
    t = np.arange(n) / SR
    f = 110 - 50 * t / 0.5
    ph = 2 * np.pi * np.cumsum(f) / SR
    x = (np.sin(ph) + 0.25 * np.sin(2 * ph) + 0.3 * rng.standard_normal(n) * np.exp(-t / 0.02)) * env(n, 0.002, 0.12)
    return norm(x, -3)


def spinup():
    n = int(SR * 0.8)
    t = np.arange(n) / SR
    f = 60 + 140 * (t / 0.8) ** 0.7
    ph = 2 * np.pi * np.cumsum(f) / SR
    x = (np.sin(ph) + 0.5 * np.sin(2 * ph) + 0.25 * np.sin(3 * ph)) * (t / 0.8) ** 1.2
    return norm(x, -6)


def stats(x):
    return db(np.max(np.abs(x))), db(np.sqrt(np.mean(x ** 2)))


def spectrogram(x, path, title, sr=SR):
    from PIL import Image, ImageDraw

    win = 1024
    hop = 256
    frames = max(1, (len(x) - win) // hop)
    w = np.hanning(win)
    cols = []
    for i in range(frames):
        seg = x[i * hop: i * hop + win]
        if len(seg) < win:
            break
        s = np.abs(np.fft.rfft(seg * w))
        cols.append(20 * np.log10(s + 1e-6))
    m = np.array(cols).T[: int(400 * SR / sr)]  # 0..~8.6 kHz
    m = np.clip((m - m.max() + 80) / 80, 0, 1)
    img = Image.fromarray((255 * m[::-1]).astype(np.uint8)).resize((800, 400))
    d = ImageDraw.Draw(img)
    d.text((8, 8), title, fill=255)
    img.save(path)


def main():
    qa = sys.argv[1] if len(sys.argv) > 1 else None
    os.makedirs(OUT, exist_ok=True)
    # rendered first, as in v0.1.1: keeps the random stream (so tick / land / thud) byte-identical
    old_rotor = rotor_v011()
    sounds = {"ding": ding(), "tick": tick(), "beep": beep(), "land": land(), "thud": thud()}
    for gone in ("rotor", "spinup"):
        old = os.path.join(OUT, f"{gone}.wav")
        if os.path.exists(old):
            os.remove(old)
    rows = []
    for name, x in sounds.items():
        write(os.path.join(OUT, f"{name}.wav"), x)
        p, r = stats(x)
        rows.append((name, len(x) / SR, p, r))
    if qa:
        os.makedirs(qa, exist_ok=True)
        # v0.1.1 for comparison: the 1 s loop rate-shifted by the throttle 0.4 -> 1.0 -> 0.5, gain 40-100 % x 60 %
        r = old_rotor
        n = SR * 10
        t = np.arange(n) / SR
        thr = np.interp(t, [0, 4, 7, 10], [0.4, 1.0, 1.0, 0.5])
        rate = 0.85 + 0.6 * (thr - 0.4) / 0.6
        pos = np.cumsum(rate) % SR
        i0 = np.floor(pos).astype(int)
        frac = pos - i0
        y = r[i0] * (1 - frac) + r[(i0 + 1) % SR] * frac
        y *= (0.4 + 0.6 * (thr - 0.4) / 0.6) * 0.6
        write(os.path.join(qa, "rotor_10s_v011.wav"), y)
        p, rr = stats(y)
        rows.append(("rotor_10s_v011 (old loop, 60 % volume)", 10.0, p, rr))
        spectrogram(y, os.path.join(qa, "rotor_10s_v011_spectrogram.png"), "v0.1.1 rotor loop, throttle 0.4 -> 1.0 -> 0.5")
        # v0.1.2: the app's own synth, rendered by RotorSynthTest
        src = os.path.join(ROOT, "app", "build", "outputs", "rotor", "rotor_10s.wav")
        if os.path.exists(src):
            import shutil
            shutil.copyfile(src, os.path.join(qa, "rotor_10s.wav"))
            with wave.open(src, "rb") as w:
                sr2 = w.getframerate()
                st = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32767
            left = st[0::2]
            p, rr = stats(st)
            rows.append(("rotor_10s v0.1.2 synth (60 % volume, stereo)", len(left) / sr2, p, rr))
            spectrogram(left, os.path.join(qa, "rotor_10s_spectrogram.png"), "v0.1.2 rotor synth (left), throttle 0.2 -> 0.4 -> 1.0 -> 0.5, 1.5 -> 6 -> 2 m", sr2)
            note = os.path.join(ROOT, "app", "build", "outputs", "rotor", "rotor_10s.txt")
            rotor_note = open(note, encoding="utf-8").read() if os.path.exists(note) else ""
        else:
            print("no app/build/outputs/rotor/rotor_10s.wav: run tools/dev.sh test first")
            rotor_note = ""
        cues = np.concatenate([sounds[k] for k in ("ding", "tick", "beep", "land", "thud")])
        write(os.path.join(qa, "cues.wav"), cues)
        spectrogram(cues, os.path.join(qa, "cues_spectrogram.png"), "ding / tick / beep / land / thud")
        with open(os.path.join(qa, "audio-levels.md"), "w", encoding="utf-8") as f:
            f.write("| 声音 | 时长 s | 峰值 dBFS | RMS dBFS |\n|---|---|---|---|\n")
            for name, dur, p, rr in rows:
                f.write(f"| {name} | {dur:.2f} | {p:.1f} | {rr:.1f} |\n")
            f.write("\nv0.1.2 螺旋桨声（RotorSynthTest 实测，应用同一份代码）：\n\n```\n" + rotor_note + "```\n")
    for name, dur, p, rr in rows:
        print(f"{name:40s} {dur:5.2f}s peak {p:6.1f} dBFS rms {rr:6.1f} dBFS")


if __name__ == "__main__":
    main()
