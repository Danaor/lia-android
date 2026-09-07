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

## The optional accessibility service

`TextInserter` exists for one reason: Android permits an app to write into
another app's text field only if it is the keyboard or an accessibility
service. It is **off by default** and the app is fully usable without it - the
floating button falls back to the clipboard.

What it does and does not do:

- It requests `typeViewFocused` and nothing more, the minimum the platform
  accepts. It ignores every event it receives.
- It reads the screen only inside `insert()`, only to locate the focused
  editable node, and only when you have just finished dictating.
- It never logs, stores, caches or transmits anything it sees. There is no
  code path from this service to the network, the log, or history.
- Switching it off in Android settings removes the capability immediately.

If you prefer not to grant it, leave "Put the text straight into the field" off:
dictated text is copied instead, and your keyboard's paste chip inserts it in
one tap.

## The floating button

`SYSTEM_ALERT_WINDOW` ("Display over other apps") is requested only when you
turn the floating button on, never at first run. The overlay window is created
with `FLAG_NOT_FOCUSABLE`, so it cannot take focus from, or read, the field
behind it. The button runs in a microphone foreground service with a permanent
notification that can stop it.

## Untrusted text

A transcript is untrusted input. Every result has C0/C1 control characters
stripped before it is displayed, copied, shared or stored.

## What is not sent

- Keys and tokens are never logged, never put in a URL or query string, and
  never included in a share.
- The Gemini key travels in the `x-goog-api-key` header, not the URL.
- Transcript logging is off by default.
