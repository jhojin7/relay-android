package com.hush.voice;

import android.app.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;

final class CloudConfigDialog {
  static void show(Activity activity, String provider, Runnable changed) {
    boolean groq = provider.equals("groq");
    LinearLayout fields = Ui.column(activity);
    Ui.pad(fields, 24, 8, 12);
    fields.addView(
        Ui.text(
            activity,
            "Audio is uploaded after recording stops. Transcripts are saved locally. Use a"
                + " transcription model supported by this provider.",
            14,
            Ui.MUTED));
    Ui.gap(fields, 18);
    EditText endpoint =
        field(
            activity,
            fields,
            "Transcription URL",
            groq
                ? Prefs.GROQ_URL
                : Prefs.get(activity).getString("custom_endpoint", Prefs.GROQ_URL),
            false);
    endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    endpoint.setEnabled(!groq);
    EditText model =
        field(
            activity,
            fields,
            "Model ID",
            Prefs.get(activity)
                .getString(provider + "_model", groq ? "whisper-large-v3-turbo" : "whisper-1"),
            false);
    if (groq) {
      LinearLayout choices = Ui.row(activity);
      choices.addView(
          Ui.button(activity, "V3 Turbo", false, () -> model.setText("whisper-large-v3-turbo")),
          new LinearLayout.LayoutParams(0, -2, 1));
      choices.addView(
          Ui.button(activity, "Large V3", false, () -> model.setText("whisper-large-v3")),
          new LinearLayout.LayoutParams(0, -2, 1));
      fields.addView(choices);
      Ui.gap(fields, 12);
    }
    EditText apiKey = field(activity, fields, "API key", "", true);
    apiKey.setHint(
        CredentialStore.has(activity, endpoint.getText().toString())
            ? "Saved key · leave blank to keep"
            : groq ? "Paste Groq API key" : "Optional for servers without authentication");
    fields.addView(
        Ui.text(
            activity,
            "Keys are encrypted using Android Keystore. A saved key is used only for its exact"
                + " endpoint.",
            12,
            Ui.MUTED));
    Ui.gap(fields, 12);
    fields.addView(
        Ui.button(
            activity,
            "Remove saved key",
            false,
            () -> {
              CredentialStore.remove(activity, endpoint.getText().toString().trim());
              apiKey.setText("");
              apiKey.setHint("API key removed");
              Toast.makeText(activity, "Saved key removed", Toast.LENGTH_SHORT).show();
            }));
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(fields);
    AlertDialog dialog =
        new AlertDialog.Builder(activity)
            .setTitle(groq ? "Groq transcription" : "Custom transcription API")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String url = endpoint.getText().toString().trim(),
                          id = model.getText().toString().trim(),
                          key = apiKey.getText().toString().trim();
                      try {
                        CloudClient.endpoint(url);
                        CloudClient.validateModel(id);
                        if (groq && key.isEmpty() && !CredentialStore.has(activity, url)) {
                          apiKey.setError("Enter your Groq API key");
                          return;
                        }
                        if (!key.isEmpty()) CredentialStore.put(activity, url, key);
                        Prefs.get(activity)
                            .edit()
                            .putString("provider", provider)
                            .putString(provider + "_model", id)
                            .putString(
                                "custom_endpoint",
                                groq
                                    ? Prefs.get(activity)
                                        .getString("custom_endpoint", Prefs.GROQ_URL)
                                    : url)
                            .apply();
                        dialog.dismiss();
                        changed.run();
                      } catch (Exception e) {
                        Toast.makeText(
                                activity,
                                "Check the HTTPS URL, model ID and key",
                                Toast.LENGTH_LONG)
                            .show();
                      }
                    }));
    dialog.show();
  }

  private static EditText field(
      Activity a, LinearLayout parent, String label, String initial, boolean secret) {
    parent.addView(Ui.bold(a, label, 14, Ui.WHITE));
    EditText input = new EditText(a);
    input.setTextSize(15);
    input.setTextColor(Ui.WHITE);
    input.setHintTextColor(Ui.MUTED);
    input.setSingleLine(true);
    input.setContentDescription(label);
    input.setInputType(
        InputType.TYPE_CLASS_TEXT
            | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
    input.setText(initial);
    parent.addView(input);
    Ui.gap(parent, 12);
    return input;
  }
}
