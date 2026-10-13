package com.hush.voice;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;

/** PCM capture + local voice-activity detection, followed by a cloud transcription request. */
final class CloudDictationEngine implements DictationController {
  interface AudioSource {
    void start();

    int read(short[] buffer);

    void stop();

    void close();
  }

  interface AudioFactory {
    AudioSource create();
  }

  private final Context context;
  private DictationEngine.Listener listener;
  private final AudioFactory factory;
  private final CloudClient client;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService capture = Executors.newSingleThreadExecutor();
  private volatile boolean stopRequested, cancelled;
  private volatile AudioSource source;
  private boolean recording, uploading, closed;
  private String endpoint, model, language;
  private long begun;

  CloudDictationEngine(Context c, DictationEngine.Listener l) {
    this(c, l, nativeAudio(c.getApplicationContext()), new CloudClient());
  }

  private static AudioFactory nativeAudio(Context c) {
    return () -> {
      if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
          != PackageManager.PERMISSION_GRANTED)
        throw new SecurityException("Microphone permission not granted");
      int min =
          AudioRecord.getMinBufferSize(
              16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      if (min < 0) throw new IllegalStateException("Unsupported microphone format");
      AudioRecord record =
          new AudioRecord.Builder()
              .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setSampleRate(16000)
                      .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .build())
              .setBufferSizeInBytes(Math.max(min * 2, 6400))
              .build();
      return new AudioSource() {
        public void start() {
          record.startRecording();
          if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
            throw new IllegalStateException("Microphone blocked");
        }

        public int read(short[] b) {
          return record.read(b, 0, b.length, AudioRecord.READ_BLOCKING);
        }

        public void stop() {
          try {
            record.stop();
          } catch (IllegalStateException ignored) {
          }
        }

        public void close() {
          record.release();
        }
      };
    };
  }

  CloudDictationEngine(Context c, DictationEngine.Listener l, AudioFactory f, CloudClient cl) {
    context = c.getApplicationContext();
    listener = l;
    factory = f;
    client = cl;
  }

  @Override
  public boolean active() {
    return recording;
  }

  @Override
  public boolean busy() {
    return recording || uploading;
  }

  @Override
  public void start(boolean pauseToSave) {
    if (closed || busy()) return;
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      error("Allow microphone access to record.");
      return;
    }
    endpoint = Prefs.endpoint(context);
    model = Prefs.model(context);
    language = Prefs.language(context);
    try {
      CloudClient.endpoint(endpoint);
      CloudClient.validateModel(model);
      if (endpoint.equals(Prefs.GROQ_URL) && CredentialStore.get(context, endpoint).isEmpty()) {
        error("Add your API key in Settings before recording.");
        return;
      }
    } catch (Exception e) {
      error("Check the API URL, model and saved key in Settings.");
      return;
    }
    RecordingOwnership.claim(this);
    recording = true;
    stopRequested = false;
    cancelled = false;
    begun = SystemClock.elapsedRealtime();
    state("Recording", true);
    capture.execute(() -> captureAudio(pauseToSave));
  }

  private void captureAudio(boolean pauseToSave) {
    long created = System.currentTimeMillis();
    String id = UUID.randomUUID().toString(), filename = id + ".wav";
    File audio = new File(CloudJobs.directory(context), filename);
    int seconds = 0;
    String problem = null;
    boolean haveAudio = false;
    try (NoteStore store = new NoteStore(context)) {
      store.addPending(
          new NoteStore.Pending(id, created, filename, endpoint, model, language, 0, "recording"));
      try (WavFile wav = new WavFile(audio)) {
        if (!stopRequested) {
          source = factory.create();
          source.start();
        }
        short[] buffer = new short[1600];
        long lastVoice = begun, lastCheckpoint = begun;
        int voicedFrames = 0;
        boolean heardVoice = false;
        float noise = .004f;
        while (!stopRequested) {
          int count = source.read(buffer);
          if (count < 0) {
            if (stopRequested) break;
            throw new IOException(
                "Microphone read failed. Check Android's microphone privacy setting.");
          }
          if (count == 0) continue;
          wav.write(buffer, count);
          double energy = 0;
          for (int i = 0; i < count; i++) energy += (double) buffer[i] * buffer[i];
          float rms = (float) Math.sqrt(energy / count) / 32768f;
          long now = SystemClock.elapsedRealtime();
          float threshold = Math.max(.012f, noise * 3);
          if (rms > threshold) {
            voicedFrames++;
            if (voicedFrames >= 2) {
              heardVoice = true;
              lastVoice = now;
            }
          } else {
            voicedFrames = 0;
            noise = noise * .97f + Math.min(rms, .015f) * .03f;
          }
          float meter = Math.min(1, rms * 12);
          main.post(
              () -> {
                if (listener != null && recording) listener.level(meter);
              });
          if (now - lastCheckpoint >= 1000) {
            wav.checkpoint();
            store.pendingState(id, "recording", wav.seconds());
            lastCheckpoint = now;
          }
          if (now - begun >= 180000 || (pauseToSave && heardVoice && now - lastVoice > 2500)) {
            stopRequested = true;
          }
          if (now - begun >= 20000 && !heardVoice) {
            problem = "No speech detected. Audio was kept locally; retry or delete it.";
            stopRequested = true;
          }
        }
        haveAudio = wav.samples() >= 1600;
        seconds = wav.seconds();
      } catch (Exception e) {
        problem =
            e instanceof IOException
                ? e.getMessage()
                : "Couldn't record from the microphone. Check permissions and Android's microphone"
                      + " privacy setting.";
        haveAudio = audio.length() > 3244;
      } finally {
        AudioSource current = source;
        source = null;
        if (current != null) {
          current.stop();
          current.close();
        }
      }
      if (cancelled || !haveAudio) {
        audio.delete();
        store.removePending(id);
      } else {
        try {
          seconds = WavFile.repair(audio);
        } catch (IOException ignored) {
        }
        store.pendingState(id, problem == null ? "ready" : "failed", seconds);
      }
      int duration = seconds;
      String message = problem;
      boolean captured = haveAudio;
      main.post(
          () -> {
            recording = false;
            RecordingOwnership.release(this);
            CloudJobs.changed(context);
            if (cancelled) {
              state("Ready", false);
              return;
            }
            if (!captured) {
              error(
                  message == null
                      ? "Recording was too short. Hold the button while speaking."
                      : message);
              return;
            }
            if (message != null) {
              error(message);
              return;
            }
            uploading = true;
            state("Transcribing…", false);
            CloudJobs.submit(
                context,
                new NoteStore.Pending(
                    id, created, filename, endpoint, model, language, duration, "ready"),
                client,
                new CloudJobs.Result() {
                  public void complete(String text, long noteId) {
                    uploading = false;
                    if (listener != null) listener.saved(text, noteId, false);
                  }

                  public void failed(String message) {
                    uploading = false;
                    error(message + " Audio is saved in Pending recordings.");
                  }
                });
          });
    } catch (Exception e) {
      main.post(
          () -> {
            recording = false;
            RecordingOwnership.release(this);
            error("Couldn't save the recording. Check free storage space.");
          });
    }
  }

  @Override
  public void stop() {
    if (!recording || stopRequested) return;
    stopRequested = true;
    state("Finishing…", false);
    AudioSource current = source;
    if (current != null) current.stop();
  }

  @Override
  public void cancel() {
    if (uploading) return;
    cancelled = true;
    stopRequested = true;
    AudioSource current = source;
    if (current != null) current.stop();
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    stop();
    listener = null;
    capture.shutdown();
  }

  private void state(String s, boolean active) {
    if (listener != null) listener.state(s, active);
  }

  private void error(String s) {
    if (listener != null) listener.error(s);
  }
}
