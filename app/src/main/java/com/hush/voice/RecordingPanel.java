package com.hush.voice;

import android.content.Context;
import android.os.*;
import android.view.*;
import android.widget.*;

/** Shared UI for in-app dictation and the system assistant's bottom sheet. */
final class RecordingPanel extends LinearLayout implements DictationEngine.Listener {
  private final DictationController engine;
  private Runnable observer;
  private final TextView status, transcript, hint, time, done, again, discard;
  private final Ui.Wave wave;
  private final LinearLayout actions;
  private final Runnable dismiss;
  private final Handler clock = new Handler(Looper.getMainLooper());
  private long started;
  private boolean busy, closed;
  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (closed) return;
          long s = (SystemClock.elapsedRealtime() - started) / 1000;
          time.setText(String.format(java.util.Locale.ROOT, "%02d:%02d", s / 60, s % 60));
          if (engine.active()) clock.postDelayed(this, 1000);
        }
      };

  RecordingPanel(Context c, Runnable close) {
    this(c, close, null);
  }

  RecordingPanel(Context c, Runnable close, DictationEngine.Factory factory) {
    super(c);
    engine =
        factory != null
            ? new DictationEngine(c, this, factory)
            : Prefs.cloud(c) ? new CloudDictationEngine(c, this) : new DictationEngine(c, this);
    dismiss = close;
    setOrientation(VERTICAL);
    setBackground(Ui.shape(c, Ui.CARD, 32));
    Ui.pad(this, 24, 12, 24);
    View handle = new View(c);
    handle.setBackground(Ui.shape(c, Ui.LINE, 4));
    LayoutParams hp = new LayoutParams(Ui.dp(c, 36), Ui.dp(c, 4));
    hp.gravity = Gravity.CENTER_HORIZONTAL;
    hp.bottomMargin = Ui.dp(c, 20);
    addView(handle, hp);
    LinearLayout top = Ui.row(c);
    Ui.Icon mark = new Ui.Icon(c, "logo", Ui.LIME);
    top.addView(mark, new LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)));
    TextView brand = Ui.bold(c, "  Relay", 19, Ui.WHITE);
    Ui.weighted(top, brand);
    TextView x =
        Ui.button(
            c,
            "Close",
            false,
            () -> {
              if (engineActive()) engine.stop();
              else dismiss.run();
            });
    top.addView(x);
    addView(top);
    Ui.gap(this, 24);
    status = Ui.bold(c, "Ready", 28, Ui.WHITE);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    addView(status);
    hint = Ui.text(c, "Tap to start, or hold the mic to talk.", 14, Ui.MUTED);
    addView(hint);
    Ui.gap(this, 12);
    wave = new Ui.Wave(c);
    wave.setVisibility(GONE);
    addView(wave, new LayoutParams(-1, Ui.dp(c, 64)));
    LinearLayout metadata = Ui.row(c);
    TextView mode =
        Ui.text(
            c,
            Prefs.description(c) + "  ·  " + Prefs.language(c).toUpperCase(java.util.Locale.ROOT),
            11,
            Ui.MUTED);
    mode.setLetterSpacing(.08f);
    Ui.weighted(metadata, mode);
    time = Ui.text(c, "00:00", 12, Ui.MUTED);
    metadata.addView(time);
    addView(metadata);
    Ui.gap(this, 16);
    ScrollView scroll = new ScrollView(c);
    scroll.setFillViewport(true);
    transcript =
        Ui.text(
            c,
            Prefs.cloud(c) ? "Transcript appears after recording." : "Transcript appears here.",
            22,
            Ui.MUTED);
    transcript.setTextIsSelectable(true);
    Ui.pad(transcript, 0, 6, 12);
    scroll.addView(transcript);
    addView(scroll, new LayoutParams(-1, Ui.dp(c, 145)));
    Ui.gap(this, 20);
    actions = Ui.row(c);
    discard =
        Ui.button(
            c,
            "Discard",
            false,
            () -> {
              engine.cancel();
              dismiss.run();
            });
    actions.addView(discard, new LayoutParams(0, -2, 1));
    View spacer = new View(c);
    actions.addView(spacer, new LayoutParams(Ui.dp(c, 12), 1));
    done =
        Ui.button(
            c,
            "Hold or tap to talk",
            true,
            () -> {
              if (engine.active()) engine.stop();
              else start(false);
            });
    actions.addView(done, new LayoutParams(0, -2, 1));
    addView(actions);
    LinearLayout tools = Ui.row(c);
    Ui.gap(this, 10);
    again = Ui.button(c, "Close recorder", false, dismiss);
    tools.addView(again, new LayoutParams(0, -2, 1));
    TextView copy =
        Ui.button(
            c,
            "Copy text",
            false,
            () -> {
              String text = transcript.getText().toString();
              if (!text.trim().isEmpty()) {
                Prefs.copy(c, text);
                Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show();
              }
            });
    tools.addView(copy, new LayoutParams(0, -2, 1));
    tools.setVisibility(GONE);
    addView(tools);
    done.setOnTouchListener(
        new OnTouchListener() {
          boolean held;
          final Handler h = new Handler(Looper.getMainLooper());
          final Runnable hold =
              () -> {
                if (!closed && !engine.busy()) {
                  held = true;
                  Ui.feedback(done);
                  start(false);
                }
              };

          public boolean onTouch(View v, android.view.MotionEvent e) {
            if (busy) return true;
            switch (e.getActionMasked()) {
              case MotionEvent.ACTION_DOWN -> {
                held = false;
                v.animate().scaleX(.96f).scaleY(.96f).setDuration(100);
                if (!engine.active()) h.postDelayed(hold, 220);
                return true;
              }
              case MotionEvent.ACTION_UP -> {
                h.removeCallbacks(hold);
                v.animate().scaleX(1).scaleY(1).setDuration(160);
                if (held) engine.stop();
                else v.performClick();
                return true;
              }
              case MotionEvent.ACTION_CANCEL -> {
                h.removeCallbacks(hold);
                v.animate().scaleX(1).scaleY(1).setDuration(160);
                if (held) engine.stop();
                return true;
              }
            }
            return false;
          }
        });
    Ui.enter(this, 0);
  }

  boolean engineActive() {
    return engine != null && engine.active();
  }

  void start(boolean pauseToSave) {
    if (closed || engine.busy()) return;
    busy = false;
    discard.setText("Discard");
    ((View) again.getParent()).setVisibility(GONE);
    actions.setVisibility(VISIBLE);
    transcript.setText(
        Prefs.cloud(getContext())
            ? "Transcript appears after recording."
            : "Transcript appears here.");
    transcript.setTextColor(Ui.MUTED);
    started = SystemClock.elapsedRealtime();
    engine.start(pauseToSave);
    if (engine.active()) {
      clock.removeCallbacks(tick);
      clock.post(tick);
      hint.setText(
          pauseToSave
              ? "Speak naturally. A pause saves your note."
              : "Tap Done to save, or release if you're holding.");
    }
  }

  @Override
  public void state(String s, boolean active) {
    status.setText(s);
    wave.active(active);
    busy = s.equals("Finishing…") || s.equals("Transcribing…");
    discard.setEnabled(!busy);
    wave.setVisibility(active ? VISIBLE : GONE);
    done.setText(active ? "Done & save" : busy ? "Saving…" : "Start dictation");
    done.setEnabled(!busy);
    notifyObserver();
  }

  @Override
  public void text(String text) {
    transcript.setText(text);
    transcript.setTextColor(Ui.WHITE);
  }

  @Override
  public void level(float l) {
    wave.level(l);
  }

  @Override
  public void saved(String text, long id, boolean provisional) {
    busy = false;
    wave.active(false);
    clock.removeCallbacks(tick);
    status.setText("Saved");
    hint.setText(
        provisional
            ? "Saved locally. Review the last few words."
            : "Saved locally" + (Prefs.autoCopy(getContext()) ? " and copied." : "."));
    transcript.setText(text);
    transcript.setTextColor(Ui.WHITE);
    actions.setVisibility(VISIBLE);
    discard.setText("Close");
    done.setEnabled(true);
    done.setText("Hold or tap to talk");
    ((View) again.getParent()).setVisibility(VISIBLE);
    Ui.feedback(this);
    discard.setEnabled(true);
    wave.setVisibility(GONE);
    notifyObserver();
  }

  @Override
  public void error(String message) {
    busy = false;
    wave.active(false);
    clock.removeCallbacks(tick);
    status.setText("Recording error");
    hint.setText(message);
    done.setEnabled(true);
    done.setText("Try again");
    discard.setEnabled(true);
    wave.setVisibility(GONE);
    notifyObserver();
  }

  void observe(Runnable callback) {
    observer = callback;
  }

  boolean busy() {
    return engine.busy();
  }

  boolean processing() {
    return busy;
  }

  void stop() {
    engine.stop();
  }

  void discard() {
    engine.cancel();
  }

  private void notifyObserver() {
    if (observer != null) observer.run();
  }

  void close() {
    if (closed) return;
    closed = true;
    observer = null;
    clock.removeCallbacksAndMessages(null);
    engine.close();
    wave.active(false);
  }
}
