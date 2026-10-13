package com.hush.voice;

import android.os.Bundle;
import android.service.voice.*;

public final class HushSessionService extends VoiceInteractionSessionService {
  @Override
  public VoiceInteractionSession onNewSession(Bundle args) {
    return new HushSession(this);
  }
}
