"""Reference cloud-backend calls (plan sections 3.2 - 3.4).

Every request shape here is the one the Kotlin backends must send. The probe
scripts dump the request shape (minus the key) and the raw response next to the
fixtures so the JVM parser tests can replay them offline.
"""

from __future__ import annotations

import base64
import json
import os
import sys
import time

import numpy as np
import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio_util as au  # noqa: E402

GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
OPENAI_URL = "https://api.openai.com/v1/audio/transcriptions"
GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"

GROQ_MODEL = "whisper-large-v3-turbo"
OPENAI_MODELS = ["gpt-transcribe", "gpt-4o-transcribe", "gpt-4o-mini-transcribe", "whisper-1"]
GEMINI_MODEL = "gemini-3.5-transcribe"

BIAS_SENTENCE = (
    "Bilingual transcription in Hebrew or English only. "
    "שלום, תודה רבה, איך הולך, מחשב, פגישה. "
    "Hello, thank you, how are you, meeting, computer, project."
)

VERBATIM_INSTRUCTION = (
    "Transcribe the audio verbatim in the language actually spoken. Do NOT "
    "translate. Do NOT summarise. If Hebrew is spoken, output Hebrew. If "
    "English is spoken, output English. If both languages are mixed in one "
    "utterance, preserve each phrase in its original language. Output every "
    "word the speaker said, including filler words and repetitions. CRITICAL: "
    "If the audio contains no actual speech - only silence, background noise, "
    "breathing, mouse clicks, keyboard sounds, fans, or static - output an "
    "empty string. Do NOT invent words to fill the void. An empty "
    "transcription is the correct output for an audio clip that does not "
    "contain speech."
)


def is_gpt_family(model: str) -> bool:
    return model.startswith("gpt-")


def build_prompt(model: str, vocab_terms: list[str], language: str | None) -> str:
    """Prompt exactly as the plan's 5.1 table describes."""
    parts = []
    if is_gpt_family(model):
        parts.append(VERBATIM_INSTRUCTION)
    if vocab_terms:
        parts.append("Common terms: " + ", ".join(vocab_terms) + ".")
    if language is None:  # auto-language calls only
        parts.append(BIAS_SENTENCE)
    return " ".join(parts).strip()


def _multipart(
    url: str,
    key: str,
    audio: np.ndarray,
    model: str,
    response_format: str,
    language: str | None,
    prompt: str,
) -> tuple[dict, float, dict]:
    files = {"file": ("audio.wav", au.wav_bytes(audio), "audio/wav")}
    data = {"model": model, "response_format": response_format}
    if language:
        data["language"] = language
    if prompt:
        data["prompt"] = prompt
    t0 = time.time()
    r = requests.post(
        url, headers={"Authorization": f"Bearer {key}"}, files=files, data=data, timeout=300
    )
    elapsed = time.time() - t0
    shape = {
        "url": url,
        "headers": {"Authorization": "Bearer <KEY>"},
        "multipart_fields": dict(data),
        "file": {"name": "audio.wav", "mime": "audio/wav",
                 "bytes": len(files["file"][1]), "format": "16 kHz mono PCM16 WAV"},
    }
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}: {r.text[:400]}")
    try:
        body = r.json()
    except ValueError:
        body = {"text": r.text}
    return body, elapsed, shape


def groq_transcribe(
    key: str, audio: np.ndarray, language: str | None = "he", vocab: list[str] | None = None
):
    """3.2 - Groq. Trim the silent tail first (Whisper family hallucinates)."""
    clip = au.trim_trailing_silence(audio)
    prompt = build_prompt(GROQ_MODEL, vocab or [], language) if au.bias_ok(audio) else ""
    body, elapsed, shape = _multipart(
        GROQ_URL, key, clip, GROQ_MODEL, "verbose_json", language, prompt
    )
    return (body.get("text") or "").strip(), elapsed, shape, body


