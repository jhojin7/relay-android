# Transcript history

## Sub-features

- Saved notes persist across an in-place update, including legacy Hush data.
- Notes supports literal search, editing, copy/share, TXT export and deletion.
- Interrupted Android dictation has a recoverable draft.

## How to get to it (user POV)

Notes → search or open a transcript. Use Save changes, Copy, Share, Export or
Delete in the editor. Export at the top of Notes exports all notes as TXT;
Settings → Storage → Export transcripts is the second entry point.

## Driving it with control.py

`test` verifies v1/v2 additive migrations, drafts and literal search/edit/delete.
The evaluation UI test edits a synthetic transcript through the real editor.
`launch`, `click Notes`, `ui` locates the resulting note; open it and inspect the
saved correction. For TXT export drive either entry point, use the native Save
dialog and reopen the output; assert timestamp, language and current transcript.
Capture editor and note-list screenshots and confirm DB preservation alongside UI.

## Gotchas

- Package `com.hush.voice`, database `hush.db`, assistant components and release
  signing identity must stay compatible. Debug signing cannot replace a signed
  release directly. Do not uninstall the user's app to work around this.
- Only evaluation-enabled cloud notes preserve the original text separately.
- TXT export contains current transcripts; paired originals belong to ZIP export.
- Delete confirmation removes saved evaluation data too; exported copies remain.
