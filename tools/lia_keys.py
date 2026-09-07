"""Read Lia Desktop credentials for the Phase 0 probes.

Nothing here ever writes a secret to disk or prints one. Values come from
(in order):

1. environment variables - LIA_SERVER_URL, LIA_SERVER_TOKEN, GROQ_API_KEY,
   OPENAI_API_KEY, GEMINI_API_KEY;
2. the local Lia Desktop config (%APPDATA%\\Lia\\config.json), decrypted with
   the desktop's own secret_store (DPAPI).

The desktop source directory is found via LIA_DESKTOP_DIR, else a guess at
the usual dev checkout path under the home directory. If neither works you can still run every probe by exporting
the environment variables above.
"""

from __future__ import annotations

import json
import os
import sys

DEFAULT_DESKTOP_DIR = os.path.join(
    os.path.expanduser("~"), "Downloads", "WhisperType", "lia"
)
PLACEHOLDER_HOST = "100.64.0.1"
PLACEHOLDER_TOKEN = "TEST-TOKEN"


def _config_path() -> str:
    appdata = os.environ.get("APPDATA") or os.path.expanduser("~")
    return os.path.join(appdata, "Lia", "config.json")


def _load_config() -> dict:
    try:
        with open(_config_path(), "r", encoding="utf-8-sig") as fh:
            return json.load(fh)
    except Exception:
        return {}


def _unprotect(value: str) -> str:
    """Decrypt a dpapi: blob using the desktop's secret_store, if reachable."""
    if not isinstance(value, str) or not value.startswith("dpapi:"):
        return value or ""
    desktop = os.environ.get("LIA_DESKTOP_DIR", DEFAULT_DESKTOP_DIR)
    if desktop not in sys.path:
        sys.path.insert(0, desktop)
    try:
        import secret_store  # type: ignore

        return secret_store.unprotect(value) or ""
    except Exception as exc:  # pragma: no cover - environment dependent
        print(f"  ! cannot decrypt a desktop secret: {exc}", file=sys.stderr)
        return ""


def _from_config(key: str) -> str:
    return _unprotect(_load_config().get(key, ""))


def groq_key() -> str:
    return os.environ.get("GROQ_API_KEY") or _from_config("groq_api_key")


def openai_key() -> str:
    return os.environ.get("OPENAI_API_KEY") or _from_config("openai_api_key")


def gemini_key() -> str:
    return os.environ.get("GEMINI_API_KEY") or _from_config("gemini_api_key")


def server_token() -> str:
    return os.environ.get("LIA_SERVER_TOKEN") or _from_config("serve_token")


def server_url() -> str:
    """ws:// URL of the local Lia serve process.

    LIA_SERVER_URL wins. Otherwise ask Tailscale for this machine's IPv4 and
    pair it with the configured serve_port.
    """
    env = os.environ.get("LIA_SERVER_URL")
    if env:
        return env
    cfg = _load_config()
    port = int(cfg.get("serve_port") or 9090)
    host = _tailscale_ipv4() or "127.0.0.1"
    return f"ws://{host}:{port}"


def _tailscale_ipv4() -> str:
    import subprocess

    exe = os.path.join(
        os.environ.get("ProgramFiles", r"C:\Program Files"), "Tailscale", "tailscale.exe"
    )
    for candidate in (exe, "tailscale"):
        try:
            out = subprocess.run(
                [candidate, "ip", "-4"], capture_output=True, text=True, timeout=8
            )
        except Exception:
            continue
        for line in (out.stdout or "").splitlines():
            line = line.strip()
            if line.count(".") == 3:
                return line
    return ""


def scrub(text: str) -> str:
    """Replace this machine's real host and token with the public placeholders."""
    out = text
    token = server_token()
    if token:
        out = out.replace(token, PLACEHOLDER_TOKEN)
    url = server_url()
    host = url.split("//", 1)[-1].split(":", 1)[0]
    if host and host not in ("127.0.0.1", "localhost"):
        out = out.replace(host, PLACEHOLDER_HOST)
    for key in (groq_key(), openai_key(), gemini_key()):
        if key:
            out = out.replace(key, "REDACTED-KEY")
    return out
