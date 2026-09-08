"""Phase 0 spike: talk to the Lia Desktop transcription server (plan 3.1).

Runs the full handshake against the real home server for every fixture clip and
dumps every frame (direction, opcode, payload) to fixtures/server_<clip>.json
with the host and token replaced by placeholders. Also captures the 1008
unauthorized close with a deliberately wrong token.

    python tools/probe_server.py                # all clips
    python tools/probe_server.py he_short       # one clip
"""

from __future__ import annotations

import json
import os
import sys
import time
import uuid

import numpy as np
import websocket

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import audio_util as au  # noqa: E402
import lia_keys  # noqa: E402

FIXTURES = os.path.join(ROOT, "fixtures")
READY_TIMEOUT = 10.0
QUIET_TIMEOUT = 2.5
TOTAL_TIMEOUT = 15.0
END_OF_AUDIO = b"END_OF_AUDIO"


class Frame(dict):
    pass


def _log(frames: list, direction: str, opcode: str, payload) -> None:
    frames.append(
        {
            "t": round(time.time() - frames[0]["_t0"] if frames else 0.0, 3),
            "dir": direction,
            "opcode": opcode,
            "payload": payload,
        }
    )


def transcribe_piece(
    url: str, token: str, audio: np.ndarray, language: str = "he", frames: list | None = None
) -> tuple[str, list]:
    """One connection, one piece. Returns (text, frames)."""
    rec: list = []
    t0 = time.time()

    def note(direction, opcode, payload):
        rec.append(
            {"t": round(time.time() - t0, 3), "dir": direction, "opcode": opcode, "payload": payload}
        )

    header = [f"Authorization: Bearer {token}"] if token else []
    ws = websocket.create_connection(url, header=header, timeout=READY_TIMEOUT)
    uid = str(uuid.uuid4())
    hello = {
        "uid": uid,
        "language": language,
        "task": "transcribe",
        "model": "large-v3-turbo",
        "use_vad": True,
        "send_last_n_segments": 1000,
        "initial_prompt": None,
    }
    ws.send(json.dumps(hello))
    note("client->server", "text", hello)

    ready = False
    deadline = time.time() + READY_TIMEOUT
    while time.time() < deadline:
        ws.settimeout(max(0.2, deadline - time.time()))
        try:
            msg = ws.recv()
        except websocket.WebSocketTimeoutException:
            break
        if isinstance(msg, bytes):
            note("server->client", "binary", f"<{len(msg)} bytes, ignored>")
            continue
        data = json.loads(msg)
        note("server->client", "text", data)
        if data.get("uid") not in (None, uid):
            continue
        if data.get("message") == "SERVER_READY":
            ready = True
            break
        if data.get("status") == "ERROR":
            raise RuntimeError(f"server error: {data.get('message')}")
    if not ready:
        ws.close()
        raise RuntimeError("server not ready")

    pcm = np.clip(audio, -1.0, 1.0).astype("<f4").tobytes()
    step = 32000 * 4  # 2 s of float32 per frame
    for i in range(0, len(pcm), step):
        ws.send_binary(pcm[i : i + step])
    note("client->server", "binary", f"<float32 le pcm, {len(pcm)} bytes, {len(pcm)//4} samples>")
    ws.send_binary(END_OF_AUDIO)
    note("client->server", "binary", "END_OF_AUDIO")

    # The server re-sends the SAME stretch with a later `end` as more audio
    # arrives, so a segment is identified by its START and the newest text for
    # that start wins. Keying on (start, end) instead produced a dozen copies of
    # one growing sentence on a slow uplink (2026-09-08). The tolerance is there
    # because the same stretch is reported at 2.22 one moment and 2.24 the next.
    pieces: list[list] = []          # [start, end, text]
    start_tolerance = 0.25
    last_seg = time.time()
    start = time.time()
    while True:
        if time.time() - start > TOTAL_TIMEOUT:
            break
        if time.time() - last_seg > QUIET_TIMEOUT and segments:
            break
        ws.settimeout(0.5)
        try:
            msg = ws.recv()
        except websocket.WebSocketTimeoutException:
            continue
        except Exception:
            break
        if not msg:
            break
        if isinstance(msg, bytes):
            note("server->client", "binary", f"<{len(msg)} bytes, ignored>")
            continue
        data = json.loads(msg)
        note("server->client", "text", data)
        if data.get("uid") not in (None, uid):
            continue
        if data.get("message") == "DISCONNECT":
            break
        if data.get("status") == "ERROR":
            raise RuntimeError(f"server error: {data.get('message')}")
        for seg in data.get("segments") or []:
            seg_start = float(seg.get("start", 0.0))
            seg_end = float(seg.get("end", seg_start))
            text = seg.get("text", "")
            hit = next(
                (p for p in pieces if abs(p[0] - seg_start) <= start_tolerance), None
            )
            if hit is None:
                pieces.append([seg_start, seg_end, text])
            elif seg_end >= hit[1]:
                hit[1], hit[2] = seg_end, text
            last_seg = time.time()
    try:
        ws.close()
    except Exception:
        pass
    # Segment texts carry their own leading space; concatenate, do not join.
    pieces.sort(key=lambda p: p[0])
    text = "".join(p[2] for p in pieces).strip()
    if frames is not None:
        frames.extend(rec)
    return text, rec


