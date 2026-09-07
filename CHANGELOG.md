# Changelog

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
