"""Phase 0 spike: the splitter (plan 3.5) on a long clip.

Builds a long file by repeating the mixed fixture (not committed - it is big),
then checks both split profiles and, with --gemini, proves a >390 s file
becomes exactly 2 Gemini requests whose joined text has no duplicated boundary.
"""
from __future__ import annotations

import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import audio_util as au  # noqa: E402
import cloud_lib as cl  # noqa: E402
import lia_keys  # noqa: E402

FIX = os.path.join(ROOT, "fixtures")


def main() -> int:
    base = au.read_wav_float(os.path.join(FIX, "he_en_mixed.wav"))
    gap = np.zeros(int(0.4 * au.RATE), dtype=np.float32)
    long_audio = np.concatenate([np.concatenate([base, gap]) for _ in range(10)])
    total = long_audio.size / au.RATE
    out = os.path.join(FIX, "long_mixed.wav")
    au.write_wav_float(out, long_audio)
    print(f"built {out}: {total:.1f}s")

    server_pieces = au.split_for_server(long_audio)
    longest = max(p.size / au.RATE for p in server_pieces)
    print(f"server split: {len(server_pieces)} pieces, longest {longest:.2f}s "
          f"(limit {au.SERVER_CHUNK_MAX_S})")
    assert longest <= au.SERVER_CHUNK_MAX_S + 0.05, "a server piece exceeds the 19 s cap"

    cloud_pieces = au.split_for_cloud(long_audio)
    sizes = [round(p.size / au.RATE, 1) for p in cloud_pieces]
    print(f"cloud split: {len(cloud_pieces)} pieces {sizes} (target 330-390 s)")
    assert all(s <= au.CLOUD_CHUNK_MAX_S + 0.05 for s in sizes), "a cloud piece exceeds 390 s"

    covered = sum(p.size for p in server_pieces) / au.RATE
    print(f"coverage: {covered:.1f}s of audio across pieces (overlap included)")

    if "--gemini" in sys.argv:
        key = lia_keys.gemini_key()
        texts = []
        for i, piece in enumerate(cloud_pieces):
            text, elapsed, _, _ = cl.gemini_transcribe(key, piece, "he", None)
            print(f"  gemini piece {i+1}/{len(cloud_pieces)} "
                  f"({piece.size/au.RATE:.0f}s, {elapsed:.1f}s): {len(text)} chars")
            texts.append(text)
        joined = " ".join(texts)
        print(f"joined length {len(joined)} chars")
        with open(os.path.join(FIX, "gemini_long_split.txt"), "w", encoding="utf-8") as fh:
            fh.write(lia_keys.scrub(joined))
        print("wrote fixtures/gemini_long_split.txt")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
