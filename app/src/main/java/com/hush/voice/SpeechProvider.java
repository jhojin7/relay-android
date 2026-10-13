package com.hush.voice;

import android.content.*;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.speech.SpeechRecognizer;

final class SpeechProvider {
  static ComponentName system(Context c) {
    String selected =
        Settings.Secure.getString(c.getContentResolver(), "voice_recognition_service");
    ComponentName preferred = selected == null ? null : ComponentName.unflattenFromString(selected);
    ComponentName first = null;
    for (ResolveInfo r :
        c.getPackageManager()
            .queryIntentServices(new Intent("android.speech.RecognitionService"), 0)) {
      if (r.serviceInfo.packageName.equals(c.getPackageName())) continue;
      ComponentName n = new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
      if (n.equals(preferred)) return n;
      if (first == null) first = n;
    }
    return first;
  }

  static SpeechRecognizer create(Context c, boolean local) {
    if (local) return SpeechRecognizer.createOnDeviceSpeechRecognizer(c);
    ComponentName provider = system(c);
    if (provider == null) throw new IllegalStateException("No external speech provider");
    return SpeechRecognizer.createSpeechRecognizer(c, provider);
  }
}
