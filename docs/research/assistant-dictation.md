# Assistant-triggered dictation with the existing keyboard

Investigated 2026-11-06. Repository state inspected: `origin/main` at `3864f18`.
This is a feasibility investigation, not a completed implementation or device test.

## Conclusion

The requested experience is technically feasible: keep the user's current keyboard,
invoke Relay through the configured assistant shortcut, capture speech, and insert
the result into the original editor. The assistant role supplies the invocation/UI
path; it does **not** by itself supply general permission to write into another
app's editor. A user-enabled accessibility service can supply the insertion path.
Android 13/API 33 introduced a public accessibility input connection for this
purpose. The combined Relay flow still requires a prototype and Samsung testing.
[AccessibilityService.getInputMethod](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getInputMethod()),
[Accessibility input connection](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection).

## Confirmed Android capabilities

- `AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR` (API 33) gives an accessibility
  service a subset of input-method capabilities, including an input connection and
  selection-change notifications. `AccessibilityService.getInputMethod()` is null
  unless the flag is set and the system has created the input-method instance.
  [Flag](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_INPUT_METHOD_EDITOR),
  [Getter](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getInputMethod()).
- `android.accessibilityservice.InputMethod.getCurrentInputConnection()` returns
  the active accessibility input connection, or null. Its `commitText(text, 1,
  null)` API inserts text at the current selection/cursor when there is no composing
  text; selection and composition semantics must be tested with Korean input.
  [InputMethod](https://developer.android.com/reference/android/accessibilityservice/InputMethod),
  [commitText](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection#commitText(java.lang.CharSequence,%20int,%20android.view.inputmethod.TextAttribute)).
- This accessibility input method is separate from choosing a replacement soft
  keyboard. AOSP keeps the regular bound IME and accessibility IME sessions in
  separate fields and initializes both from one binding result. Therefore using
  this path does not require selecting Relay as the default keyboard. This is an
  inference from platform source, supported by Wispr's first-party behavior below;
  it is not a claim that every Samsung keyboard version is already verified.
  [AOSP InputMethodManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/inputmethod/InputMethodManager.java),
  [AOSP accessibility InputMethod](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/accessibilityservice/InputMethod.java).
- An accessibility service is enabled explicitly by the user in system settings;
  declaring an assistant service does not also enable accessibility access.
  [AccessibilityService lifecycle](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#service-lifecycle).

## Why assistant APIs alone are insufficient

`VoiceInteractionSession.requestDirectActions()` requests actions that the target
app supports. `performDirectAction()` sends a request back to the app that supplied
that action. This is cooperative app integration, not a universal text-insertion
operation. A target app would have to advertise and implement the relevant action;
Relay cannot assume arbitrary editors provide one.
[VoiceInteractionSession](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession),
[Activity.onGetDirectActions](https://developer.android.com/reference/android/app/Activity#onGetDirectActions(android.os.CancellationSignal,%20java.util.function.Consumer%3Cjava.util.List%3Candroid.app.DirectAction%3E%3E)).

Receiving an assist structure/screenshot is also separate from acquiring a live
editor input connection. Relay currently disables those assist-context requests,
and need not turn on screenshots merely to use the accessibility input method.
The manifest has assistant/recognition services but no accessibility service.
These are repository observations from `origin/main:app/src/main/AndroidManifest.xml`
and `origin/main:app/src/main/java/com/hush/voice/HushAssistant.java`.

## Older Android and fallbacks

Relay's minimum SDK is 31, so API 31–32 need a separate fallback or an explicitly
limited automatic-insertion feature. Accessibility `ACTION_PASTE` pastes clipboard
content, while `ACTION_SET_TEXT` sets the target element's text and moves the cursor
to the end. Their success depends on the target exposing/implementing the action.
Do not treat `ACTION_SET_TEXT` as an insert-at-cursor primitive: naively using it
could replace an existing draft. Manual copy/paste remains a clear fallback when
there is no usable connection or supported action.
[ACTION_PASTE](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction#ACTION_PASTE),
[ACTION_SET_TEXT](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction#ACTION_SET_TEXT).

## Proposed implementation boundary and unverified behavior

Use Relay's existing assistant invocation and capture engine, plus a narrowly used,
user-enabled accessibility service. Capture the intended editor identity at the
start of an explicit dictation operation, then revalidate it before committing
text. If the assistant panel takes focus, dismiss it and wait for the original
editor connection to return rather than typing into the panel or another app.
This is an implementation proposal, not a documented guarantee of focus retention.

Input-method callbacks report start/finish/editor changes, and connections can be
null or invalid. Test editor changes during transcription, app switches, selection
replacement, Korean composing text, custom/web editors and unavailable connections.
Exclude password fields as a product rule, using editor information/node password
metadata; do not claim that the API itself automatically rejects every password
field. Never automatically send the message or trigger an editor's send action.
[InputMethod lifecycle](https://developer.android.com/reference/android/accessibilityservice/InputMethod),
[EditorInfo.inputType](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#inputType),
[AccessibilityNodeInfo.isPassword](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#isPassword()).

## Wispr first-party evidence

Wispr's Android documentation says users keep their existing keyboard and enable
accessibility for text insertion. Its own Play listing describes insertion using
the accessibility input connection. This demonstrates the keyboard-preserving
insertion approach in a shipped app. It does not establish that Wispr uses the
assistant button, or that Relay's assistant panel already preserves editor focus.
[Wispr Android documentation](https://docs.wisprflow.ai/articles/8858845757),
[Wispr Play listing](https://play.google.com/store/apps/details?id=com.wispr.flowapp).

## Samsung assistant-button routing

- Samsung documents selecting a default digital assistant separately from the
  side-button binding. Its side-button support article permits a long press to
  launch a digital assistant, with availability varying by model/software/region.
  Relay already implements the assistant service/session used for this route.
  [Default assistant settings](https://www.samsung.com/us/support/answer/ANS10001575/),
  [Side-button settings](https://www.samsung.com/au/support/mobile-devices/how-to-customise-the-side-button-with-new-features-on-your-galaxy-phone-and-tablet/).
- Samsung's moderator describes a Samsung Keyboard voice-input setting named
  `Use Side button for voice input`, which invokes dictation with the keyboard
  open. This was available in the indexed moderator reply; fetching the full
  community page failed during the investigation. Treat the reply as evidence
  for the setting, not a verified description of routing on every firmware.
  [Samsung moderator reply](https://stg-eu.community.samsung.com/t5/galaxy-s25-series/my-galaxy-s25-ultra/td-p/15193994).
- Samsung Knox independently documents keyboard-open side-key voice input on
  certain tablets running One UI 6.1 or later. That article's tablet/kiosk scope
  does not prove the behavior on a phone with a different firmware.
  [Samsung Knox documentation](https://docs.samsungknox.com/admin/knox-platform-for-enterprise/kbas/kba-1667-voice-input-button-is-missing-from-keyboard-toolbar-in-lock-task-mode/).

For Relay, the proposed configuration is: select Relay as the default assistant,
bind the side-button long press to the digital assistant, and disable Samsung
Keyboard's side-button voice-input shortcut if present. **Disabling that shortcut
and getting Relay while the keyboard is open is a testable hypothesis, not a
hardware result obtained in this investigation.** Check keyboard-hidden and
keyboard-visible cases separately on the target One UI version.

The keyboard microphone button, side-button shortcut, and system assistant gesture
are different entry paths. The supplied navigation-bar dialog establishes that
Samsung's voice-input route was requested; it does not establish that Relay can
replace that microphone action. There is no need to replace the microphone action
to implement the requested assistant-triggered flow.

## Current Relay state and required validation

At `3864f18`, opt-in recording retention and evaluation ZIP export already exist;
the README documents WAV, original transcription and latest edited text storage.
The assistant recorder still copies results instead of inserting into another app.
No application code, settings, installed app, or device data was changed by this
investigation, and no runtime tests were run.

Before promising the final experience, prototype and verify: assistant invocation
with the keyboard visible, original-editor recovery, commit without disturbing
existing draft text, cancellation, and failed/late transcription. Destructive
instrumentation tests must remain restricted to a disposable emulator as required
by the repository instructions.

## Implementation follow-up (2026-11-22)

The opt-in assistant input implementation and its Android runtime evidence are
recorded in [verification](../verification/assistant-input.md). Samsung hardware
button routing remains a separate manual device check. The sections above record
the original feasibility investigation.
