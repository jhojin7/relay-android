package com.hush.voice;

import static com.hush.voice.HushTests.*;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;

/** Real settings/editor UI; only microphone input and provider HTTP are controlled. */
public final class EvaluationUiTests {
  private final Instrumentation instrumentation;
  private final CloudTests cloud;
  private Context context;

  EvaluationUiTests(Instrumentation i) {
    instrumentation = i;
    cloud = new CloudTests(i);
  }

  void setUp() {
    cloud.setUp();
    context = instrumentation.getTargetContext();
  }

  void tearDown() {
    cloud.tearDown();
  }

  private void screenshot(String name) throws Exception {
    instrumentation.waitForIdleSync();
    instrumentation.getUiAutomation().waitForIdle(200, 5000);
    File dir = new File(context.getFilesDir(), "verification");
    assertTrue(dir.isDirectory() || dir.mkdirs());
    android.graphics.Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
    assertNotNull(bitmap);
    try (OutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
      assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
    } finally {
      bitmap.recycle();
    }
  }

  private AccessibilityNodeInfo node(String label) {
    AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
    if (root == null) return null;
    for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(label)) {
      if (label.equalsIgnoreCase(n.getText() == null ? "" : n.getText().toString())
          || label.equalsIgnoreCase(
              n.getContentDescription() == null ? "" : n.getContentDescription().toString()))
        return n;
    }
    return null;
  }

  public void testEvaluationSettingsCaptureAndEdit() throws Exception {
    cloud.groq();
    MainActivity a =
        (MainActivity)
            instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    instrumentation.waitForIdleSync();
    try {
      cloud.main(
          () -> {
            ((View) find(a.getWindow().getDecorView(), "Settings").getParent()).performClick();
          });
      instrumentation.waitForIdleSync();
      cloud.main(
          () -> {
            Switch toggle =
                (Switch) find(a.getWindow().getDecorView(), "Keep recordings for evaluation");
            assertFalse(toggle.isChecked());
            toggle.requestRectangleOnScreen(
                new android.graphics.Rect(0, 0, toggle.getWidth(), toggle.getHeight()), true);
          });
      screenshot("retention-before");
      cloud.main(
          () ->
              find(a.getWindow().getDecorView(), "Keep recordings for evaluation").performClick());
      assertTrue(Prefs.keepRecordings(context));
      screenshot("retention-enabled");
      // Feed deterministic PCM and a synthetic response through the existing production boundaries.
      CloudTests.Audio audio = new CloudTests.Audio();
      CloudDictationEngine[] engine = new CloudDictationEngine[1];
      cloud.main(
          () -> {
            engine[0] =
                new CloudDictationEngine(
                    context,
                    new HushTests.Capture(),
                    () -> audio,
                    new CloudClient(
                        url ->
                            new CloudTests.Http(
                                200, "{\"text\":\"Evaluation sample: meet at tree.\"}")));
            engine[0].start(false);
          });
      audio.waitForFrames();
      cloud.main(engine[0]::stop);
      cloud.await(
          () -> {
            try (NoteStore s = new NoteStore(context)) {
              return s.evaluationCount() == 1 && !CloudJobs.any();
            }
          });
      cloud.main(engine[0]::close);
      cloud.main(
          () -> {
            ((View) find(a.getWindow().getDecorView(), "Notes").getParent()).performClick();
            View sample = find(a.getWindow().getDecorView(), "Evaluation sample: meet at tree.");
            assertNotNull(sample);
            ((View) sample.getParent()).performClick();
          });
      instrumentation.waitForIdleSync();
      cloud.await(() -> node("Edit transcript") != null);
      Bundle edit = new Bundle();
      edit.putCharSequence(
          AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
          "Evaluation sample: meet at three.");
      assertTrue(
          node("Edit transcript").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, edit));
      screenshot("transcript-correction");
      assertTrue(node("Save changes").performAction(AccessibilityNodeInfo.ACTION_CLICK));
      cloud.await(
          () -> {
            try (NoteStore s = new NoteStore(context)) {
              return s.list("").get(0).text.equals("Evaluation sample: meet at three.");
            }
          });
      try (NoteStore s = new NoteStore(context);
          android.database.Cursor c =
              s.getReadableDatabase().rawQuery("SELECT original_text FROM evaluation", null)) {
        assertTrue(c.moveToFirst());
        assertEquals("Evaluation sample: meet at tree.", c.getString(0));
      }
      cloud.main(
          () -> {
            ((View) find(a.getWindow().getDecorView(), "Settings").getParent()).performClick();
          });
      instrumentation.waitForIdleSync();
      cloud.main(
          () -> {
            View export = find(a.getWindow().getDecorView(), "Export evaluation ZIP");
            export.requestRectangleOnScreen(
                new android.graphics.Rect(0, 0, export.getWidth(), export.getHeight()), true);
          });
      screenshot("evaluation-saved");
    } finally {
      cloud.main(a::finish);
    }
  }
}
