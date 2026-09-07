"""Prove the Kotlin prompt composer equals Lia Desktop's, on a real store.

Runs the desktop's own `VocabStore.compose_prompt(600)` over the local
vocabulary.json, writes the result to a temp file, then runs the Android
`PromptParityTest` against the same inputs. Neither the vocabulary nor the
prompt is printed or committed - only lengths and a pass/fail.

    python tools/prompt_parity.py
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import lia_keys  # noqa: E402

DESKTOP = os.environ.get("LIA_DESKTOP_DIR", lia_keys.DEFAULT_DESKTOP_DIR)


def desktop_prompt(path: str) -> str:
    sys.path.insert(0, DESKTOP)
    import vocab_learn  # type: ignore

    store = vocab_learn.VocabStore(path)
    return store.compose_prompt(600)


def main() -> int:
    appdata = os.environ.get("APPDATA") or os.path.expanduser("~")
    vocab = os.environ.get("LIA_VOCAB_FILE") or os.path.join(appdata, "Lia", "vocabulary.json")
    if not os.path.exists(vocab):
        print(f"no vocabulary.json at {vocab}", file=sys.stderr)
        return 2

    prompt = desktop_prompt(vocab)
    print(f"desktop compose_prompt(600): {len(prompt)} chars, "
          f"{prompt.count(',') + 1 if prompt else 0} terms")

    with tempfile.NamedTemporaryFile(
        "w", suffix=".txt", delete=False, encoding="utf-8"
    ) as fh:
        fh.write(prompt)
        expected = fh.name

    env = dict(os.environ)
    env["LIA_VOCAB_FILE"] = vocab
    env["LIA_EXPECTED_PROMPT"] = expected
    gradlew = os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
    result = subprocess.run(
        [gradlew, ":app:testDebugUnitTest", "--tests", "app.lia.android.PromptParityTest",
         "--rerun-tasks", "--no-daemon"],
        cwd=ROOT,
        env=env,
        capture_output=True,
        text=True,
    )
    os.unlink(expected)
    tail = (result.stdout or "") + (result.stderr or "")
    for line in tail.splitlines():
        if any(k in line for k in ("BUILD", "FAILED", "PromptParityTest")):
            print(line)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
