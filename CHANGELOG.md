# Changelog

## 0.2.0 - 2026-09-07

Dictate into other apps, without opening Lia.

- **Floating button.** A microphone bubble that sits over whatever you are
  doing. Tap, talk, tap. You stay in WhatsApp and keep your own keyboard; drag
  it anywhere, hide it from its notification. Runs as a microphone foreground
  service and comes back when you next open the app.
- **Optional direct insert.** With Lia switched on under Accessibility the text
  lands in the field you were typing in. Without it, the text is copied and your
  keyboard's paste chip inserts it in one tap. The service reads the screen only
  at the moment it inserts, and stores or sends nothing.
- **Voice keyboard.** An alternative with no extra permissions: switch to the
  Lia keyboard, it starts listening at once, inserts what you said and hands
  control back to your usual keyboard. Both automatic steps are switchable.
- Dictation from either surface goes through the same pipeline and lands in
  History, tagged with where it came from.

Gboard's own microphone key cannot be pointed at Lia - it is wired to Google's
recogniser - which is why these two routes exist.

## 0.1.0 - unreleased

First build.

- Dictation from the phone microphone, tap to start and tap to stop, running in
  a foreground service so it survives the screen going off.
- Four backends: the home Lia transcription server over WebSocket, Groq, OpenAI
  (`gpt-transcribe`, `gpt-4o-transcribe`, `gpt-4o-mini-transcribe`,
  `whisper-1`) and Gemini `gemini-3.5-transcribe`.
- Falls back from the home server to one configured cloud when the PC is
  unreachable, and labels the result. Never falls back the other way, and never
  retries an authorization failure.
- File transcription from the system picker or a share intent, with per-piece
  progress and cancel.
- Desktop parity for the text pipeline: silence gate, auto-gain, trailing
  silence trim, hallucination strip that keeps a real question mark, corrections
  table, and the optional Hebrew -in/-im spelling guard.
- Vocabulary imported from the desktop `vocabulary.json`; the composed prompt is
  byte-identical to the desktop's.
- History with search and delete-all. Keys and the server token in
  EncryptedSharedPreferences; a blank field keeps the saved secret.