def probe_clip(url: str, token: str, name: str, path: str) -> dict:
    audio = au.read_wav_float(path)
    pieces = au.split_for_server(audio)
    print(f"- {name}: {audio.size / au.RATE:.2f}s -> {len(pieces)} piece(s)")
    all_frames: list = []
    texts = []
    t0 = time.time()
    for i, piece in enumerate(pieces):
        secs = piece.size / au.RATE
        assert secs <= au.SERVER_CHUNK_MAX_S + 0.05, f"piece {i} too long: {secs:.2f}s"
        text, frames = transcribe_piece(url, token, piece)
        print(f"    piece {i + 1}/{len(pieces)} {secs:5.2f}s -> {text[:70]!r}")
        texts.append(text)
        if i == 0:
            all_frames = frames
    elapsed = time.time() - t0
    joined = " ".join(t for t in texts if t).strip()
    print(f"  joined ({elapsed:.2f}s): {joined}")
    return {
        "clip": name,
        "seconds": round(audio.size / au.RATE, 2),
        "pieces": [round(p.size / au.RATE, 2) for p in pieces],
        "elapsed_s": round(elapsed, 2),
        "text": joined,
        "frames_first_piece": all_frames,
    }


def probe_wrong_token(url: str) -> dict:
    """Connect with a bad token and record what the client can observe.

    Note for the Kotlin port: python's websocket-client does NOT surface the
    close code here (recv() just returns ''), but the server really does send
    `close(1008, "unauthorized")` - lia.py `_reject()` - and its log prints
    "serve: unauthorized client rejected". OkHttp surfaces it in
    `onClosing(code, reason)`; that is the hook ServerBackend must map.
    """
    print("- wrong token (expect 1008 unauthorized)")
    out = {
        "expected_close_code": 1008,
        "expected_close_reason": "unauthorized",
        "observed_first_recv": None,
        "observed_exception": None,
        "note": (
            "python websocket-client does not expose the close code; verified "
            "instead from the server side (lia.py _reject -> close(1008, "
            "'unauthorized') and the 'serve: unauthorized client rejected' log "
            "line). OkHttp reports it in onClosing(code, reason)."
        ),
    }
    try:
        ws = websocket.create_connection(
            url, header=["Authorization: Bearer definitely-not-the-token"], timeout=10
        )
        ws.send(json.dumps({"uid": "probe", "language": "he"}))
        ws.settimeout(10)
        try:
            out["observed_first_recv"] = ws.recv()
        except Exception as exc:
            out["observed_exception"] = f"{type(exc).__name__}: {exc}"
        ws.close()
    except Exception as exc:
        out["observed_exception"] = f"{type(exc).__name__}: {exc}"
    print(f"  first recv={out['observed_first_recv']!r} exc={out['observed_exception']}")
    print("  server-side truth: close(1008, 'unauthorized')")
    return out


def main() -> int:
    url = lia_keys.server_url()
    token = lia_keys.server_token()
    if not token:
        print("no server token available (set LIA_SERVER_TOKEN)", file=sys.stderr)
        return 2
    print(f"server: ws://{lia_keys.PLACEHOLDER_HOST}:{url.rsplit(':', 1)[-1]}  (real host hidden)")
    only = sys.argv[1] if len(sys.argv) > 1 else ""
    with open(os.path.join(FIXTURES, "clips.json"), encoding="utf-8") as fh:
        clips = json.load(fh)
    results = []
    for clip in clips:
        if only and clip["name"] != only:
            continue
        path = os.path.join(FIXTURES, clip["name"] + ".wav")
        try:
            results.append(probe_clip(url, token, clip["name"], path))
        except Exception as exc:
            print(f"  !! {type(exc).__name__}: {exc}")
            results.append({"clip": clip["name"], "error": f"{type(exc).__name__}: {exc}"})
    unauthorized = probe_wrong_token(url)
    payload = {
        "backend": "server",
        "protocol": "lia-serve (WhisperLive-shaped, batch)",
        "url": f"ws://{lia_keys.PLACEHOLDER_HOST}:9090",
        "results": results,
        "unauthorized": unauthorized,
    }
    out = os.path.join(FIXTURES, "server_probe.json")
    with open(out, "w", encoding="utf-8") as fh:
        fh.write(lia_keys.scrub(json.dumps(payload, ensure_ascii=False, indent=2)))
    print(f"\nwrote {out}")
    ok = sum(1 for r in results if r.get("text"))
    print(f"{ok}/{len(results)} clips transcribed")
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
