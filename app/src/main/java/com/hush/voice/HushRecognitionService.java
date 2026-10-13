package com.hush.voice;

import android.content.Intent;
import android.os.*;
import android.speech.*;

/**
 * The assistant metadata requires a recognition service. Delegates to a real engine, excluding Hush
 * itself to avoid recursion if Android selects it as voice input. No discovery intent filter: Hush
 * isn't advertised as an independent speech model.
 */
public final class HushRecognitionService extends RecognitionService {
  private SpeechRecognizer engine;
  private Callback client;

  interface Event {
    void send() throws RemoteException;
  }

  private void deliver(Event event) {
    try {
      event.send();
    } catch (RemoteException ignored) {
      release();
    }
  }

  private void release() {
    if (engine != null) {
      engine.destroy();
      engine = null;
    }
    client = null;
  }

  @Override
  protected void onStartListening(Intent intent, Callback callback) {
    if (engine != null) {
      deliver(() -> callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY));
      return;
    }
    client = callback;
    try {
      engine = SpeechProvider.create(this, Prefs.offline(this));
      engine.setRecognitionListener(
          new RecognitionListener() {
            public void onReadyForSpeech(Bundle b) {
              deliver(() -> callback.readyForSpeech(b));
            }

            public void onBeginningOfSpeech() {
              deliver(callback::beginningOfSpeech);
            }

            public void onRmsChanged(float v) {
              deliver(() -> callback.rmsChanged(v));
            }

            public void onBufferReceived(byte[] b) {
              deliver(() -> callback.bufferReceived(b));
            }

            public void onEndOfSpeech() {
              deliver(callback::endOfSpeech);
            }

            public void onError(int e) {
              deliver(() -> callback.error(e));
              release();
            }

            public void onResults(Bundle b) {
              deliver(() -> callback.results(b));
              release();
            }

            public void onPartialResults(Bundle b) {
              deliver(() -> callback.partialResults(b));
            }

            public void onEvent(int t, Bundle b) {}
          });
      engine.startListening(intent);
    } catch (RuntimeException e) {
      deliver(() -> callback.error(SpeechRecognizer.ERROR_CLIENT));
      release();
    }
  }

  @Override
  protected void onStopListening(Callback c) {
    if (engine != null) engine.stopListening();
  }

  @Override
  protected void onCancel(Callback c) {
    release();
  }

  @Override
  public void onDestroy() {
    release();
    super.onDestroy();
  }
}
