package com.hush.voice;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.speech.*;
import android.view.*;
import android.widget.TextView;
import java.util.*;

/** Runs on a real Android runtime with a controlled speech backend. */
@SuppressWarnings("deprecation")
public final class HushTests {
  private final Instrumentation instrumentation;

  HushTests(Instrumentation i) {
    instrumentation = i;
  }

  Instrumentation getInstrumentation() {
    return instrumentation;
  }

  static void assertTrue(boolean v) {
    if (!v) throw new AssertionError("Expected true");
  }

  static void assertFalse(boolean v) {
    assertTrue(!v);
  }

  static void assertEquals(Object a, Object b) {
    if (!java.util.Objects.equals(a, b)) throw new AssertionError("Expected " + a + ", got " + b);
  }

  static void assertNull(Object v) {
    if (v != null) throw new AssertionError("Expected null: " + v);
  }

  static void assertNotNull(Object v) {
    if (v == null) throw new AssertionError("Expected non-null");
  }

  private Context context;
  private Fake fake;
  private Capture capture;
  private DictationEngine engine;

  static final class Fake implements DictationEngine.Factory, DictationEngine.Backend {
    RecognitionListener listener;
    int starts, stops, destroys;
    boolean available = true;

    public boolean available(boolean local) {
      return available;
    }

    public DictationEngine.Backend create(boolean local, RecognitionListener l) {
      listener = l;
      return this;
    }

    public void start(Intent i) {
      starts++;
      listener.onReadyForSpeech(new Bundle());
    }

    public void stop() {
      stops++;
    }

    public void destroy() {
      destroys++;
    }

    void partial(String t) {
      listener.onPartialResults(bundle(t));
    }

    void result(String t) {
      listener.onResults(bundle(t));
    }

