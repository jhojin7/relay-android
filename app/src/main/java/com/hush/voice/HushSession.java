package com.hush.voice;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.view.*;
import android.widget.*;

public final class HushSession extends VoiceInteractionSession {
  private RecordingPanel panel;
  private FrameLayout root;

  public HushSession(Context c) {
    super(c);
  }

  @Override
  public View onCreateContentView() {
    root = new FrameLayout(getContext());
    return root;
  }

  @Override
  public void onShow(Bundle args, int flags) {
    super.onShow(args, flags);
    if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      getContext()
          .startActivity(
              new Intent(getContext(), MainActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                  .putExtra("dictate", true));
      finish();
      return;
    }
    if (panel != null) panel.close();
    root.removeAllViews();
    panel = new RecordingPanel(getContext(), this::finish);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    lp.setMargins(Ui.dp(getContext(), 8), 0, Ui.dp(getContext(), 8), Ui.dp(getContext(), 12));
    root.addView(panel, lp);
    root.setOnApplyWindowInsetsListener(
        (v, i) -> {
          int bottom = i.getInsets(WindowInsets.Type.systemBars()).bottom;
          root.setPadding(0, 0, 0, bottom);
          return i;
        });
    root.requestApplyInsets();
    panel.post(
        () -> {
          if (panel != null) panel.start(true);
        });
  }

  @Override
  public void onComputeInsets(Insets out) {
    super.onComputeInsets(out);
    if (panel != null) {
      out.contentInsets.top =
          Math.max(
              0,
              root.getHeight()
                  - panel.getHeight()
                  - root.getPaddingBottom()
                  - Ui.dp(getContext(), 12));
      out.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT;
    }
  }

  @Override
  public void onHide() {
    if (panel != null) {
      panel.close();
      panel = null;
    }
    super.onHide();
  }

  @Override
  public void onDestroy() {
    if (panel != null) panel.close();
    super.onDestroy();
  }
}
