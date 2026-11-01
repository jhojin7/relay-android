# Relay

Native Android speech-to-text app with local transcript history and a default-assistant shortcut. Android 12+; tested on an Android 15 emulator. The original Hush assistant shortcut was confirmed working by the user on a Galaxy S23.

## Recording

- **Home screen:** touch and hold **Hold to dictate**. Capture starts immediately in place. Release to stop and save/transcribe.
- **Recorder:** tap to start continuous dictation, or hold its record control and release to finish. The screen-reader/keyboard activation of the home button opens this recorder.
- **Side button:** select Relay as the default digital assistant, then configure Samsung Settings → Advanced features → Side button → Long press → Digital assistant. Long-press opens the recorder and starts listening. A pause or Done ends capture. Android does not deliver the physical power-button release to ordinary apps.
- Recordings are limited to 3 minutes. Cloud pause detection uses microphone levels; background noise may require tapping Done.
- Search, edit, copy, share, and export transcripts as TXT. Automatic clipboard copying is optional.

## Providers

### Android on-device / system speech
Uses the phone's installed speech engine and selected language. On-device is the default. Availability and accuracy depend on installed language models. Android system speech may process audio online.

### Groq
1. Settings → Change provider → Groq.
2. Enter your own Groq API key.
3. Choose `whisper-large-v3-turbo` or `whisper-large-v3`, or enter another transcription model ID supported by Groq.
4. Save, then hold the home record button. On release, Relay uploads the recording and saves the returned text.

**This is speech-to-text, not text-to-speech.** A chat/completion model cannot be used as a transcription model just by entering its ID.

### Custom API
Enter a complete **HTTPS transcription URL**, API key if the server requires one, and model ID. The endpoint must accept OpenAI-compatible multipart `file`, `model`, `response_format=json`, and an optional ISO language hint, returning a JSON object with a string `text` field. This supports any transcription model served through that interface; it does not load arbitrary local model files.

The language option **Auto** omits the language hint for cloud providers. Android providers use the device language for that option.

## Privacy and failures

- Transcripts and recovery drafts are in app-private SQLite. Cloud and device-transfer backups are disabled. No analytics or ads.
- API keys are encrypted with an Android Keystore AES-GCM key and authenticated to their exact endpoint URL. No plaintext keys are stored, shown in logs, or sent to another endpoint after a configuration change.
- Cloud recording uses 16 kHz mono PCM WAV. Audio is uploaded **after capture stops**. HTTPS redirects are refused rather than forwarding credentials. API use follows the provider account's limits and billing.
- By default, audio is deleted after the returned transcript is committed locally. Failed/interrupted recordings remain in **Pending recordings** for retry or deletion.
- Retry explicitly uses the current cloud provider/model settings, so a mistaken model or endpoint can be corrected without rerecording.
- Dismissing a recorder stops microphone capture. A cloud request may finish after its UI closes. If Android kills the process, recoverable audio appears for manual retry on the next launch.
- Explicit Discard deletes in-progress cloud audio and prevents upload. Once uploading begins, Discard is disabled; Close may dismiss the UI while the request finishes.
- Uninstalling deletes transcripts, pending audio, and keys. Export transcripts first if you need to keep them.
- Relay does not request screen contents, screenshots, accessibility access, or automatic typing access. Copy/paste sends text to other apps.

## Collecting your own evaluation data

In **Settings → Evaluation data**, turn on **Keep recordings for evaluation** before
starting a Groq or Custom API recording. It is off by default. Relay keeps the
captured 16 kHz mono PCM WAV and the original transcription in private app storage,
alongside the note's latest edited text, capture time, language, duration and
provider/model. Editing a note does not change its saved original transcription.
Android on-device/system speech does not provide audio to this collection path.

**Export evaluation ZIP** opens Android's file picker. The ZIP contains:

- `manifest.json`: schema version, recording count and audio format.
- `recordings.jsonl`: one record per retained note, with `note_id`, `created_at_ms`,
  `audio`, `original_transcript`, `transcript` (latest text), `language`,
  `duration_seconds`, `provider` and `model`.
- `audio/<note_id>.wav`: the matching captured audio.

No API keys, endpoint URLs or raw provider responses are included. The latest text
is only a correction/reference if you have reviewed it; untouched model output is
not ground truth. TXT export continues to export current transcripts only.

The retention choice is fixed when capture starts and survives failure/retry.
Turning it off affects future recordings and does not erase existing pairs.
It does not recover old deleted audio or retroactively collect old pending items.
Delete a transcript to delete its retained audio and original text too. Exporting
does not remove local data; deleting a note does not remove exported ZIP copies.
Uninstalling still removes all local data, and Android backups remain disabled.

## Updates from Hush

The launcher name is now Relay. The Android application ID, assistant component names, database name, and local release signing identity are intentionally preserved for an in-place update. Install the signed Relay APK over Hush; **do not uninstall Hush first** if you want to keep its notes.

## Build

Open this folder in Android Studio. Set your SDK location in `local.properties` or `ANDROID_HOME`.

- Java source: 17; Gradle: 9.0; Android Gradle Plugin: 8.13.2.
- Compile SDK: 36; target SDK: 35; minimum SDK: 31.
- No third-party runtime libraries.

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
./gradlew assembleRelease lintRelease
```

Release signing reads the ignored local `signing/signing.properties` and keystore. Signing material stays on this Mac and is not pushed to GitHub or included in source archives. Keep it private and backed up to issue compatible updates. Builds without it produce an unsigned release APK; debug APKs use another certificate and cannot replace an existing signed release without uninstalling.

GitHub Actions builds/lints the debug application and compiles its runtime tests. CI does not use the local release signing key and does not run a live Groq request.

## Runtime verification

See [VERIFICATION.md](VERIFICATION.md) and [TEST-RESULTS.txt](TEST-RESULTS.txt). **Runtime tests reset history and preferences: run only on a disposable emulator, never on the user's S23.**

Evaluation retention checks and synthetic UI screenshots are in
[evaluation-data.md](docs/verification/evaluation-data.md). The project-local
[verify-relay skill](.agents/skills/verify-relay/SKILL.md) provides the guarded
emulator harness and feature map.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.hush.voice android.permission.RECORD_AUDIO
adb shell am instrument -w com.hush.voice.test/com.hush.voice.HushTestRunner
```

If a signed release is already installed in that emulator, uninstall it before installing a debug build. The tests use controlled audio and HTTP boundaries with real Android Keystore, SQLite and UI events. No real API key is included in the app or tests.

## Platform references

- [Groq speech-to-text](https://console.groq.com/docs/speech-to-text)
- [Groq API reference](https://console.groq.com/docs/api-reference)
- [Samsung digital assistant setup](https://www.samsung.com/us/support/answer/ANS10001575/)
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)
