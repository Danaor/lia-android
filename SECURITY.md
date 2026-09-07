# Security

## Reporting

Open a private security advisory on this repository, or email the maintainer.
Please do not open a public issue for an unpatched vulnerability.

## What the app stores, and where

| Data | Where | Notes |
|---|---|---|
| API keys, server token | `EncryptedSharedPreferences` | keystore-backed; falls back to app-private prefs if the device keystore is unusable |
| Transcripts | `filesDir/history.json` | app-private; "Delete all" removes them |
| Vocabulary | `filesDir/vocabulary.json` | imported by you from the desktop |
| Hebrew dictionary | `filesDir/lexicon/he_IL.dic` | downloaded on request, SHA-256 pinned |

A saved secret is never rendered back into the UI: the field shows a
placeholder, and leaving it blank keeps the stored value rather than wiping it.
Only a masked preview (first four and last two characters) is ever displayed.

## Cleartext and the home server

Android blocks cleartext traffic by default. Lia needs plain `ws://` for exactly
one case: a home transcription server on a Tailscale or LAN address, which is an
IP literal that `network_security_config.xml` cannot express as a range. The
manifest therefore permits cleartext app-wide, and **the real restriction is
enforced in code** - `backend/WsUrl.kt`:

- `wss://` is always allowed.
- Plain `ws://` is allowed only to a private host: loopback, 10/8, 172.16/12,
  192.168/16, 169.254/16, the Tailscale CGNAT range 100.64/10, and names ending
  `.local` or `.ts.net`.
- Plain `ws://` to anything else is refused with an explanatory message, unless
  the user deliberately turns on "Allow insecure ws:// to a public host", which
  also shows a warning.

`WsUrlTest` covers both the allowed and refused sets.

## Server authentication

The phone sends `Authorization: Bearer <token>` on the WebSocket upgrade. The
server compares it in constant time and closes with 1008 `unauthorized` on a
mismatch; the app maps that close code to a message that names the real cause
rather than reporting a timeout. An authorization failure is never retried and
never falls back to another backend.

No `Origin` header is sent: the server rejects a foreign origin, and OkHttp does
not add one.

## Untrusted text

A transcript is untrusted input. Every result has C0/C1 control characters
stripped before it is displayed, copied, shared or stored.

## What is not sent

- Keys and tokens are never logged, never put in a URL or query string, and
  never included in a share.
- The Gemini key travels in the `x-goog-api-key` header, not the URL.
- Transcript logging is off by default.
