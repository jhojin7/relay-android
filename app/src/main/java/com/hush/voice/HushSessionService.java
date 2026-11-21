package com.hush.voice;

import android.os.Bundle;
import android.service.voice.*;

public final class HushSessionService extends VoiceInteractionSessionService {
  // Same-process instrumentation substitutes speech, while session/window/input remain real.
  static DictationEngine.Factory recordingFactory;

  @Override
  public VoiceInteractionSession onNewSession(Bundle args) {
    return new HushSession(this, recordingFactory);
  }
}
