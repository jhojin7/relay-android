# Evaluation data

## Sub-features

- Off by default; only cloud captures started with retention enabled are collected.
- The original PCM WAV and transcription survive success, restart and later edits.
- Failed requests remain pending with their original retention choice for retry.
- ZIP includes original/current text, timestamp, language, duration, provider/model
  and `audio/<note_id>.wav`; credentials and endpoint URLs are excluded.
- Turning retention off affects future captures; deleting a note deletes its pair.

## How to get to it (user POV)

Settings → scroll to Evaluation data → Keep recordings for evaluation.
Choose Groq/Custom API and record. Notes → open transcript → Save changes edits
the current text. Settings → Export evaluation ZIP → choose a location → Save.
Notes → open transcript → Delete → confirm deletes retained data too.

## Driving it with control.py

1. `test com.hush.voice.EvaluationUiTests` drives the real toggle and editor,
   with controlled PCM/HTTPS boundaries. `pull-evidence` collects before/enabled,
   correction and saved-count screenshots. Assert zero failures in `ui-tests.txt`.
2. `launch`, `click Settings`, `scroll`, `ui`. Scroll until `Export evaluation ZIP`
   is visible. Capture `screenshot evaluation-settings` and click that button.
3. `ui`; capture `screenshot export-picker`. The Android document UI must show an
   editable ZIP filename and a Save action. Choose Downloads if needed, click Save.
4. `exports` pulls the real ZIP into `build/verification`. Inspect it with Python
   `zipfile`, `json` and `wave`: one JSONL record, original text ending in “tree.”,
   current text ending in “three.”, valid mono 16 kHz 16-bit audio. The fixture is
   synthetic and not an accuracy measurement. Export must leave local audio intact.
5. Open the note through Notes and confirm deletion. Settings must show zero saved
   recordings. Inspect `run-as com.hush.voice ls files/pending_audio` and the
   app-private database copy to confirm the row/file are gone. Keep the exported
   ZIP and screenshots as proof; exported copies are independent of note deletion.

## Gotchas

- A retained file stays in `pending_audio` but is owned by an evaluation row;
  recovery must keep it even though it no longer appears in Pending recordings.
- Enabling retention does not retroactively collect old notes or old pending audio.
- Latest text is not guaranteed human-corrected ground truth; it is the latest edit.
- Document picker cancel/failure must not delete local originals. Disk failure is
  covered by runtime tests; verify real picker success separately.
- Exports are outside app storage and are not removed when a note is deleted.
- No real provider credentials are shipped. Live cloud capture remains unverified
  without the user's configured account; Android speech does not retain audio.