    static Bundle bundle(String t) {
      Bundle b = new Bundle();
      b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<>(List.of(t)));
      return b;
    }
  }

  static final class Capture implements DictationEngine.Listener {
    String text = "", error = "", status = "";
    int saved;
    boolean provisional;

    public void state(String s, boolean a) {
      status = s;
    }

    public void text(String t) {
      text = t;
    }

    public void level(float v) {}

    public void saved(String t, long id, boolean p) {
      text = t;
      saved++;
      provisional = p;
      assertTrue(id > 0);
    }

    public void error(String m) {
      error = m;
    }
  }

  void main(Runnable r) {
    getInstrumentation().runOnMainSync(r);
  }

  protected void setUp() throws Exception {
    context = getInstrumentation().getTargetContext();
    context.deleteDatabase("hush.db");
    Prefs.get(context).edit().clear().commit();
    context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit();
    fake = new Fake();
    capture = new Capture();
    main(() -> engine = new DictationEngine(context, capture, fake));
  }

  protected void tearDown() throws Exception {
    main(() -> engine.close());
  }

  public void testWaitForFinalResultBeforeSaving() {
    main(
        () -> {
          engine.start(false);
          fake.partial("hello wor");
          engine.stop();
          assertEquals(1, fake.stops);
          assertEquals(0, capture.saved);
          fake.result("Hello world.");
          assertEquals("Hello world.", capture.text);
          assertEquals(1, capture.saved);
          assertFalse(capture.provisional);
          fake.result("late result");
          assertEquals(1, capture.saved);
        });
    try (NoteStore s = new NoteStore(context)) {
      assertEquals(1, s.list("").size());
      assertEquals("Hello world.", s.list("").get(0).text);
    }
  }

  public void testMultipleSpeechSegments() {
    main(
        () -> {
          engine.start(false);
          fake.result("First sentence.");
        });
    SystemClock.sleep(300);
    main(
        () -> {
          assertEquals(2, fake.starts);
          fake.partial("Second sentence");
          engine.stop();
          fake.result("Second sentence.");
          assertEquals("First sentence. Second sentence.", capture.text);
          assertEquals(1, capture.saved);
        });
  }

  public void testPauseSavesAssistantSession() {
    main(
        () -> {
          engine.start(true);
          fake.result("One thought.");
          assertEquals(1, capture.saved);
          assertFalse(engine.active());
          assertEquals(1, fake.destroys);
        });
  }

  public void testDiscardRemovesRecoveryDraft() {
    main(
        () -> {
          engine.start(false);
          fake.partial("Don't keep this");
          engine.cancel();
          assertEquals(0, capture.saved);
        });
    try (NoteStore s = new NoteStore(context)) {
      assertFalse(s.recoverDraft());
      assertEquals(0, s.list("").size());
    }
  }

  public void testInterruptionKeepsPartial() {
    main(
        () -> {
          engine.start(false);
          fake.partial("A thought worth keeping");
          engine.close();
          assertTrue(capture.provisional);
          assertEquals(1, capture.saved);
        });
    try (NoteStore s = new NoteStore(context)) {
      assertEquals("A thought worth keeping", s.list("").get(0).text);
      assertFalse(s.recoverDraft());
    }
  }

  public void testFailureKeepsWords() {
    main(
        () -> {
          engine.start(false);
          fake.partial("Keep these words");
          fake.listener.onError(SpeechRecognizer.ERROR_AUDIO);
          assertEquals(1, capture.saved);
          assertTrue(capture.error.contains("saved locally"));
        });
  }

  public void testNoMatchKeepsPartialAcrossRestart() {
    main(
        () -> {
          engine.start(false);
          fake.partial("First captured words");
          fake.listener.onError(SpeechRecognizer.ERROR_NO_MATCH);
        });
    SystemClock.sleep(350);
    main(
        () -> {
          fake.partial("second phrase");
          engine.stop();
          fake.result("second phrase.");
          assertEquals("First captured words second phrase.", capture.text);
          assertTrue(capture.provisional);
        });
  }

  public void testMissingEngineIsActionable() {
    fake.available = false;
    main(
        () -> {
          engine.start(true);
          assertFalse(engine.active());
          assertEquals(0, fake.starts);
          assertTrue(capture.error.contains("On-device"));
        });
  }

  public void testNoSpeechDoesNotCreateEmptyNote() {
    main(
        () -> {
          engine.start(false);
          engine.stop();
          fake.result("");
          assertEquals(0, capture.saved);
        });
    try (NoteStore s = new NoteStore(context)) {
      assertTrue(s.list("").isEmpty());
    }
  }

  public void testDraftRecoveryAndLiteralSearch() {
    try (NoteStore s = new NoteStore(context)) {
      s.draft("100% ready_under_score", "en-US", 5);
      assertTrue(s.recoverDraft());
      assertFalse(s.recoverDraft());
      assertEquals(1, s.list("% ready_").size());
      assertEquals(0, s.list("100_ ready").size());
      long id = s.list("").get(0).id;
      s.update(id, "Edited note");
      assertEquals("Edited note", s.list("").get(0).text);
      s.delete(id);
      assertTrue(s.list("").isEmpty());
    }
  }

  public void testActualHoldControlStartsAndStopsOnRelease() {
    RecordingPanel[] panel = new RecordingPanel[1];
    View[] mic = new View[1];
    main(
        () -> {
          panel[0] = new RecordingPanel(context, () -> {}, fake);
          mic[0] = find(panel[0], "Hold or tap to talk");
          touch(mic[0], MotionEvent.ACTION_DOWN);
        });
    SystemClock.sleep(350);
    main(
        () -> {
          assertEquals(1, fake.starts);
          fake.partial("Held microphone words");
          touch(mic[0], MotionEvent.ACTION_UP);
          assertEquals(1, fake.stops);
          fake.result("Held microphone words.");
          assertNotNull(find(panel[0], "Saved"));
          panel[0].close();
        });
    try (NoteStore s = new NoteStore(context)) {
      assertEquals("Held microphone words.", s.list("").get(0).text);
    }
  }

  public void testActualTapControlTogglesRecording() {
    main(
        () -> {
          RecordingPanel panel = new RecordingPanel(context, () -> {}, fake);
          View mic = find(panel, "Hold or tap to talk");
          touch(mic, MotionEvent.ACTION_DOWN);
          touch(mic, MotionEvent.ACTION_UP);
          assertEquals(1, fake.starts);
          assertEquals(0, fake.stops);
          assertNotNull(find(panel, "Done & save"));
          fake.partial("Tapped words");
          touch(mic, MotionEvent.ACTION_DOWN);
          touch(mic, MotionEvent.ACTION_UP);
          assertEquals(1, fake.stops);
          fake.result("Tapped words.");
          assertNotNull(find(panel, "Saved"));
          panel.close();
        });
  }

  static void touch(View v, int action) {
    MotionEvent e =
        MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, 10, 10, 0);
    v.dispatchTouchEvent(e);
    e.recycle();
  }

  public void testAssistantMetadataAndUi() {
    try (android.content.res.XmlResourceParser parser =
        context
            .getPackageManager()
            .getServiceInfo(
                new ComponentName(context, HushAssistant.class),
                android.content.pm.PackageManager.GET_META_DATA)
            .loadXmlMetaData(context.getPackageManager(), "android.voice_interaction")) {
      while (parser.next() != org.xmlpull.v1.XmlPullParser.START_TAG) {}
      assertEquals("voice-interaction-service", parser.getName());
      assertEquals(
          HushSessionService.class.getName(),
          parser.getAttributeValue("http://schemas.android.com/apk/res/android", "sessionService"));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    MainActivity activity =
        (MainActivity)
            getInstrumentation()
                .startActivitySync(
                    new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    getInstrumentation().waitForIdleSync();
    main(
        () -> {
          View shortcut = find(activity.getWindow().getDecorView(), "Shortcut");
          assertNotNull(shortcut);
          ((View) shortcut.getParent()).performClick();
          assertNotNull(find(activity.getWindow().getDecorView(), "Shortcut"));
          activity.finish();
        });
  }

  static View find(View v, String text) {
    if (v instanceof TextView t && t.getText().toString().equals(text)) return v;
    if (v instanceof ViewGroup g) {
      for (int i = 0; i < g.getChildCount(); i++) {
        View found = find(g.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }
}
