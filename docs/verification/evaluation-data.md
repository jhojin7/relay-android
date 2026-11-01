# Evaluation retention verification

Verified on 2026-11-01 in the dedicated disposable Android 15/API 35 ARM64
`relay-evaluation-disposable` emulator. No tests or installs ran on the user's S23.

## Checks

- `./gradlew assembleDebug assembleDebugAndroidTest lintDebug` passed using the
  local Android SDK. Lint reported zero errors and 17 warnings (mostly English
  strings and existing programmatic views).
- The full `com.hush.voice.HushTestRunner` suite passed **35 tests, zero failures**.
  The evaluation UI journey was rerun successfully after final screenshot timing
  and count-wording adjustments.
- v1 and v2 SQLite upgrade tests preserve existing transcripts; the v2 test also
  preserves a recovery draft and pending WAV, with retention disabled for old data.
- Runtime coverage includes default-off cleanup, capture-time opt-in, opt-out for
  future recordings, failed-request recovery/retry, immutable original versus edited
  text, deduplicated commit, retained-file recovery, failed export preservation,
  exact exported WAV bytes and note/audio deletion.
- Existing endpoint-scoped Keystore, HTTPS-only, redirect refusal, capture,
  cancellation, pending recovery and assistant-intent tests continue to pass.

## Live user journey and side effects

Used poteto's pstack `create-verification-skill` workflow to generate and then run
[verify-relay](../../.agents/skills/verify-relay/SKILL.md). The helper checks the
dedicated AVD identity before every command. The evaluation journey drove actual
settings/editor views; only the existing PCM microphone and HTTPS boundaries used
controlled test data. The manual continuation drove the native document picker
through its visible Save action, rather than calling the exporter directly.

1. Enabled retention through Settings, captured a synthetic sample, then edited
   “Evaluation sample: meet at tree.” to “Evaluation sample: meet at three.”
2. Settings showed one saved recording. Saved the evaluation ZIP to Downloads with
   Android's file picker, then pulled and parsed that actual file.
3. ZIP CRC validation passed. It contained `manifest.json`, `recordings.jsonl` and
   `audio/1.wav`. The record preserved both distinct texts, provider `groq`, model
   `whisper-large-v3-turbo`, `ko-KR`, timestamp and duration. The WAV contained 4,800
   PCM frames, mono, 16 kHz, 16-bit. No endpoint or credential fields/test token
   appeared in the uncompressed entries.
4. Before deletion: notes=1, evaluation=1, pending=0, local WAV files=1. Reopening
   and cancelling the export picker left the original data intact.
5. Deleted the note through the editor and confirmation dialog. Settings showed
   zero saved recordings. Direct checks of the app-private SQLite database and
   audio directory found notes=0, evaluation=0, pending=0 and local WAV files=0.
   The already exported ZIP still existed.

All screenshots below contain synthetic fixtures only. No recordings, ZIPs,
databases, credentials or APKs are committed. Local raw proof is kept in the
ignored `build/verification/` directory and survives emulator cleanup.

## Screenshots

- [Retention enabled and saved count](evaluation-settings.png)
- [Editing the current transcript](transcript-correction.png)
- [Native ZIP save dialog](export-picker.png)
- [Saved ZIP visible in Downloads](export-saved-file.png)
- [Zero retained recordings after deletion](evaluation-deleted.png)

## Limits

This proves local storage, editing, recovery and export behavior. It does not
measure live provider transcription accuracy or physical phone microphone quality.
Android on-device/system speech does not collect original audio. No signed release
APK was built or delivered as part of this draft PR; existing release signing
material was not copied into the worktree or changed.
