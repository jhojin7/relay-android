# Assistant dictation input

Requires Android 13+ for automatic insertion; the normal recording/copy flow still
supports Android 12. Use only the dedicated disposable emulator and custom runner.

Run `control.py test com.hush.voice.AssistantInputTests`. The suite enables and
restores its accessibility/default-assistant settings, opens a debug-only synthetic
editor, and uses the real accessibility input connection and ordinary keyboard.
It invokes Android's assistant key, substitutes only the speech recognition backend,
waits for local transcript commit, and verifies the original field receives text.

Required behavior:

- Cursor insertion and selected-text replacement preserve the rest of the draft.
- The selected keyboard remains unchanged; Korean composing text is preserved.
- A changed draft, cursor, editor or foreground app cancels automatic insertion.
- Password input is rejected; disabling accessibility invalidates a pending request.
- Dismissing the actual assistant window saves recovered speech without inserting
  a late result. A dispatched result is never automatically sent again.
- The restricted-settings setup action opens Relay's own Android App info entry.
  `CloudTests#testRestrictedAccessibilitySetupOpensOwnAppInfo` checks the Intent
  action and exact package URI. Android 13+ APK installs can require a user to
  allow restricted settings there before accessibility can be enabled. The in-app
  opt-in alone is not permission and must not be presented as ready.

`assistant-recording.png` and `assistant-inserted.png` are synthetic runtime evidence
under app-private `files/verification/`; pull them using `control.py pull-evidence`.
Keep evidence in ignored `build/verification/`, then run the guarded cleanup command.

The test activity and recording factory are instrumentation seams. The activity is
debug-only and not exported. No test component, key or real recording is needed in
release builds. Existing provider/storage tests cover cloud failure and retention.

Samsung side-button routing with its keyboard visible still requires an
initialization-free manual check on the target One UI version. Do not run the
instrumentation runner on a personal phone. The emulator's assistant key proves
the assistant session/input path, not Samsung's hardware mapping.
Shell-enabled accessibility in the input suite does not validate the APK installer
or the restricted-settings consent flow on a physical Samsung device.
