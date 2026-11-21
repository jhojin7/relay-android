package com.hush.voice;

import static com.hush.voice.HushTests.*;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/**
 * Real accessibility binding, editor connections, keyboard and assistant windows on a disposable
 * AVD.
 */
public final class AssistantInputTests {
  private final Instrumentation instrumentation;
  private Context context;
  private UiAutomation automation;
  private InputTestActivity host;
  private String accessibility, enabled, assistant, voiceService, keyboard;
  private final RelayAccessibilityService.Request[] captured =
      new RelayAccessibilityService.Request[1];
  private HushTests.Fake fake;

  AssistantInputTests(Instrumentation instrumentation) {
    this.instrumentation = instrumentation;
  }

  void setUp() throws Exception {
    context = instrumentation.getTargetContext();
    automation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    accessibility = setting("enabled_accessibility_services");
    enabled = setting("accessibility_enabled");
    assistant = setting("assistant");
    voiceService = setting("voice_interaction_service");
    keyboard = setting("default_input_method");
    automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
    put("enabled_accessibility_services", "");
    put("accessibility_enabled", "0");
    await(
        () ->
            !RelayAccessibilityService.available()
                && context
                    .getSystemService(android.view.accessibility.AccessibilityManager.class)
                    .getEnabledAccessibilityServiceList(
                        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .isEmpty(),
        "accessibility reset");
    put("enabled_accessibility_services", "com.hush.voice/.RelayAccessibilityService");
    put("accessibility_enabled", "1");
    put("assistant", "com.hush.voice/.HushAssistant");
    put("voice_interaction_service", "com.hush.voice/.HushAssistant");
    main(
        () -> {
          Prefs.get(context).edit().clear().putBoolean("assistant_input", true).commit();
          fake = new HushTests.Fake();
          HushSessionService.recordingFactory = fake;
          try (NoteStore store = new NoteStore(context)) {
            for (NoteStore.Note note : store.list("")) store.delete(note.id);
          }
        });
    await(() -> RelayAccessibilityService.available(), "accessibility binding");
    host =
        (InputTestActivity)
            instrumentation.startActivitySync(
                new Intent(context, InputTestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    await(
        () ->
            host.getWindow().getDecorView().getRootWindowInsets() != null
                && host.getWindow()
                    .getDecorView()
                    .getRootWindowInsets()
                    .isVisible(WindowInsets.Type.ime()),
        "regular keyboard visible");
    await(
        () -> {
          captured[0] = RelayAccessibilityService.capture(context);
          if (captured[0] == null) return false;
          captured[0].cancel();
          return true;
        },
        "accessibility input connection");
    captured[0] = null;
  }

  void tearDown() {
    main(
        () -> {
          if (captured[0] != null) captured[0].cancel();
          HushSessionService.recordingFactory = null;
          if (host != null) host.finish();
          Prefs.get(context).edit().clear().commit();
        });
    if (automation != null) {
      put("enabled_accessibility_services", accessibility);
      put("accessibility_enabled", enabled);
      put("assistant", assistant);
      put("voice_interaction_service", voiceService);
      automation.dropShellPermissionIdentity();
    }
  }

  public void testRealConnectionInsertsAtCursorReplacesSelectionAndKeepsKeyboard() {
    capture();
    main(
        () -> {
          captured[0].deliver("spoken ");
          captured[0].deliver("duplicate");
        });
    await(() -> host.first.getText().toString().equals("Before spoken after"), "cursor insertion");
    assertEquals(keyboard, setting("default_input_method"));
    main(() -> host.first.setSelection(7, 13));
    settle();
    capture();
    main(() -> captured[0].deliver("replaced"));
    await(
        () -> host.first.getText().toString().equals("Before replaced after"),
        "selection replacement");
    assertEquals(keyboard, setting("default_input_method"));
  }

  public void testChangedDraftCursorFieldAndPermissionNeverReceiveLateText() {
    capture();
    main(() -> host.first.setSelection(0));
    reject();
    capture();
    main(() -> host.first.setText("Changed draft"));
    reject();
    capture();
    main(() -> host.second.requestFocus());
    reject();
    main(() -> host.first.requestFocus());
    settle();
    capture();
    android.app.Activity other =
        instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    reject();
    main(other::finish);
    settle();
    capture();
    main(() -> Prefs.get(context).edit().putBoolean("assistant_input", false).commit());
    reject();
    main(() -> assertNull(RelayAccessibilityService.capture(context)));
  }

  public void testPasswordAndComposingText() {
    main(
        () -> {
          host.second.setInputType(
              InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
          host.second.requestFocus();
        });
    settle();
    main(() -> assertNull(RelayAccessibilityService.capture(context)));
    main(
        () -> {
          host.first.setText("한글");
          host.first.setSelection(2);
          host.first.requestFocus();
        });
    settle();
    main(
        () -> {
          BaseInputConnection composing =
              new BaseInputConnection(host.first, true) {
                @Override
                public android.text.Editable getEditable() {
                  return host.first.getText();
                }
              };
          composing.setComposingRegion(1, 2);
        });
    settle();
    capture();
    main(() -> captured[0].deliver(" 입력"));
    await(() -> host.first.getText().toString().equals("한글 입력"), "composing draft preserved");
  }

  public void testAssistantWindowReturnsToOriginalEditorAfterLocalCommit() throws Exception {
    main(
        () -> {
          host.first.setText("Before  after");
          host.first.setSelection(7);
        });
    settle();
    String shown = shell("input keyevent 219");
    if (shown.contains("failed") || shown.contains("ERROR") || shown.contains("false")) {
      throw new AssertionError("Assistant command: " + shown);
    }
    await(() -> fake.listener != null, "assistant recorder started");
    screenshot("assistant-recording");
    main(() -> fake.result("dictated "));
    await(
        () -> host.first.getText().toString().equals("Before dictated after"), "assistant handoff");
    main(
        () -> {
          try (NoteStore store = new NoteStore(context)) {
            assertEquals(1, store.list("").size());
            assertEquals("dictated", store.list("").get(0).text);
          }
        });
    assertEquals(keyboard, setting("default_input_method"));
    screenshot("assistant-inserted");
  }

  public void testDismissedAssistantDoesNotInsertLateResult() throws Exception {
    shell("input keyevent 219");
    await(() -> fake.listener != null, "assistant recorder started");
    main(() -> fake.partial("Recovered words"));
    // The IME may consume the first Back. Wait for actual recorder dismissal, not just key
    // dispatch.
    shell("input keyevent 4");
    SystemClock.sleep(400);
    if (fake.destroys == 0) shell("input keyevent 4");
    await(() -> fake.destroys > 0, "assistant dismissed");
    main(() -> fake.result("Late result"));
    SystemClock.sleep(2300);
    main(
        () -> {
          assertEquals("Before after", host.first.getText().toString());
          try (NoteStore store = new NoteStore(context)) {
            assertEquals(1, store.list("").size());
            assertEquals("Recovered words", store.list("").get(0).text);
          }
        });
  }

  public void testDisabledServiceRejectsPendingDelivery() {
    capture();
    put("enabled_accessibility_services", "");
    put("accessibility_enabled", "0");
    await(() -> !RelayAccessibilityService.available(), "accessibility disconnected");
    reject();
  }

  private void capture() {
    settle();
    main(
        () -> {
          captured[0] = RelayAccessibilityService.capture(context);
          assertNotNull(captured[0]);
        });
  }

  private void reject() {
    settle();
    String[] before = new String[2];
    main(
        () -> {
          before[0] = host.first.getText().toString();
          before[1] = host.second.getText().toString();
          captured[0].deliver("UNEXPECTED");
        });
    SystemClock.sleep(2300);
    main(
        () -> {
          assertEquals(before[0], host.first.getText().toString());
          assertEquals(before[1], host.second.getText().toString());
        });
  }

  private String setting(String name) {
    return Settings.Secure.getString(context.getContentResolver(), name);
  }

  private void screenshot(String name) throws Exception {
    settle();
    SystemClock.sleep(300);
    File directory = new File(context.getFilesDir(), "verification");
    directory.mkdirs();
    android.graphics.Bitmap bitmap = automation.takeScreenshot();
    assertNotNull(bitmap);
    try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      bitmap.recycle();
    }
  }

  private void put(String name, String value) {
    Settings.Secure.putString(context.getContentResolver(), name, value);
  }

  private void main(Runnable action) {
    Throwable[] failure = {null};
    instrumentation.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure[0] = error;
          }
        });
    if (failure[0] != null) throw new AssertionError("Main-thread test failed", failure[0]);
  }

  private void settle() {
    instrumentation.waitForIdleSync();
    SystemClock.sleep(250);
  }

  private void await(BooleanSupplier condition, String description) {
    long deadline = SystemClock.elapsedRealtime() + 10000;
    boolean[] done = {false};
    do {
      main(() -> done[0] = condition.getAsBoolean());
      if (done[0]) return;
      SystemClock.sleep(100);
    } while (SystemClock.elapsedRealtime() < deadline);
    String[] editor = {""};
    main(() -> editor[0] = host == null ? "" : host.first.getText().toString());
    throw new AssertionError("Timed out: " + description + "; synthetic editor=" + editor[0]);
  }

  private String shell(String command) throws Exception {
    try (ParcelFileDescriptor fd = automation.executeShellCommand(command);
        FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
