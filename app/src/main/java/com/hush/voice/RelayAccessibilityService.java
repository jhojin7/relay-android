package com.hush.voice;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.InputMethod;
import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

/** User-enabled, API 33+ input connection; never changes the selected keyboard. */
@TargetApi(33)
public final class RelayAccessibilityService extends AccessibilityService {
  private static RelayAccessibilityService connected;
  private final Handler main = new Handler(Looper.getMainLooper());
  private Request request;
  private final BroadcastReceiver screenOff =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          cancelCurrent();
        }
      };
  private boolean receiverRegistered;

  static boolean available() {
    return connected != null;
  }

  static void cancelPending() {
    if (connected != null) connected.cancelCurrent();
  }

  /** Only called at the beginning of an explicit assistant invocation, before its UI opens. */
  static Request capture(Context context) {
    RelayAccessibilityService service = connected;
    if (service == null || !Prefs.assistantInput(context) || service.locked()) return null;
    service.cancelCurrent();
    InputMethod input = service.getInputMethod();
    EditorInfo editor = input == null ? null : input.getCurrentInputEditorInfo();
    if (!supported(editor) || input.getCurrentInputConnection() == null) return null;
    AccessibilityNodeInfo node = service.focusedEditor();
    if (node == null
        || node.isPassword()
        || !node.isEditable()
        || !node.isEnabled()
        || !editor.packageName.contentEquals(
            node.getPackageName() == null ? "" : node.getPackageName())
        || node.getText() == null
        || node.getText().length() > 65536
        || node.getTextSelectionStart() < 0
        || node.getTextSelectionEnd() < 0) return null;
    service.request = new Request(service, node, editor);
    return service.request;
  }

  private static boolean supported(EditorInfo editor) {
    if (editor == null || editor.packageName == null) return false;
    int type = editor.inputType;
    if ((type & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false;
    int variation = type & InputType.TYPE_MASK_VARIATION;
    return variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
        && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
  }

  private AccessibilityNodeInfo focusedEditor() {
    for (AccessibilityWindowInfo window : getWindows()) {
      if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused())
        continue;
      AccessibilityNodeInfo root = window.getRoot();
      return root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
    }
    return null;
  }

  @Override
  protected void onServiceConnected() {
    connected = this;
    if (!receiverRegistered) {
      registerReceiver(
          screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
      receiverRegistered = true;
    }
  }

  private boolean locked() {
    return getSystemService(KeyguardManager.class).isKeyguardLocked();
  }

  @Override
  public InputMethod onCreateInputMethod() {
    return new InputMethod(this) {
      @Override
      public void onStartInput(EditorInfo editor, boolean restarting) {
        if (request != null && !request.matches(editor)) request.cancel();
      }

      @Override
      public void onUpdateSelection(int oldStart, int oldEnd, int start, int end, int a, int b) {
        if (request != null && (start != request.start || end != request.end)) request.cancel();
      }
    };
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    Request current = request;
    if (current == null || event.getEventTime() < current.capturedAt) return;
    int type = event.getEventType();
    if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      for (AccessibilityWindowInfo window : getWindows()) {
        if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
            && window.isFocused()
            && window.getId() != current.node.getWindowId()) {
          current.cancel();
          return;
        }
      }
    } else {
      AccessibilityNodeInfo source = event.getSource();
      if (source == null) return;
      if ((type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && source.equals(current.node))
          || (type == AccessibilityEvent.TYPE_VIEW_FOCUSED && !source.equals(current.node))) {
        current.cancel();
      } else if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
          && source.equals(current.node)
          && (event.getFromIndex() != current.start || event.getToIndex() != current.end)) {
        current.cancel();
      }
    }
  }

  private void cancelCurrent() {
    if (request != null) request.cancel();
  }

  @Override
  public void onInterrupt() {
    cancelCurrent();
  }

  @Override
  public void onDestroy() {
    cancelCurrent();
    main.removeCallbacksAndMessages(null);
    if (receiverRegistered) unregisterReceiver(screenOff);
    if (connected == this) connected = null;
    super.onDestroy();
  }

  /** A one-shot operation. No target, text, or pending insertion survives process death. */
  static final class Request {
    private final RelayAccessibilityService owner;
    private final String packageName;
    private final int fieldId, start, end;
    private final long expires = SystemClock.elapsedRealtime() + 360000;
    private final long capturedAt = SystemClock.uptimeMillis();
    private AccessibilityNodeInfo node;
    private String original;
    private boolean delivered;

    private Request(
        RelayAccessibilityService owner, AccessibilityNodeInfo node, EditorInfo editor) {
      this.owner = owner;
      this.node = node;
      packageName = editor.packageName;
      fieldId = editor.fieldId;
      start = node.getTextSelectionStart();
      end = node.getTextSelectionEnd();
      original = node.getText().toString();
    }

    private boolean matches(EditorInfo editor) {
      return supported(editor)
          && packageName.equals(editor.packageName)
          && fieldId == editor.fieldId;
    }

    void cancel() {
      if (owner.request == this) owner.request = null;
      node = null;
      original = null;
    }

    /** Called once after local commit and intentional dismissal of the assistant window. */
    void deliver(String text) {
      if (delivered) return;
      delivered = true;
      long deadline = SystemClock.elapsedRealtime() + 2000;
      owner.main.postDelayed(() -> attempt(text, deadline), 100);
    }

    private void attempt(String text, long deadline) {
      if (node == null
          || connected != owner
          || !Prefs.assistantInput(owner)
          || owner.locked()
          || SystemClock.elapsedRealtime() > expires) {
        fallback();
        return;
      }
      InputMethod input = owner.getInputMethod();
      AccessibilityNodeInfo focus = owner.focusedEditor();
      if (focus == null || input == null || input.getCurrentInputConnection() == null) {
        if (SystemClock.elapsedRealtime() < deadline) {
          owner.main.postDelayed(() -> attempt(text, deadline), 100);
        } else fallback();
        return;
      }
      if (!focus.equals(node)
          || !matches(input.getCurrentInputEditorInfo())
          || !node.refresh()
          || node.isPassword()
          || !node.isFocused()
          || !node.isEditable()
          || !node.isEnabled()
          || node.getTextSelectionStart() != start
          || node.getTextSelectionEnd() != end
          || !original.contentEquals(node.getText() == null ? "" : node.getText())) {
        fallback();
        return;
      }
      // Clear before dispatch: commitText has no success acknowledgement. Never retry it.
      InputMethod.AccessibilityInputConnection connection = input.getCurrentInputConnection();
      cancel();
      try {
        connection.commitText(text, 1, null);
      } catch (RuntimeException ignored) {
        fallback();
      }
    }

    private void fallback() {
      cancel();
      Toast.makeText(owner, "Saved in Relay. Copy the transcript to continue.", Toast.LENGTH_LONG)
          .show();
    }
  }
}
