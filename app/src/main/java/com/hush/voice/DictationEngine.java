package com.hush.voice;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.speech.*;
import java.util.*;

/** All callbacks and state changes run on the main looper. No recording survives its UI. */
final class DictationEngine implements RecognitionListener, DictationController {
  interface Listener {
    void state(String label, boolean active);

    void text(String text);

    void level(float value);

    void saved(String text, long id, boolean provisional);

    void error(String message);
  }

  private final Context context;
  private final Listener listener;
  private final NoteStore store;
  private final Handler handler = new Handler(Looper.getMainLooper());

  interface Backend {
    void start(Intent i);

    void stop();

    void destroy();
  }

  interface Factory {
    boolean available(boolean local);

    Backend create(boolean local, RecognitionListener listener);
  }

  static boolean capturing() {
    return RecordingOwnership.capturing();
  }

  private final Factory factory;
  private Backend recognizer;
  private boolean active, stopping, inFlight, autoStop, provisionalSegments;
  private String committed = "", partial = "", language;
  private long started, lastSpeech;
  private int quietRetries;
  private final Runnable finalizeTimeout = () -> finish(true);
  private final Runnable restart = () -> listen();
  private final Runnable watchdog =
      new Runnable() {
        public void run() {
          if (!active || stopping) return;
          long now = SystemClock.elapsedRealtime();
          listener.level(0);
          if (now - started > 180000
              || (autoStop && !current().trim().isEmpty() && now - lastSpeech > 3200)) {
            stop();
            return;
          }
          if (now - started > 20000 && current().trim().isEmpty()) {
            fail("No speech detected. Try again when you're ready.");
            return;
          }
          handler.postDelayed(this, 300);
        }
      };

  DictationEngine(Context c, Listener l) {
    this(
        c,
        l,
        new Factory() {
          public boolean available(boolean local) {
            return local
                ? SpeechRecognizer.isOnDeviceRecognitionAvailable(c)
                : SpeechProvider.system(c) != null;
          }

          public Backend create(boolean local, RecognitionListener listener) {
            SpeechRecognizer r = SpeechProvider.create(c, local);
            r.setRecognitionListener(listener);
            return new Backend() {
              public void start(Intent i) {
                r.startListening(i);
              }

              public void stop() {
                r.stopListening();
              }

              public void destroy() {
                r.destroy();
              }
            };
          }
        });
  }

  DictationEngine(Context c, Listener l, Factory f) {
    context = c;
    listener = l;
    factory = f;
    store = new NoteStore(c);
  }

  public boolean active() {
    return active;
  }

