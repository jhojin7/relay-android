package com.hush.voice;

import android.content.*;
import java.util.Locale;

final class Prefs {
  static final String GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions";

  static android.content.SharedPreferences get(Context c) {
    return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
  }

  static String language(Context c) {
    return get(c).getString("language", Locale.getDefault().toLanguageTag());
  }

  static String provider(Context c) {
    return get(c).getString("provider", get(c).getBoolean("offline", true) ? "device" : "system");
  }

  static boolean offline(Context c) {
    return provider(c).equals("device");
  }

  static boolean cloud(Context c) {
    String p = provider(c);
    return p.equals("groq") || p.equals("custom");
  }

  static String endpoint(Context c) {
    return provider(c).equals("groq") ? GROQ_URL : get(c).getString("custom_endpoint", GROQ_URL);
  }

  static String model(Context c) {
    return get(c).getString(provider(c) + "_model", "whisper-large-v3-turbo");
  }

  static String providerName(Context c) {
    return switch (provider(c)) {
      case "groq" -> "Groq";
      case "custom" -> "Custom API";
      case "system" -> "Android system";
      default -> "On-device";
    };
  }

  static String description(Context c) {
    return providerName(c) + (cloud(c) ? " · " + model(c) : "");
  }

  static boolean autoCopy(Context c) {
    return get(c).getBoolean("copy", false);
  }

  static boolean keepRecordings(Context c) {
    return get(c).getBoolean("keep_recordings", false);
  }

  static void copy(Context c, String text) {
    ((ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE))
        .setPrimaryClip(ClipData.newPlainText("Relay transcript", text));
  }
}
