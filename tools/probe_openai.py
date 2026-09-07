"""Phase 0 spike: OpenAI transcription (plan 3.3), gpt-transcribe and whisper-1."""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import audio_util as au  # noqa: E402
import cloud_lib as cl  # noqa: E402
import lia_keys  # noqa: E402

VOCAB = ["Kubernetes", "React", "git push", "ביטולים", "פגישה"]
MODELS = ["gpt-transcribe", "whisper-1"]


def main() -> int:
    key = lia_keys.openai_key()
    if not key:
        print("no OpenAI key", file=sys.stderr)
        return 2
    ok, msg = cl.key_check("openai", key)
    print(f"key check: {ok} ({msg})")
    with open(os.path.join(ROOT, "fixtures", "clips.json"), encoding="utf-8") as fh:
        clips = json.load(fh)
    results = []
    for model in MODELS:
        for clip in clips:
            audio = au.read_wav_float(os.path.join(ROOT, "fixtures", clip["name"] + ".wav"))
            try:
                text, elapsed, shape, raw = cl.openai_transcribe(key, audio, model, "he", VOCAB)
            except Exception as exc:
                print(f"- {model} / {clip['name']}: !! {exc}")
                results.append({"model": model, "clip": clip["name"], "error": str(exc)})
                continue
            print(f"- {model} / {clip['name']} ({elapsed:.2f}s): {text[:80]}")
            results.append(
                {
                    "model": model,
                    "clip": clip["name"],
                    "elapsed_s": round(elapsed, 2),
                    "response_format": shape["multipart_fields"]["response_format"],
                    "prompt_len": len(shape["multipart_fields"].get("prompt", "")),
                    "request": shape,
                    "response": raw,
                    "text": text,
                }
            )
    cl.dump(
        os.path.join(ROOT, "fixtures", "openai_probe.json"),
        {"backend": "openai", "models": MODELS, "key_check": [ok, msg], "results": results},
    )
    good = sum(1 for r in results if r.get("text"))
    print(f"{good}/{len(results)} calls transcribed")
    return 0 if good == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
