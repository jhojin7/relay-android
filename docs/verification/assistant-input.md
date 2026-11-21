# Assistant dictation input verification

Verified 2026-11-22 on a disposable Android 15 / API 35 emulator.
All text, speech results and editor screenshots below are synthetic.

## Behavior exercised

The runtime suite enables the real accessibility service, keeps the ordinary
keyboard selected, opens a debug-only native editor and injects Android's assistant
key. The actual assistant service/session and window run in Android; only speech
recognition is substituted through the existing backend interface. A final speech
result is committed to SQLite before the assistant closes and the accessibility
input connection inserts it into the original editor.

- `Before  after`, with the cursor between the spaces, becomes
  `Before dictated after`; the saved note contains `dictated`.
- Direct cursor insertion and selection replacement preserve the surrounding draft.
- Korean composing text is preserved when additional dictated text is inserted.
- Draft, cursor, field and foreground-window changes reject pending delivery.
- Turning off the option or disconnecting accessibility rejects pending delivery.
- A password field cannot be captured as an automatic-input target.
- Repeating delivery on the same request does not insert twice.
- After the recorder is actually dismissed with Back (the IME may consume the first
  Back), a late speech result is not inserted. Recovered partial speech remains saved.
- Keyboard insets keep the recorder controls clear of the keyboard. The ordinary
  keyboard selection is unchanged by assistant invocation and insertion.

The fixture activity is debug-only and not exported. It is absent from the release
manifest. The service does not take screenshots in production; screenshot evidence
comes from the instrumentation automation on this disposable emulator.

## Commands and evidence

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
python3 .agents/skills/verify-relay/scripts/control.py test
python3 .agents/skills/verify-relay/scripts/control.py pull-evidence
```

The full custom-runner suite passes **41 tests**, including six new input tests and
the existing capture, HTTPS/credential handling, failure recovery, schema upgrade,
recording retention and evaluation ZIP checks. The focused input suite also passes
independently. Input tests are skipped on Android versions before API 33; normal
recording and storage tests remain applicable.

Release packaging and `lintRelease` also pass in the isolated worktree without
release signing material (unsigned packaging check, not a release delivery). The
release manifest contains the accessibility service and excludes the debug editor.

A manual emulator UI pass also scrolled to the new setting, enabled the opt-in,
and opened Android Accessibility, where Relay dictation input was listed.

Raw runtime reports remain in ignored `build/verification/`. The following copied
screenshots contain only the synthetic fixture and controlled recorder:

- [Input setup and disclosure](assistant-input-settings.png)
- [Assistant recorder](assistant-recording.png)
- [Result inserted into the original editor](assistant-inserted.png)

Existing tests were retained because their recording, metadata, storage and export
coverage remains applicable. No old behavior test was made obsolete by this change.

## Limits and remaining device check

No Samsung physical device was connected. The emulator assistant key validates the
assistant/session path, not a manufacturer's mapping of a hardware side button.
Manually verify Relay as the default assistant, the side-button assistant binding,
and disabling Samsung Keyboard's side-button voice-input shortcut where present,
with the keyboard both visible and hidden. Do not run this destructive instrumentation
suite on a personal phone.

This is not a live provider-accuracy or microphone-quality test. Custom editors,+third-party accessibility restrictions and OEM behavior may require manual copying.
The public accessibility input API does not acknowledge commit success: the app
dispatches at most once, does not claim confirmed insertion, and retains the note
for copying when the connection is unavailable or the target has changed.

## Implementation boundary

The assistant recorder still owns capture. The saved-result callback runs only
after local commit, so the assistant can then close and release its engine before
handoff; no cloud-job or database rewrite was needed. User dismissal releases the
result listener and invalidates the target. Pending-recording retries have no
target and never replay text into another app. Existing retention and recovery
semantics remain unchanged.
