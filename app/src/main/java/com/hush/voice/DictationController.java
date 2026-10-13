package com.hush.voice;

interface DictationController {
  boolean active();

  default boolean busy() {
    return active();
  }

  void start(boolean pauseToSave);

  void stop();

  void cancel();

  void close();
}
