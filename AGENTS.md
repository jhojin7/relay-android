# Relay Android

- Native Java Android app, no third-party runtime libraries. Java source is formatted with google-java-format.
- Preserve `com.hush.voice`, the `hush.db` filename, and the existing release signing identity: installed Hush versions must upgrade to Relay without losing notes or assistant selection.
- Make SQLite schema upgrades additive and test preservation of existing transcripts.
- Never commit signing files, API keys, local.properties, APKs, Android Studio state, or build output. Signing material is intentionally retained only in ignored `signing/` locally.
- Keep product wording functional. No marketing slogans, decorative hero sections, or fake recording animations. The waveform is shown only during capture.
- Cloud audio must stay recoverable on request failure and must be deleted after successful local transcript commit unless evaluation retention was explicitly enabled when capture started. Avoid logging audio, credentials, request headers, or provider responses containing secrets.
- Maintain endpoint-scoped Android Keystore key encryption, HTTPS-only uploads, and redirect refusal.
- Run `./gradlew assembleDebug assembleDebugAndroidTest lintDebug` for relevant changes. Release delivery uses `assembleRelease lintRelease` with the local signing files.
- Runtime tests use the custom instrumentation runner `com.hush.voice.HushTestRunner`. **Run only on a disposable emulator: the tests reset app history and preferences.** Never run them on the user's S23.
- Always squash merge pull requests.
