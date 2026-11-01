---
name: verify-relay
description: Drive Relay's native Android settings, recorder, transcript editor and evaluation ZIP export on a disposable emulator; capture runtime and UI evidence after changes.
---

# Verify Relay

Generated using poteto's pstack [create-verification-skill](https://github.com/cursor/plugins/blob/4dd1d05818d19792e7cd6e818f1667f95f7e1dbe/pstack/skills/create-verification-skill/SKILL.md).
The feature map is the maintained source for verification. Use pstack's
`maintain-verification-skill` when the map or harness needs an audit.

## Launch

Run from the repository root. Java 17+ and an Android SDK with API 36 build tools,
the emulator, and `system-images;android-35;google_apis;arm64-v8a` are required.
On this Mac the SDK is `$HOME/Library/Android/sdk`; set `ANDROID_HOME` explicitly.
If needed, use SDK `avdmanager create avd --name relay-evaluation-disposable
--package 'system-images;android-35;google_apis;arm64-v8a' --device pixel_6`
and answer `no` to the custom hardware profile. Never reuse a personal AVD.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
"$ANDROID_HOME/emulator/emulator" -avd relay-evaluation-disposable -port 5580 -no-window -no-audio -no-snapshot -no-boot-anim
```

Keep that emulator in a managed background process. Wait for
`adb -s emulator-5580 shell getprop sys.boot_completed` to return `1`, then run:

```sh
.agents/skills/verify-relay/scripts/control.py doctor
.agents/skills/verify-relay/scripts/control.py install
.agents/skills/verify-relay/scripts/control.py launch
```

Ready means the Relay home UI is visible (`control.py ui`), not only boot complete.
No API key is required for the controlled runtime suite. A real cloud transcription
requires a configured provider/key, microphone input and network access.

## Doctor

`control.py doctor` reads the emulator serial, exact AVD name, emulator property and
boot completion. Every helper command repeats this guard. Only serial
`emulator-5580` running `relay-evaluation-disposable` is allowed. Never run tests on
the S23 or any physical phone: the runner resets history and preferences.
If anything fails unexpectedly, run doctor, force-stop only this emulator's Relay
process, relaunch and inspect `ui` before another drive. Use one coordinator for UI;
never double-drive this shared emulator.

## Drive

```sh
.agents/skills/verify-relay/scripts/control.py test
.agents/skills/verify-relay/scripts/control.py pull-evidence
.agents/skills/verify-relay/scripts/control.py launch
.agents/skills/verify-relay/scripts/control.py click Settings
.agents/skills/verify-relay/scripts/control.py scroll
.agents/skills/verify-relay/scripts/control.py ui
```

The custom runner is `com.hush.voice.HushTestRunner`. `control.py test
com.hush.voice.EvaluationUiTests` reruns just the evaluation UI journey and leaves
one synthetic recording for manual export. Class or `Class#method` filters are
supported. The full suite also ends with that UI journey.

`click` matches visible text or content description, ignoring case, from the current
Android accessibility dump; it prefers a unique clickable match and refuses absent
or ambiguous targets. `scroll up`, `back`,
`screenshot <lowercase-name>`, `exports` and `pull-evidence` are additional commands.
The scroll gesture is for the Pixel 6 AVD; inspect the next UI state after scrolling.
Do not treat `performClick` on an off-screen view as evidence of reachability; the
manual drive must scroll and use visible UI bounds.

## Evidence

Evidence stays under `build/verification/` (ignored by Git). The suite writes
`runtime-tests.txt` and pulls PNGs from app-private `files/verification/`.
Capture both action and resulting state: retention before/enabled, transcript
correction, saved-recording count, native export picker and completed export.
Use the actual Android Save button, pull the ZIP with `control.py exports`, then
inspect its manifest, JSONL and WAV entries. Check original versus edited text,
provider/model, count, PCM format and the absence of credentials/endpoints.

Runtime tests control only the existing microphone and HTTPS interfaces. SQLite,
Keystore, lifecycle, settings/editor views and document export are real Android.
The synthetic fixture is **not** proof of live provider accuracy or phone microphone
quality. An internal exporter test alone is not proof that the UI can save a file.
Never commit an APK, real recordings, API keys, database or build output. Only
explicitly synthetic screenshots may be copied into `docs/verification/` for a PR.

## Cleanup

After the final drive (including retries):

```sh
.agents/skills/verify-relay/scripts/control.py cleanup
```

This force-stops the app and kills only the dedicated emulator at port 5580. It
verifies that screenshot proof still exists in `build/verification/`. If a failed
iteration strands a UI, first doctor and reset that app; do not kill by process
name. Keep evidence after teardown. Remove the dedicated AVD with `avdmanager
delete avd -n relay-evaluation-disposable` only after the emulator has stopped.

## Helpers

`scripts/control.py` is executable, uses only Python's standard library, always
specifies the guarded serial, saves test output and fails on nonzero test failures.
Its commands are shown above. Run `scripts/control.py --help` for the command list.
