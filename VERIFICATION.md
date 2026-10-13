# Relay 1.1.0 verification

Date: 2026-10-13.

- `assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease`, and `lintRelease` passed locally. Android lint: zero errors, 16 warnings, mainly English strings, programmatic view construction, and target SDK 35. One narrow lint suppression covers the signature-protected broadcast receiver registration on Android 12, which predates exported-state flags.
- **30 Android-runtime tests passed, zero failed**, on an Android 15/API 35 ARM64 emulator. See TEST-RESULTS.txt for individual results.
- Tests verify immediate touch-down capture on the real home-screen view, release-to-stop, recorder hold/tap controls, final recognition timing, multiple Android speech segments, empty sessions, cancellation, interruption, legacy SQLite migration and history preservation, literal search/edit/delete, pending audio recovery, atomic/deduplicated cloud saves, cloud failure/retry and audio cleanup, dismissal during cloud capture, API multipart WAV/model/language fields, auto language, encrypted endpoint-scoped Android Keystore keys, credential-safe redirect refusal, HTTP authentication/quota error mapping, WAV header repair/PCM layout, and the assistant setting's Android OS intent.
- HTTP and audio-source boundaries are controlled in cloud tests. Keystore encryption, SQLite, application lifecycle, touch events and views use the real Android runtime. No real Groq API key was supplied, so a live authenticated Groq transcription has **not** been performed.
- The app was visually reviewed in the emulator: home screen, settings, provider chooser and Groq configuration. The home screen has no marketing text or decorative hero waveform.
- The application ID remains `com.hush.voice`, the assistant component remains `com.hush.voice/.HushAssistant`, and the database remains `hush.db` for compatible upgrades from Hush. Database version 2 adds the pending-audio table without replacing transcript history.
- APK signed with the same dedicated local release key as Hush 1.0; Android apksigner verifies its v2 signature. Signing credentials are excluded from Git and source archives.

## Remaining device checks

The user confirmed the original assistant/power shortcut works on their Galaxy S23. The new Groq transcription, native PCM microphone quality/VAD, and updated UI still need use on that phone. The Android on-device recognizer in the emulator has no usable speech-language model, so its transcription accuracy is not evaluated here. Runtime cloud tests simulate provider responses and do not certify remote speech accuracy. Background request completion is best effort; interrupted audio is retained for manual retry.

## Delivered APK SHA-256

`040c17fe08b87a2541b2c3beb880c9bbc16546ce18117afab712851f6f197c5e`
