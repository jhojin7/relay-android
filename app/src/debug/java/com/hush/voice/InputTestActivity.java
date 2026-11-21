package com.hush.voice;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Synthetic editor fixture. Never packaged in release builds. */
public final class InputTestActivity extends Activity {
  EditText first, second;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    first = new EditText(this);
    first.setId(R.id.input_test_first);
    first.setText("Before after");
    first.setSelection(7);
    layout.addView(first);
    second = new EditText(this);
    second.setId(R.id.input_test_second);
    second.setText("Other field");
    layout.addView(second);
    setContentView(layout);
    first.requestFocus();
  }
}
