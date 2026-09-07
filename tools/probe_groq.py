"""Phase 0 spike: Groq transcription (plan 3.2)."""
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


def main() -> int:
    key = lia_keys.groq_key()
    if not key:
        print("no Groq key", file=sys.stderr)
        return 2
    ok, msg = cl.key_check("groq", key)
    print(f"key check: {ok} ({msg})")
    with open(os.path.join(ROOT, "fixtures", "clips.json"), encoding="utf-8") as fh:
        clips = json.load(fh)
    results = []
    for clip in clips:
        audio = au.read_wav_float(os.path.join(ROOT, "fixtures", clip["name"] + ".wav"))
        try:
            text, elapsed, shape, raw = cl.groq_transcribe(key, audio, "he", VOCAB)
        except Exception as exc:
            print(f"- {clip['name']}: !! {exc}")
            results.append({"clip": clip["name"], "error": str(exc)})
            continue
        print(f"- {clip['name']} ({elapsed:.2f}s): {text[:90]}")
        results.append(
            {
                "clip": clip["name"],
                "elapsed_s": round(elapsed, 2),
                "bias_prompt_sent": bool(shape["multipart_fields"].get("prompt")),
                "request": shape,
                "response": raw,
                "text": text,
            }
        )
    cl.dump(
        os.path.join(ROOT, "fixtures", "groq_probe.json"),
        {"backend": "groq", "model": cl.GROQ_MODEL, "key_check": [ok, msg], "results": results},
    )
    good = sum(1 for r in results if r.get("text"))
    print(f"{good}/{len(results)} clips transcribed")
    return 0 if good == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