def openai_transcribe(
    key: str,
    audio: np.ndarray,
    model: str = "gpt-transcribe",
    language: str | None = "he",
    vocab: list[str] | None = None,
):
    """3.3 - OpenAI. GPT family: json only, no trim. whisper-1: trim + text."""
    gpt = is_gpt_family(model)
    clip = audio if gpt else au.trim_trailing_silence(audio)
    prompt = build_prompt(model, vocab or [], language)
    if not au.bias_ok(audio):
        # The bias gate drops the vocabulary/bias part; the GPT verbatim
        # instruction is not vocabulary and always stays.
        prompt = VERBATIM_INSTRUCTION if gpt else ""
    fmt = "json" if gpt else "verbose_json"
    body, elapsed, shape = _multipart(OPENAI_URL, key, clip, model, fmt, language, prompt)
    return (body.get("text") or "").strip(), elapsed, shape, body


def gemini_transcribe(
    key: str, audio: np.ndarray, language: str | None = "he", vocab: list[str] | None = None
):
    """3.4 - Gemini 3.5 Transcribe through the Interactions API."""
    clip = au.trim_trailing_silence(audio)
    codes = {"he": ["he-IL"], "en": ["en-US"]}.get(language or "", ["he-IL", "en-US"])
    tconf = {"language_codes": codes, "mode": {"type": "verbatim"}}
    if vocab and au.bias_ok(audio):
        tconf["custom_vocabulary"] = vocab[:1000]
    body_req = {
        "model": GEMINI_MODEL,
        "input": [
            {
                "type": "audio",
                "data": base64.b64encode(au.wav_bytes(clip)).decode("ascii"),
                "mime_type": "audio/wav",
            }
        ],
        "generation_config": {"transcription_config": tconf},
    }
    shape = {
        "url": GEMINI_URL,
        "headers": {"x-goog-api-key": "<KEY>", "Content-Type": "application/json"},
        "body": {
            "model": GEMINI_MODEL,
            "input": [{"type": "audio", "data": "<base64 wav>", "mime_type": "audio/wav"}],
            "generation_config": {"transcription_config": tconf},
        },
    }
    delay = 20.0
    last = None
    t0 = time.time()
    for attempt in range(4):
        r = requests.post(
            GEMINI_URL,
            headers={"x-goog-api-key": key, "Content-Type": "application/json"},
            json=body_req,
            timeout=600,
        )
        if r.status_code == 200:
            data = r.json()
            return gemini_text(data), time.time() - t0, shape, data
        last = f"HTTP {r.status_code}: {r.text[:400]}"
        if r.status_code != 429 or attempt == 3:
            raise RuntimeError(_gemini_error(r) or last)
        wait = _retry_delay(r) or delay
        time.sleep(min(wait, 90.0))
        delay = min(delay * 1.6, 90.0)
    raise RuntimeError(last or "gemini failed")


def _retry_delay(r) -> float:
    try:
        for detail in r.json().get("error", {}).get("details", []) or []:
            if "retryDelay" in detail:
                return float(str(detail["retryDelay"]).rstrip("s"))
    except Exception:
        pass
    return 0.0


def _gemini_error(r) -> str:
    try:
        err = r.json().get("error", {})
        msg = err.get("message") or ""
        reason = err.get("status") or ""
        return f"{msg} [{reason}]" if msg else ""
    except Exception:
        return ""


def gemini_text(data: dict) -> str:
    """Concatenate every steps[].content[] item whose type == 'text'."""
    out = []
    for step in data.get("steps") or []:
        for item in step.get("content") or []:
            if item.get("type") == "text" and item.get("text"):
                out.append(item["text"].strip())
    return " ".join(out).strip()


def key_check(kind: str, key: str) -> tuple[bool, str]:
    """The Test button in Settings: a cheap authenticated GET."""
    urls = {
        "groq": ("https://api.groq.com/openai/v1/models", {"Authorization": f"Bearer {key}"}),
        "openai": ("https://api.openai.com/v1/models", {"Authorization": f"Bearer {key}"}),
        "gemini": (
            "https://generativelanguage.googleapis.com/v1beta/models",
            {"x-goog-api-key": key},
        ),
    }
    url, headers = urls[kind]
    try:
        r = requests.get(url, headers=headers, timeout=30)
    except Exception as exc:
        return False, f"{type(exc).__name__}: {exc}"
    return r.status_code == 200, f"HTTP {r.status_code}"


def dump(path: str, payload: dict) -> None:
    import lia_keys

    with open(path, "w", encoding="utf-8") as fh:
        fh.write(lia_keys.scrub(json.dumps(payload, ensure_ascii=False, indent=2)))
    print(f"wrote {path}")
