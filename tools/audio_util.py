"""Reference implementations of Lia's audio pre-processing (plan section 3.5 / 4).

These mirror lia.py and are the yardstick the Kotlin unit tests are checked
against. Pure Python + numpy so a spike can run without Android.
"""

from __future__ import annotations

import math
import struct
import wave

import numpy as np

SILENCE_RMS = 0.005          # 4.1 dictation silence gate
MIN_DICTATION_S = 1.5        # 4.1
TRIM_THRESHOLD_DB = -45.0    # 4.2
TRIM_TAIL_MS = 500           # 4.2
GAIN_TARGET_RMS = 0.06       # 4.4
GAIN_MAX = 4.0               # 4.4
GAIN_NEAR_NOISE_RMS = 0.02   # 4.4
GAIN_NEAR_NOISE_MAX = 3.0    # 4.4
BIAS_MIN_SEC = 5.0           # 4.5
BIAS_MIN_RMS = 0.02          # 4.5

SERVER_CHUNK_THRESHOLD_S = 20.0
SERVER_CHUNK_TARGET_S = 13.0
SERVER_CHUNK_MAX_S = 19.0
SERVER_CHUNK_OVERLAP_S = 1.0

CLOUD_CHUNK_THRESHOLD_S = 390.0
CLOUD_CHUNK_TARGET_S = 330.0
CLOUD_CHUNK_MAX_S = 390.0
CLOUD_CHUNK_OVERLAP_S = 0.0

RATE = 16000


def read_wav_float(path: str) -> np.ndarray:
    with wave.open(path, "rb") as wf:
        assert wf.getsampwidth() == 2, "expected PCM16"
        rate = wf.getframerate()
        ch = wf.getnchannels()
        raw = wf.readframes(wf.getnframes())
    data = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    if ch > 1:
        data = data.reshape(-1, ch).mean(axis=1)
    if rate != RATE:
        data = resample_linear(data, rate, RATE)
    return data


def write_wav_float(path: str, audio: np.ndarray, rate: int = RATE) -> None:
    pcm = np.clip(audio, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2")
    with wave.open(path, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        wf.writeframes(pcm.tobytes())


def wav_bytes(audio: np.ndarray, rate: int = RATE) -> bytes:
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()
    hdr = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt "
    hdr += struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
    hdr += b"data" + struct.pack("<I", len(pcm))
    return hdr + pcm


def resample_linear(audio: np.ndarray, src: int, dst: int) -> np.ndarray:
    if src == dst:
        return audio
    n_out = int(len(audio) * dst / float(src))
    x = np.linspace(0, len(audio) - 1, n_out, dtype=np.float64)
    return np.interp(x, np.arange(len(audio)), audio).astype(np.float32)


def rms(audio: np.ndarray) -> float:
    if audio.size == 0:
        return 0.0
    return float(np.sqrt(np.mean(np.square(audio.astype(np.float64)))))


def peak_rms(audio: np.ndarray, window_s: float = 0.3) -> float:
    """4.1: highest RMS over a sliding window (default 300 ms)."""
    win = int(window_s * RATE)
    if audio.size <= win:
        return rms(audio)
    best = 0.0
    step = max(1, win // 2)
    for i in range(0, audio.size - win + 1, step):
        best = max(best, rms(audio[i : i + win]))
    return best


def trim_trailing_silence(
    audio: np.ndarray, threshold_db: float = TRIM_THRESHOLD_DB, tail_ms: int = TRIM_TAIL_MS
) -> np.ndarray:
    """4.2 - cut the quiet tail, keep tail_ms of it."""
    if audio.size == 0:
        return audio
    frame = int(0.02 * RATE)
    thresh = 10 ** (threshold_db / 20.0)
    last_loud = -1
    for i in range(0, audio.size - frame + 1, frame):
        if rms(audio[i : i + frame]) > thresh:
            last_loud = i + frame
    if last_loud < 0:
        return audio
    keep = min(audio.size, last_loud + int(tail_ms * RATE / 1000))
    return audio[:keep]


def apply_auto_gain(audio: np.ndarray) -> tuple[np.ndarray, float]:
    """4.4 - amplify quiet dictation, never clip, cap near-noise clips at 3x."""
    cur = rms(audio)
    if cur <= 0:
        return audio, 1.0
    gain = GAIN_TARGET_RMS / cur
    if gain <= 1.0:
        return audio, 1.0
    cap = GAIN_NEAR_NOISE_MAX if cur < GAIN_NEAR_NOISE_RMS else GAIN_MAX
    gain = min(gain, cap)
    peak = float(np.max(np.abs(audio))) or 1.0
    gain = min(gain, 0.97 / peak)
    if gain <= 1.0:
        return audio, 1.0
    return (audio * gain).astype(np.float32), gain


def bias_ok(audio: np.ndarray) -> bool:
    """4.5 - is this clip long and loud enough to carry a vocabulary prompt?"""
    return (audio.size / float(RATE)) >= BIAS_MIN_SEC and peak_rms(audio) >= BIAS_MIN_RMS


def split_at_silence(
    audio: np.ndarray,
    threshold_s: float,
    target_s: float,
    max_s: float,
    overlap_s: float,
) -> list[np.ndarray]:
    """3.5 - cut at the quietest 30 ms frame inside [target, max]."""
    total = audio.size / float(RATE)
    if total <= threshold_s:
        return [audio]
    frame = int(0.03 * RATE)
    pieces: list[np.ndarray] = []
    pos = 0
    n = audio.size
    while pos < n:
        remaining = (n - pos) / float(RATE)
        if remaining <= max_s:
            pieces.append(audio[pos:])
            break
        lo = pos + int(target_s * RATE)
        hi = min(n, pos + int(max_s * RATE))
        best_i, best_rms = -1, None
        i = lo
        while i + frame <= hi:
            r = rms(audio[i : i + frame])
            if best_rms is None or r < best_rms:
                best_rms, best_i = r, i
            i += frame
        if best_i < 0:
            cut = hi
            quiet = False
        else:
            cut = best_i + frame // 2
            quiet = best_rms is not None and best_rms < SILENCE_RMS
        pieces.append(audio[pos:cut])
        if quiet:
            pos = cut
        else:
            pos = max(pos + 1, cut - int(overlap_s * RATE))
    return [p for p in pieces if p.size > 0]


def split_for_server(audio: np.ndarray) -> list[np.ndarray]:
    return split_at_silence(
        audio,
        SERVER_CHUNK_THRESHOLD_S,
        SERVER_CHUNK_TARGET_S,
        SERVER_CHUNK_MAX_S,
        SERVER_CHUNK_OVERLAP_S,
    )


def split_for_cloud(audio: np.ndarray) -> list[np.ndarray]:
    return split_at_silence(
        audio,
        CLOUD_CHUNK_THRESHOLD_S,
        CLOUD_CHUNK_TARGET_S,
        CLOUD_CHUNK_MAX_S,
        CLOUD_CHUNK_OVERLAP_S,
    )


def db(x: float) -> float:
    return 20 * math.log10(x) if x > 0 else -120.0
