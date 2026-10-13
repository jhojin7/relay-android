# Changelog

## 1.1.0 — Relay

- Renamed the launcher to Relay, retaining the original application and assistant identity for compatible updates.
- Home-screen hold-to-talk starts capture immediately on touch-down and stops on release.
- Removed marketing slogans and the decorative hero/waveform from the home screen.
- Assistant setup buttons open Android's Default apps settings.
- Added Groq transcription, Whisper presets, and an editable model ID.
- Added custom HTTPS OpenAI-compatible transcription endpoints.
- Added endpoint-scoped encrypted API key storage and connection checks.
- Added WAV capture with live level feedback, cloud request handling, and local retryable pending audio.
- Preserved existing transcript history through an additive database migration.

## 1.0.0 — Hush

Initial native Android assistant with on-device/system dictation and local transcript history.