  public void start(boolean pauseToSave) {
    if (active) return;
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      listener.error("Allow microphone access in Hush to start dictating.");
      return;
    }
    boolean local = Prefs.offline(context);
    if (local && !factory.available(true)) {
      listener.error(
          "On-device speech isn't available. Choose System speech in Settings, or install your"
              + " phone's speech language pack.");
      return;
    }
    if (!local && !factory.available(false)) {
      listener.error("No speech service found. Enable Speech Services by Google on your phone.");
      return;
    }
    RecordingOwnership.claim(this);
    language = Prefs.language(context);
    if (language.equals("auto")) language = Locale.getDefault().toLanguageTag();
    committed = "";
    partial = "";
    quietRetries = 0;
    stopping = false;
    provisionalSegments = false;
    autoStop = pauseToSave;
    started = lastSpeech = SystemClock.elapsedRealtime();
    active = true;
    try {
      recognizer = factory.create(local, this);
      listen();
      handler.postDelayed(watchdog, 300);
    } catch (RuntimeException e) {
      fail("Couldn't start speech recognition. Check your speech service and microphone settings.");
    }
  }

  private Intent intent() {
    return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, Prefs.offline(context));
  }

  private void listen() {
    if (!active || stopping) return;
    inFlight = true;
    listener.state(committed.trim().isEmpty() ? "Getting ready…" : "Listening", true);
    try {
      recognizer.start(intent());
    } catch (RuntimeException e) {
      fail("Speech service couldn't restart. Your words have been kept.");
    }
  }

  public void stop() {
    if (!active || stopping) return;
    stopping = true;
    handler.removeCallbacks(restart);
    listener.state("Finishing…", false);
    if (!inFlight) {
      finish(false);
      return;
    }
    handler.postDelayed(finalizeTimeout, 3500);
    try {
      recognizer.stop();
    } catch (RuntimeException e) {
      finish(true);
    }
  }

  public void cancel() {
    if (!active) return;
    active = false;
    stopping = false;
    cleanup();
    store.discardDraft();
    listener.state("Ready", false);
  }

  public void close() {
    if (active) finish(true);
    else cleanup();
    store.close();
  }

  private String current() {
    return (committed
            + (committed.trim().isEmpty() || partial.trim().isEmpty() ? "" : " ")
            + partial)
        .trim();
  }

  private int seconds() {
    return (int) ((SystemClock.elapsedRealtime() - started) / 1000);
  }

  private void snapshot() {
    String t = current();
    listener.text(t);
    if (!t.trim().isEmpty()) store.draft(t, language, seconds());
  }

  private void finish(boolean provisional) {
    if (!active) return;
    String t = current();
    int elapsed = seconds();
    active = false;
    stopping = false;
    cleanup();
    if (t.trim().isEmpty()) {
      store.discardDraft();
      listener.error("No words captured. Nothing was saved.");
      return;
    }
    try {
      long id = store.save(t, language, elapsed);
      if (Prefs.autoCopy(context)) Prefs.copy(context, t);
      listener.saved(t, id, provisional || provisionalSegments);
    } catch (RuntimeException e) {
      listener.error("Couldn't finish saving. Your recovered draft will appear when Hush reopens.");
    }
  }

  private void cleanup() {
    RecordingOwnership.release(this);
    handler.removeCallbacksAndMessages(null);
    inFlight = false;
    if (recognizer != null) {
      recognizer.destroy();
      recognizer = null;
    }
  }

  private void fail(String message) {
    if (!current().trim().isEmpty()) {
      finish(true);
      listener.error(message + " Captured text is saved locally.");
    } else {
      active = false;
      cleanup();
      listener.error(message);
    }
  }

  private static String result(Bundle b) {
    ArrayList<String> r =
        b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    return r == null || r.isEmpty() ? "" : r.get(0).trim();
  }

  @Override
  public void onReadyForSpeech(Bundle b) {
    if (active && !stopping) listener.state("Listening", true);
  }

  @Override
  public void onBeginningOfSpeech() {
    lastSpeech = SystemClock.elapsedRealtime();
    quietRetries = 0;
  }

  @Override
  public void onRmsChanged(float v) {
    if (active && !stopping) {
      listener.level(Math.max(0, Math.min(1, (v + 2) / 12)));
    }
  }

  @Override
  public void onPartialResults(Bundle b) {
    if (!active) return;
    String t = result(b);
    if (!t.trim().isEmpty()) {
      if (!t.equals(partial)) lastSpeech = SystemClock.elapsedRealtime();
      partial = t;
      snapshot();
    }
  }

  @Override
  public void onResults(Bundle b) {
    if (!active) return;
    inFlight = false;
    String t = result(b);
    if (t.trim().isEmpty()) t = partial;
    if (!t.trim().isEmpty()) {
      committed = (committed + " " + t).trim();
      partial = "";
      quietRetries = 0;
      snapshot();
    }
    if (stopping) {
      finish(false);
      return;
    }
    if (autoStop && !committed.trim().isEmpty()) {
      finish(false);
      return;
    }
    handler.postDelayed(restart, 150);
  }

  @Override
  public void onError(int code) {
    if (!active) return;
    inFlight = false;
    if (stopping) {
      finish(true);
      return;
    }
    if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
      if (!partial.trim().isEmpty()) {
        committed = (committed + " " + partial).trim();
        partial = "";
        provisionalSegments = true;
        snapshot();
      }
      if (autoStop && !current().trim().isEmpty()) {
        finish(true);
        return;
      }
      if (++quietRetries <= 3) {
        handler.postDelayed(restart, 250);
        return;
      }
      fail("No speech detected. Try again.");
      return;
    }
    String message =
        switch (code) {
          case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
              "Microphone access is blocked. Enable it in Android Settings.";
          case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
              SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
              "This language isn't ready. Download its speech model from Settings, or choose"
                  + " another language.";
          case SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
              "The microphone or speech service is busy. Close other recording apps and try again.";
          case SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
              "The system speech service needs a connection. Try on-device speech or reconnect.";
          case SpeechRecognizer.ERROR_AUDIO ->
              "Couldn't access the microphone. Check Android's microphone privacy toggle.";
          default -> "Speech recognition stopped (" + code + "). Try again.";
        };
    fail(message);
  }

  @Override
  public void onEndOfSpeech() {}

  @Override
  public void onBufferReceived(byte[] b) {}

  @Override
  public void onEvent(int t, Bundle b) {}
}
