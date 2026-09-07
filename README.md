# Lia for Android

Hebrew-first speech to text on your phone, talking to the same backends as
[Lia for Windows](https://github.com/Danaor/lia): your own home transcription
server, Groq, OpenAI or Gemini.

Dictate and get clean text you can copy or share. Point it at an audio or video
file and get a transcript. Your desktop vocabulary and correction table come
along.

- **Home server first.** If you run Lia on a PC with `--serve`, the phone talks
  to it over Tailscale. Nothing leaves your network, and it is the fastest of
  the four backends (about 0.4 s for a short clip in testing).
- **Clouds when the PC is off.** Groq, OpenAI (`gpt-transcribe` and the Whisper
  models) and Gemini (`gemini-3.5-transcribe`) each need only their API key.
- **The same text handling as the desktop.** The silence gate, the auto-gain,
  the hallucination strip that does not eat a real question mark, your
  corrections table, and the optional Hebrew -in/-im spelling guard.

## Getting started

1. Install the APK from [Releases](https://github.com/Danaor/lia-android/releases).
2. Open **Settings**.
3. Either:
   - **Home server** - install [Tailscale](https://tailscale.com) on the phone
     and sign in to the same account as the PC. In Lia on the PC open
     Settings > Transcription server, copy the address and the access token,
     and paste both here. Tap **Test**.
   - **A cloud** - paste a Groq, OpenAI or Gemini API key and tap **Test**.
4. Choose the language (Hebrew by default), then go to **Record** and talk.

### The access token

The token is a plain shared secret and it has to be **identical on both
devices**. If the phone says *"Access token doesn't match the server"*, copy the
token from the PC again - do not press Generate there, because that would break
every other device already using it.

Leaving the token field blank keeps whatever is already saved; the app never
shows a saved secret back to you.

## Backends at a glance

| Backend | Where the audio goes | Speed | Notes |
|---|---|---|---|
| Home server | your own PC | fastest | needs Tailscale (or a LAN) and the token |
| Groq | api.groq.com | very fast | `whisper-large-v3-turbo` |
| OpenAI | api.openai.com | fast | `gpt-transcribe` by default |
| Gemini | Google | slowest | free tier available; see the privacy note below |

The app can fall back from the home server to one cloud when the PC is
unreachable, and it always tells you when it did. It never falls back the other
way: if you chose a cloud, that is where the audio goes.

## Dictating into other apps

The point of a dictation app is not to dictate into itself. Lia gives you two
ways to get text into WhatsApp, mail, or any other field, and you can use
either.

### The floating button (no keyboard switching)
Settings > Dictate into other apps > **Show the floating button**. A small
microphone floats over whatever you are doing. Tap it, talk, tap it again. You
stay in WhatsApp and you keep your own keyboard. Drag it anywhere; the
notification hides it again.

Where the text lands depends on one optional switch:

| | taps | what it needs |
|---|---|---|
| straight into the field | tap, talk, tap | Lia switched on under Accessibility |
| onto the clipboard | tap, talk, tap, then the keyboard's paste chip | nothing extra |

Android only lets an app write into another app's text field if it is the
keyboard or an accessibility service. Lia's accessibility service does one
thing: at the moment you finish dictating, it finds the focused field and puts
your text in it. It reads nothing else, stores nothing, and sends nothing. If
you would rather not grant that, leave it off and paste - everything else still
works. See [SECURITY.md](SECURITY.md).

### The voice keyboard (no extra permissions)
Settings > Dictate into other apps > **Enable it**, then pick "Lia dictation
keyboard" from the keyboard switcher. It starts listening the moment it opens,
inserts what you said, and hands control straight back to your usual keyboard.
Both of those steps can be switched off.

Note that Gboard's own microphone key cannot be pointed at Lia - it is wired to
Google's recogniser - which is why these two routes exist.

## Files

Pick any audio or video file, or share one into Lia from another app. Long files
are split at the quiet points and sent piece by piece, with a progress bar and a
Cancel button. The transcription keeps running with the screen off.

## Vocabulary

Copy `vocabulary.json` from the PC (`%APPDATA%\Lia\vocabulary.json`) to the
phone by any means you like, then **Settings > Vocabulary > Import**. Your terms
and corrections then apply here too, and the prompt Lia composes for a cloud
model is byte-identical to the desktop's.

When you use the **home server**, you do not need this: the PC applies its own
vocabulary for you.

## Hebrew spelling guard (optional)

Whisper often hears the plural ending *-im* as *-in*. The guard fixes exactly
that, and only when a Hebrew dictionary proves the heard form is not a word and
the corrected one is. Defective spellings like *me'onyan* are protected by a
yod-guard and left alone.

The dictionary is derived from [hspell](http://hspell.ivrix.org.il/) and is
**AGPL**, so it is not part of the app. Tap Download in Settings and Lia fetches
it once (about 5 MB), checks it against a pinned SHA-256, and stores it
privately on the phone. Lia itself stays MIT.

## Privacy

- API keys and the server token are kept in `EncryptedSharedPreferences`.
- Transcripts are stored on the phone in History; you can delete them all in one
  tap.
- Nothing is written to the diagnostic log unless you switch that on.
- Google's free Gemini tier may use submitted audio to improve their products.
  The Settings screen repeats this next to the key. Use the home server for
  anything private.

See [SECURITY.md](SECURITY.md) for the transport rules and how to report an
issue.

## Building

Needs JDK 17 and the Android SDK (platform 35, build-tools 35).

```
./gradlew test          # JVM unit tests, no emulator needed
./gradlew assembleDebug # app/build/outputs/apk/debug/app-debug.apk
```

`tools/` holds the Python probes used to capture the golden fixtures in
`fixtures/` from the real backends. They read credentials from a local Lia
desktop install and never write a secret anywhere.

## Licence

MIT - see [LICENSE](LICENSE). Third-party: OkHttp (Apache-2.0), AndroidX and
Jetpack Compose (Apache-2.0). The optional Hebrew dictionary is AGPL and is
downloaded at runtime, never bundled.
