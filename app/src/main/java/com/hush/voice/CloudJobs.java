package com.hush.voice;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Requests outlive dismissed recording UIs. SQLite commits prevent duplicate retry notes. */
final class CloudJobs {
  interface Result {
    void complete(String text, long noteId);

    void failed(String message);
  }

  static final ExecutorService workers = Executors.newFixedThreadPool(2);
  private static final Set<String> active = ConcurrentHashMap.newKeySet();
  private static final Object storageLock = new Object();

  static void changed(Context c) {
    c.sendBroadcast(
        new android.content.Intent("com.hush.voice.NOTES_CHANGED").setPackage(c.getPackageName()),
        "com.hush.voice.INTERNAL");
  }

  static boolean busy(String id) {
    return active.contains(id);
  }

  static boolean any() {
    return !active.isEmpty();
  }

  static File directory(Context c) {
    File d = new File(c.getFilesDir(), "pending_audio");
    if (!d.isDirectory() && !d.mkdirs())
      throw new IllegalStateException("Couldn't create audio storage");
    return d;
  }

  static void submit(Context context, NoteStore.Pending p, CloudClient client, Result callback) {
    if (!active.add(p.id)) {
      callback.failed("This recording is already being transcribed.");
      return;
    }
    Context app = context.getApplicationContext();
    workers.execute(
        () -> {
          String text = null, error = null;
          long noteId = -1;
          File audio = new File(directory(app), p.file);
          try (NoteStore store = new NoteStore(app)) {
            store.pendingState(p.id, "uploading", p.seconds);
            try {
              String key = CredentialStore.get(app, p.endpoint);
              if (p.endpoint.equals(Prefs.GROQ_URL) && key.isEmpty())
                throw new IOException(
                    "No API key for this endpoint. Configure it in Settings, then retry.");
              text = client.transcribe(audio, p.endpoint, p.model, key, p.language);
              synchronized (storageLock) {
                noteId = store.completePending(p, text);
                if (noteId > 0 && !p.retain) audio.delete();
              }
            } catch (Exception e) {
              store.pendingState(p.id, "failed", p.seconds);
              error =
                  e instanceof IOException
                      ? e.getMessage()
                      : "Transcription failed. Audio is kept locally; check Settings and retry.";
            }
          } catch (Exception e) {
            error = "Couldn't save the transcription. Audio is kept locally.";
          } finally {
            active.remove(p.id);
          }
          String result = text, message = error;
          long id = noteId;
          new Handler(Looper.getMainLooper())
              .post(
                  () -> {
                    if (message != null) callback.failed(message);
                    else if (id > 0) {
                      if (Prefs.autoCopy(app)) Prefs.copy(app, result);
                      callback.complete(result, id);
                    } else callback.failed("Recording is no longer pending.");
                    changed(app);
                  });
        });
  }

  static void recover(Context c) {
    synchronized (storageLock) {
      recoverLocked(c);
    }
  }

  private static void recoverLocked(Context c) {
    if (RecordingOwnership.capturing()) return;
    Set<String> keep = new HashSet<>();
    File directory = directory(c);
    try (NoteStore store = new NoteStore(c)) {
      keep.addAll(store.evaluationFiles());
      for (NoteStore.Pending p : store.pending()) {
        keep.add(p.file);
        if (busy(p.id)) continue;
        File audio = new File(directory, p.file);
        try {
          int seconds = WavFile.repair(audio);
          if (p.state.equals("recording") || p.state.equals("uploading"))
            store.pendingState(p.id, "ready", seconds);
        } catch (IOException e) {
          audio.delete();
          store.removePending(p.id);
        }
      }
    }
    File[] files = directory.listFiles();
    if (files != null) for (File f : files) if (!keep.contains(f.getName())) f.delete();
  }
}
