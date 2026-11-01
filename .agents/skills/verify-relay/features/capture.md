# Capture and recovery

## Sub-features

- Home touch-down starts capture; release stops it.
- Recorder tap starts continuous capture; hold/release and Done stop it.
- Assistant opens and starts the recorder; power-button release is not delivered.
- Cloud capture writes recoverable WAV and uploads only after capture stops.
- Failed/interrupted audio appears in Pending recordings for retry or deletion.

## How to get to it (user POV)

Home → hold Hold to dictate. Keyboard/screen-reader activation opens Recorder.
Choose Relay as default assistant in Android Settings before invoking assistant.
Settings → choose cloud provider; Home → Pending recordings → Retry or Delete.

## Driving it with control.py

`test` covers home touch-down/release, recorder tap/hold, dismissal, final results,
failure/retry, WAV repair and recovery through the real Android runtime. Inspect
the individual test names and zero failures. `launch`, `ui`, `screenshot home`
prove the idle home has no recording waveform. For live capture configure a real
provider/key and microphone, hold the visible home control, release and confirm
the note through Notes. Verify pending state after an actual failed request.

## Gotchas

- Synthetic runtime boundaries prove behavior, not remote transcription accuracy.
- Emulator Android speech may lack usable language models. Do not infer a product
  regression from that prerequisite failure; record the attempted route.
- Samsung side-button configuration is phone-specific and not proved by this AVD.
- Never run this suite on the user's phone or alter their assistant selection.
