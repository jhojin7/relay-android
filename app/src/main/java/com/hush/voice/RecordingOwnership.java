package com.hush.voice;

import java.lang.ref.WeakReference;

/** Main-thread ownership; only one capture can hold the microphone. */
final class RecordingOwnership {
  private static WeakReference<DictationController> owner = new WeakReference<>(null);

  static boolean capturing() {
    DictationController e = owner.get();
    return e != null && e.active();
  }

  static void claim(DictationController next) {
    DictationController previous = owner.get();
    if (previous != null && previous != next) previous.close();
    owner = new WeakReference<>(next);
  }

  static void release(DictationController engine) {
    if (owner.get() == engine) owner.clear();
  }
}
