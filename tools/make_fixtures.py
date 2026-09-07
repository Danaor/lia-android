"""Produce the Phase 0 fixture clips as 16 kHz mono PCM16 WAV.

Fixtures land in a PUBLIC repo, so every sentence here is generic: no names,
no client data, no private content.

Speech source, in order of preference:
  1. Gemini TTS (Hebrew voices) - needs GEMINI_API_KEY or the desktop config.
  2. Windows SAPI (English only on this machine) - fallback, marked as such.

Usage:  python tools/make_fixtures.py [outdir]
"""

from __future__ import annotations

import base64
import json
import os
import struct
import subprocess
import sys
import wave

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lia_keys  # noqa: E402

TTS_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent"

# (name, language, seconds target, text)
CLIPS = [
    ("he_short", "he", 3, "שלום, בדיקה של המערכת."),
    (
        "he_medium",
        "he",
        8,
        "אני מקליט עכשיו הודעה קצרה כדי לבדוק את איכות התמלול של המערכת החדשה.",
    ),
    (
        "he_long",
        "he",
        15,
        "בדיקת תמלול ארוכה יותר. אני מדבר כמה משפטים ברצף, בלי הפסקות גדולות, "
        "כדי לראות איך המערכת מתמודדת עם קטע רציף. האם הפיסוק נשמר? נראה שכן.",
    ),
    (
        "he_en_mixed",
        "he",
        45,
        "אני רוצה לבדוק את הפיצול לקטעים. "
        "The recording should be split into several pieces before it is sent. "
        "כל קטע נשלח בחיבור נפרד לשרת, ואז מחברים את הטקסט בחזרה. "
        "This sentence is in English so we can see how the router handles mixed speech. "
        "אחר כך אנחנו בודקים שהמילים בגבול בין הקטעים לא נחתכות. "
        "The splitter cuts at the quietest frame inside the window. "
        "אם אין שקט מספיק, הוא חותך בכוח ומתחיל את הקטע הבא שנייה אחורה. "
        "That overlap means a boundary word appears whole in the next piece. "
        "לסיום, אנחנו בודקים שהתמלול המלא קריא והגיוני. תודה.",
    ),
]

VOICES = {"he": "Kore", "en": "Puck"}


def _write_wav(path: str, pcm16: bytes, rate: int = 16000) -> None:
    with wave.open(path, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        wf.writeframes(pcm16)


def _resample_pcm16(pcm: bytes, src_rate: int, dst_rate: int = 16000) -> bytes:
    if src_rate == dst_rate:
        return pcm
    n = len(pcm) // 2
    src = struct.unpack("<%dh" % n, pcm[: n * 2])
    ratio = src_rate / float(dst_rate)
    out_n = int(n / ratio)
    out = []
    for i in range(out_n):
        pos = i * ratio
        i0 = int(pos)
        i1 = min(i0 + 1, n - 1)
        frac = pos - i0
        out.append(int(src[i0] * (1 - frac) + src[i1] * frac))
    return struct.pack("<%dh" % out_n, *out)


def gemini_tts(text: str, voice: str) -> bytes | None:
    """Return 16 kHz mono PCM16 bytes, or None when TTS is unavailable."""
    import requests

    key = lia_keys.gemini_key()
    if not key:
        return None
    body = {
        "contents": [{"parts": [{"text": "Read this aloud, exactly as written: " + text}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {
                "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}
            },
        },
    }
    try:
        r = requests.post(
            TTS_URL,
            headers={"x-goog-api-key": key, "Content-Type": "application/json"},
            json=body,
            timeout=180,
        )
    except Exception as exc:
        print(f"  ! TTS request failed: {exc}")
        return None
    if r.status_code != 200:
        print(f"  ! TTS HTTP {r.status_code}: {r.text[:300]}")
        return None
    data = r.json()
    try:
        part = data["candidates"][0]["content"]["parts"][0]["inlineData"]
    except Exception:
        print(f"  ! unexpected TTS response: {json.dumps(data)[:300]}")
        return None
    pcm = base64.b64decode(part["data"])
    mime = part.get("mimeType", "")
    rate = 24000
    for token in mime.split(";"):
        token = token.strip()
        if token.startswith("rate="):
            rate = int(token.split("=", 1)[1])
    return _resample_pcm16(pcm, rate, 16000)


def sapi_tts(text: str, out_wav: str) -> bool:
    """English fallback via Windows SAPI. Returns True on success."""
    ps = (
        "Add-Type -AssemblyName System.Speech; "
        "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
        f"$s.SetOutputToWaveFile('{out_wav}'); "
        f"$s.Speak(@'\n{text}\n'@); $s.Dispose()"
    )
    try:
        subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
            check=True,
            capture_output=True,
            timeout=180,
        )
    except Exception as exc:
        print(f"  ! SAPI failed: {exc}")
        return False
    return os.path.exists(out_wav)


def main() -> int:
    outdir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "fixtures"
    )
    os.makedirs(outdir, exist_ok=True)
    only = os.environ.get("ONLY_CLIP", "")
    manifest = []
    if os.path.exists(os.path.join(outdir, "clips.json")):
        with open(os.path.join(outdir, "clips.json"), encoding="utf-8") as fh:
            manifest = [m for m in json.load(fh) if not only or m["name"] != only]
    for name, lang, want_s, text in CLIPS:
        if only and name != only:
            continue
        path = os.path.join(outdir, f"{name}.wav")
        print(f"- {name} ({lang}, target ~{want_s}s)")
        pcm = gemini_tts(text, VOICES.get(lang, "Kore"))
        source = "gemini-tts"
        if pcm is None:
            print("  falling back to Windows SAPI (English voice)")
            if not sapi_tts(text, path):
                print("  !! no speech source available - skipping")
                continue
            source = "sapi"
            with wave.open(path, "rb") as wf:
                raw = wf.readframes(wf.getnframes())
                rate = wf.getframerate()
                ch = wf.getnchannels()
            if ch == 2:
                n = len(raw) // 4
                s = struct.unpack("<%dh" % (n * 2), raw[: n * 4])
                raw = struct.pack("<%dh" % n, *[(s[2 * i] + s[2 * i + 1]) // 2 for i in range(n)])
            pcm = _resample_pcm16(raw, rate, 16000)
        _write_wav(path, pcm)
        secs = len(pcm) / 2 / 16000.0
        print(f"  wrote {path}  ({secs:.2f}s, {source})")
        manifest.append(
            {"name": name, "language": lang, "seconds": round(secs, 2),
             "source": source, "text": text}
        )
    order = {c[0]: i for i, c in enumerate(CLIPS)}
    manifest.sort(key=lambda m: order.get(m["name"], 99))
    with open(os.path.join(outdir, "clips.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)
    print(f"\n{len(manifest)}/{len(CLIPS)} clips written to {outdir}")
    return 0 if len(manifest) == len(CLIPS) else 1


if __name__ == "__main__":
    raise SystemExit(main())
