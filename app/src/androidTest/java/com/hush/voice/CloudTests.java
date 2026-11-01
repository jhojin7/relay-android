package com.hush.voice;

import static com.hush.voice.HushTests.*;

import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import android.view.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Real Android Keystore/SQLite/UI with controlled audio and HTTP boundaries. No live API keys. */
public final class CloudTests {
  private final Instrumentation instrumentation;
  private Context context;

  CloudTests(Instrumentation i) {
    instrumentation = i;
  }

  void main(Runnable r) {
    instrumentation.runOnMainSync(r);
  }

  void setUp() {
    context = instrumentation.getTargetContext();
    context.deleteDatabase("hush.db");
    Prefs.get(context).edit().clear().commit();
    context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit();
    File[] files = CloudJobs.directory(context).listFiles();
    if (files != null) for (File f : files) f.delete();
  }

  void tearDown() {
    await(() -> !RecordingOwnership.capturing() && !CloudJobs.any());
  }

  interface Condition {
    boolean ready();
  }

  void await(Condition c) {
    long deadline = SystemClock.elapsedRealtime() + 10000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (c.ready()) return;
      SystemClock.sleep(40);
    }
    throw new AssertionError("Timed out waiting for Android callback");
  }

  static final class Http extends HttpsURLConnection {
    final int status;
    final String body;
    final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    boolean disconnected;
    int wait;

    Http(int status, String body) throws MalformedURLException {
      super(new URL(Prefs.GROQ_URL));
      this.status = status;
      this.body = body;
    }

    @Override
    public OutputStream getOutputStream() {
      return sent;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int getResponseCode() {
      if (wait > 0) SystemClock.sleep(wait);
      return status;
    }

    @Override
    public void connect() {}

    @Override
    public void disconnect() {
      disconnected = true;
    }

    @Override
    public boolean usingProxy() {
      return false;
    }

    @Override
    public String getCipherSuite() {
      return "TEST";
    }

    @Override
    public Certificate[] getLocalCertificates() {
      return null;
    }

    @Override
    public Certificate[] getServerCertificates() {
      return new Certificate[0];
    }
  }

  static final class Audio implements CloudDictationEngine.AudioSource {
    volatile boolean stopped;
    final CountDownLatch frames = new CountDownLatch(3);

    public void start() {}

    public int read(short[] b) {
      SystemClock.sleep(50);
      if (stopped) return -3;
      Arrays.fill(b, (short) 1000);
      frames.countDown();
      return b.length;
    }

    public void stop() {
      stopped = true;
    }

    public void close() {
      stopped = true;
    }

    void waitForFrames() throws Exception {
      assertTrue(frames.await(4, TimeUnit.SECONDS));
    }
  }

  File wave(String name) throws Exception {
    File f = new File(CloudJobs.directory(context), name);
    try (WavFile wav = new WavFile(f)) {
      short[] data = new short[3200];
      Arrays.fill(data, (short) 1000);
      wav.write(data, data.length);
    }
    return f;
  }

  void groq() throws Exception {
    Prefs.get(context)
        .edit()
        .putString("provider", "groq")
        .putString("groq_model", "whisper-large-v3-turbo")
        .putString("language", "ko-KR")
        .commit();
    CredentialStore.put(context, Prefs.GROQ_URL, "test-only-token");
  }

  public void testMultipartAudioAndLanguage() throws Exception {
    Http http = new Http(200, "{\"text\":\"테스트 문장\"}");
    File audio = wave("request.wav");
    String text =
        new CloudClient(url -> http)
            .transcribe(
                audio, Prefs.GROQ_URL, "whisper-large-v3-turbo", "test-only-token", "ko-KR");
    assertEquals("테스트 문장", text);
    assertEquals("POST", http.getRequestMethod());
    assertEquals("Bearer test-only-token", http.getRequestProperty("Authorization"));
    assertFalse(http.getInstanceFollowRedirects());
    String body = http.sent.toString(StandardCharsets.ISO_8859_1.name());
    assertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-large-v3-turbo"));
    assertTrue(body.contains("name=\"language\"\r\n\r\nko\r\n"));
    assertTrue(body.contains("filename=\"recording.wav\""));
    assertTrue(body.contains("RIFF"));
    assertTrue(http.disconnected);
  }

  public void testAutoLanguageOmitsHint() throws Exception {
    Http http = new Http(200, "{\"text\":\"Auto language\"}");
    new CloudClient(url -> http)
        .transcribe(wave("auto.wav"), Prefs.GROQ_URL, "provider/model:2026", "", "auto");
    assertFalse(http.sent.toString().contains("name=\"language\""));
    assertNull(http.getRequestProperty("Authorization"));
  }

  public void testAuthenticationAndQuotaErrors() throws Exception {
    for (int code : new int[] {401, 429}) {
      Http http = new Http(code, "secret-bearing provider error");
      try {
        new CloudClient(url -> http)
            .transcribe(
                wave("error.wav"), Prefs.GROQ_URL, "whisper-large-v3", "test-only-token", "en-US");
        throw new AssertionError("Expected HTTP error");
      } catch (IOException e) {
        assertTrue(e.getMessage().contains("" + code));
        assertFalse(e.getMessage().contains("test-only-token"));
        assertFalse(e.getMessage().contains("secret-bearing"));
        assertTrue(http.disconnected);
      }
    }
  }

  public void testRedirectDoesNotForwardCredentials() throws Exception {
    Http http = new Http(307, "");
    int[] connections = {0};
    try {
      new CloudClient(
              url -> {
                connections[0]++;
                return http;
              })
          .transcribe(
              wave("redirect.wav"), Prefs.GROQ_URL, "whisper-large-v3", "test-only-token", "en-US");
      throw new AssertionError("Expected redirect refusal");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("Redirect"));
      assertEquals(1, connections[0]);
    }
  }

  public void testEmptyResponseKeepsRecording() throws Exception {
    Http http = new Http(200, "{\"text\":\"\"}");
    File file = wave("empty.wav");
    try {
      new CloudClient(url -> http)
          .transcribe(file, Prefs.GROQ_URL, "whisper-large-v3", "test-only-token", "en-US");
      throw new AssertionError("Expected missing text");
    } catch (IOException e) {
      assertTrue(file.exists());
      assertTrue(e.getMessage().contains("no transcript"));
    }
  }

  public void testModelAndHttpsValidation() throws Exception {
    for (String url :
        List.of(
            "http://localhost/audio/transcriptions",
            "https://user:secret@example.com/transcribe",
            "https://example.com/transcribe?key=x")) {
      try {
        CloudClient.endpoint(url);
        throw new AssertionError("Unsafe endpoint accepted");
      } catch (IOException expected) {
      }
    }
    try {
      CloudClient.validateModel("model\r\ninjection");
      throw new AssertionError("Unsafe model accepted");
    } catch (IOException expected) {
    }
    CloudClient.validateModel("org/whisper-v3:2026");
    assertEquals("https", CloudClient.endpoint(Prefs.GROQ_URL).getProtocol());
  }

  public void testKeystoreEncryptionAndEndpointScope() throws Exception {
    String secret = "test-only-sensitive-token";
    CredentialStore.put(context, Prefs.GROQ_URL, secret);
    assertEquals(secret, CredentialStore.get(context, Prefs.GROQ_URL));
    String encrypted =
        context
            .getSharedPreferences("credentials", Context.MODE_PRIVATE)
            .getString(Prefs.GROQ_URL, "");
    assertFalse(encrypted.contains(secret));
    String other = "https://example.com/v1/audio/transcriptions";
    assertEquals("", CredentialStore.get(context, other));
    context
        .getSharedPreferences("credentials", Context.MODE_PRIVATE)
        .edit()
        .putString(other, encrypted)
        .commit();
    try {
      CredentialStore.get(context, other);
      throw new AssertionError("Endpoint tampering accepted");
    } catch (java.security.GeneralSecurityException expected) {
    }
    CredentialStore.remove(context, Prefs.GROQ_URL);
    assertFalse(CredentialStore.has(context, Prefs.GROQ_URL));
  }

  public void testWavRepairAndPcmEncoding() throws Exception {
    File file = new File(CloudJobs.directory(context), "repair.wav");
    try (WavFile wav = new WavFile(file)) {
      short[] pcm = {1000, -1000};
      wav.write(pcm, 2);
      WavFile.repair(file);
      try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
        byte[] bytes = new byte[48];
        f.readFully(bytes);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(4, b.getInt(40));
        assertEquals((short) 1000, b.getShort(44));
        assertEquals((short) -1000, b.getShort(46));
      }
    }
    assertEquals(48L, file.length());
  }

  public void testUpgradePreservesExistingHistory() {
    SQLiteDatabase db =
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("hush.db"), null);
    db.execSQL(
        "CREATE TABLE notes (id INTEGER PRIMARY KEY, created INTEGER, text TEXT, language TEXT,"
            + " seconds INTEGER)");
    db.execSQL(
        "CREATE TABLE draft (id INTEGER PRIMARY KEY, text TEXT, language TEXT, seconds INTEGER)");
    db.execSQL("INSERT INTO notes VALUES(7,1000,'Existing Hush transcript','en-US',5)");
    db.setVersion(1);
    db.close();
    try (NoteStore s = new NoteStore(context)) {
      assertEquals("Existing Hush transcript", s.list("").get(0).text);
      assertEquals(7L, s.list("").get(0).id);
      assertTrue(s.pending().isEmpty());
    }
  }

  public void testVersionTwoUpgradePreservesPendingDraftAndHistory() throws Exception {
    File audio = wave("v2.wav");
    SQLiteDatabase db =
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("hush.db"), null);
    db.execSQL(
        "CREATE TABLE notes (id INTEGER PRIMARY KEY, created INTEGER, text TEXT, language TEXT,"
            + " seconds INTEGER)");
    db.execSQL(
        "CREATE TABLE draft (id INTEGER PRIMARY KEY, text TEXT, language TEXT, seconds INTEGER)");
    db.execSQL(
        "CREATE TABLE pending (id TEXT PRIMARY KEY, created INTEGER, file TEXT, endpoint TEXT,"
            + " model TEXT, language TEXT, seconds INTEGER, state TEXT)");
    db.execSQL("INSERT INTO notes VALUES(9,1000,'Relay transcript','ko-KR',5)");
    db.execSQL("INSERT INTO draft VALUES(1,'Interrupted draft','ko-KR',3)");
    db.execSQL(
        "INSERT INTO pending VALUES('legacy',1000,'v2.wav',?,'model','ko-KR',1,'failed')",
        new Object[] {Prefs.GROQ_URL});
    db.setVersion(2);
    db.close();
    try (NoteStore s = new NoteStore(context)) {
      assertEquals("Relay transcript", s.list("").get(0).text);
      assertEquals(9L, s.list("").get(0).id);
      assertEquals(0, s.evaluationCount());
      assertFalse(s.pending().get(0).retain);
      assertEquals("v2.wav", s.pending().get(0).file);
      assertTrue(s.recoverDraft());
      assertEquals(2, s.list("").size());
    }
    CloudJobs.recover(context);
    assertTrue(audio.exists());
  }

  private void transcribePending(NoteStore.Pending pending, int status, String body)
      throws Exception {
    CountDownLatch completed = new CountDownLatch(1);
    CloudJobs.submit(
        context,
        pending,
        new CloudClient(url -> new Http(status, body)),
        new CloudJobs.Result() {
          public void complete(String text, long id) {
            completed.countDown();
          }

          public void failed(String message) {
            completed.countDown();
          }
        });
    assertTrue(completed.await(10, TimeUnit.SECONDS));
  }

  public void testEvaluationRetryExportOriginalAndEditedTranscript() throws Exception {
    groq();
    File audio = wave("evaluation.wav");
    byte[] original = java.nio.file.Files.readAllBytes(audio.toPath());
    NoteStore.Pending pending =
        new NoteStore.Pending(
            "evaluation",
            123456789L,
            audio.getName(),
            Prefs.GROQ_URL,
            "whisper-large-v3",
            "ko-KR",
            1,
            "ready",
            true,
            "groq");
    try (NoteStore s = new NoteStore(context)) {
      s.addPending(pending);
    }
    transcribePending(pending, 503, "unavailable");
    try (NoteStore s = new NoteStore(context)) {
      assertEquals(0, s.evaluationCount());
      assertTrue(s.pending().get(0).retain);
      assertEquals("failed", s.pending().get(0).state);
    }
    assertTrue(audio.exists());
    CloudJobs.recover(context);
    try (NoteStore s = new NoteStore(context)) {
      pending = s.pending().get(0);
    }
    transcribePending(pending, 200, "{\"text\":\"원본 전사\"}");
    long id;
    try (NoteStore s = new NoteStore(context)) {
      assertEquals(1, s.evaluationCount());
      assertTrue(s.pending().isEmpty());
      id = s.list("").get(0).id;
      s.update(id, "수정한 전사");
      assertEquals(-1L, s.completePending(pending, "Duplicate"));
    }
    CloudJobs.recover(context);
    assertTrue(audio.exists());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    assertEquals(1, EvaluationArchive.write(context, bytes));
    Map<String, byte[]> entries = new HashMap<>();
    try (java.util.zip.ZipInputStream zip =
        new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), zip.readAllBytes());
    }
    assertEquals(3, entries.size());
    assertTrue(Arrays.equals(original, entries.get("audio/" + id + ".wav")));
    org.json.JSONObject manifest =
        new org.json.JSONObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
    assertEquals(1, manifest.getInt("schema_version"));
    org.json.JSONObject record =
        new org.json.JSONObject(
            new String(entries.get("recordings.jsonl"), StandardCharsets.UTF_8));
    assertEquals("원본 전사", record.getString("original_transcript"));
    assertEquals("수정한 전사", record.getString("transcript"));
    assertEquals("groq", record.getString("provider"));
    assertEquals("whisper-large-v3", record.getString("model"));
    assertEquals("ko-KR", record.getString("language"));
    assertEquals(123456789L, record.getLong("created_at_ms"));
    assertFalse(record.has("endpoint"));
    assertFalse(bytes.toString(StandardCharsets.ISO_8859_1.name()).contains("test-only-token"));
    try (NoteStore s = new NoteStore(context)) {
      s.delete(id);
      assertEquals(0, s.evaluationCount());
      assertTrue(s.list("").isEmpty());
    }
    assertFalse(audio.exists());
  }

  public void testFailedEvaluationExportKeepsAudio() throws Exception {
    File audio = wave("export-failure.wav");
    NoteStore.Pending p =
        new NoteStore.Pending(
            "export-failure",
            1,
            audio.getName(),
            Prefs.GROQ_URL,
            "model",
            "en-US",
            1,
            "ready",
            true,
            "groq");
    try (NoteStore s = new NoteStore(context)) {
      s.addPending(p);
      s.completePending(p, "Keep me");
    }
    try {
      EvaluationArchive.write(
          context,
          new OutputStream() {
            public void write(int b) throws IOException {
              throw new IOException("Disk full");
            }
          });
      throw new AssertionError("Expected export failure");
    } catch (IOException expected) {
    }
    CloudJobs.recover(context);
    assertTrue(audio.exists());
    try (NoteStore s = new NoteStore(context)) {
      assertEquals(1, s.evaluationCount());
    }
  }

  public void testEvaluationChoiceIsCapturedAtRecordingStart() throws Exception {
    groq();
    assertFalse(Prefs.keepRecordings(context));
    Prefs.get(context).edit().putBoolean("keep_recordings", true).commit();
    Audio audio = new Audio();
    HushTests.Capture listener = new HushTests.Capture();
    CloudDictationEngine[] engine = new CloudDictationEngine[1];
    main(
        () -> {
          engine[0] =
              new CloudDictationEngine(
                  context,
                  listener,
                  () -> audio,
                  new CloudClient(url -> new Http(200, "{\"text\":\"Retained recording\"}")));
          engine[0].start(false);
        });
    audio.waitForFrames();
    Prefs.get(context).edit().putBoolean("keep_recordings", false).commit();
    main(engine[0]::stop);
    await(
        () -> {
          try (NoteStore s = new NoteStore(context)) {
            return s.list("").size() == 1 && !CloudJobs.any();
          }
        });
    main(engine[0]::close);
    CloudJobs.recover(context);
    try (NoteStore s = new NoteStore(context)) {
      assertEquals(1, s.evaluationCount());
      assertEquals(1, s.evaluationFiles().size());
      assertTrue(
          new File(CloudJobs.directory(context), s.evaluationFiles().iterator().next()).exists());
    }
    // New recordings after opt-out use the normal delete-after-commit behavior.
    File next = wave("after-opt-out.wav");
    NoteStore.Pending p =
        new NoteStore.Pending(
            "after-opt-out",
            2,
            next.getName(),
            Prefs.GROQ_URL,
            "model",
            "en-US",
            1,
            "ready",
            Prefs.keepRecordings(context),
            "groq");
    try (NoteStore s = new NoteStore(context)) {
      s.addPending(p);
    }
    transcribePending(p, 200, "{\"text\":\"Not retained\"}");
    assertFalse(next.exists());
    try (NoteStore s = new NoteStore(context)) {
      assertEquals(1, s.evaluationCount());
    }
  }

  public void testPendingCommitIsAtomicAndDoesNotDuplicate() throws Exception {
    File file = wave("atomic.wav");
    NoteStore.Pending pending =
        new NoteStore.Pending(
            "atomic", 1, file.getName(), Prefs.GROQ_URL, "whisper-large-v3", "en-US", 2, "ready");
    try (NoteStore s = new NoteStore(context)) {
      s.addPending(pending);
      assertTrue(s.completePending(pending, "Saved once") > 0);
      assertEquals(-1L, s.completePending(pending, "Duplicate"));
      assertEquals(1, s.list("").size());
      assertTrue(s.pending().isEmpty());
    }
  }

  public void testCloudCaptureStopsAndSavesTranscript() throws Exception {
    groq();
    Audio audio = new Audio();
    Http http = new Http(200, "{\"text\":\"Groq transcript\"}");
    HushTests.Capture listener = new HushTests.Capture();
    CloudDictationEngine[] engine = new CloudDictationEngine[1];
    main(
        () -> {
          engine[0] =
              new CloudDictationEngine(
                  context, listener, () -> audio, new CloudClient(url -> http));
          engine[0].start(false);
        });
    audio.waitForFrames();
    main(engine[0]::stop);
    await(
        () -> {
          try (NoteStore s = new NoteStore(context)) {
            return s.list("").size() == 1;
          }
        });
    await(
        () -> {
          boolean[] ready = {false};
          main(() -> ready[0] = listener.saved == 1);
          return ready[0];
        });
    main(
        () -> {
          assertEquals("Groq transcript", listener.text);
          assertFalse(engine[0].busy());
          engine[0].close();
        });
    try (NoteStore s = new NoteStore(context)) {
      assertTrue(s.pending().isEmpty());
      assertEquals("Groq transcript", s.list("").get(0).text);
    }
  }

  public void testCloudFailureRetainsAudioThenRetrySucceeds() throws Exception {
    groq();
    Audio audio = new Audio();
    Http unauthorized = new Http(401, "");
    HushTests.Capture listener = new HushTests.Capture();
    CloudDictationEngine[] engine = new CloudDictationEngine[1];
    main(
        () -> {
          engine[0] =
              new CloudDictationEngine(
                  context, listener, () -> audio, new CloudClient(url -> unauthorized));
          engine[0].start(false);
        });
    audio.waitForFrames();
    main(engine[0]::stop);
    await(
        () -> {
          try (NoteStore s = new NoteStore(context)) {
            return !s.pending().isEmpty()
                && s.pending().get(0).state.equals("failed")
                && !CloudJobs.any();
          }
        });
    NoteStore.Pending pending;
    try (NoteStore s = new NoteStore(context)) {
      pending = s.pending().get(0);
      assertTrue(new File(CloudJobs.directory(context), pending.file).exists());
      assertTrue(s.list("").isEmpty());
    }
    Http success = new Http(200, "{\"text\":\"Retried transcript\"}");
    CountDownLatch finished = new CountDownLatch(1);
    CloudJobs.submit(
        context,
        pending,
        new CloudClient(url -> success),
        new CloudJobs.Result() {
          public void complete(String t, long id) {
            finished.countDown();
          }

          public void failed(String m) {
            throw new AssertionError(m);
          }
        });
    assertTrue(finished.await(5, TimeUnit.SECONDS));
    try (NoteStore s = new NoteStore(context)) {
      assertTrue(s.pending().isEmpty());
      assertEquals("Retried transcript", s.list("").get(0).text);
    }
    assertFalse(new File(CloudJobs.directory(context), pending.file).exists());
    main(engine[0]::close);
  }

  public void testDiscardedCloudAudioIsNeverUploaded() throws Exception {
    groq();
    Audio audio = new Audio();
    int[] opened = {0};
    CloudDictationEngine[] engine = new CloudDictationEngine[1];
    main(
        () -> {
          engine[0] =
              new CloudDictationEngine(
                  context,
                  new HushTests.Capture(),
                  () -> audio,
                  new CloudClient(
                      url -> {
                        opened[0]++;
                        throw new IOException();
                      }));
          engine[0].start(false);
        });
    audio.waitForFrames();
    main(
        () -> {
          engine[0].cancel();
          engine[0].close();
        });
    await(() -> !RecordingOwnership.capturing());
    try (NoteStore s = new NoteStore(context)) {
      assertTrue(s.pending().isEmpty());
      assertTrue(s.list("").isEmpty());
    }
    assertEquals(0, opened[0]);
  }

  public void testDismissedCloudUiStillSaves() throws Exception {
    groq();
    Audio audio = new Audio();
    Http http = new Http(200, "{\"text\":\"Saved after dismissal\"}");
    HushTests.Capture listener = new HushTests.Capture();
    CloudDictationEngine[] engine = new CloudDictationEngine[1];
    main(
        () -> {
          engine[0] =
              new CloudDictationEngine(
                  context, listener, () -> audio, new CloudClient(url -> http));
          engine[0].start(false);
        });
    audio.waitForFrames();
    main(engine[0]::close);
    await(
        () -> {
          try (NoteStore s = new NoteStore(context)) {
            return s.list("").size() == 1 && !CloudJobs.any();
          }
        });
    assertEquals(0, listener.saved);
    try (NoteStore s = new NoteStore(context)) {
      assertEquals("Saved after dismissal", s.list("").get(0).text);
      assertTrue(s.pending().isEmpty());
    }
  }

  public void testInterruptedCloudAudioBecomesRetryable() throws Exception {
    File f = wave("interrupted.wav");
    try (NoteStore s = new NoteStore(context)) {
      s.addPending(
          new NoteStore.Pending(
              "interrupted",
              1,
              f.getName(),
              Prefs.GROQ_URL,
              "whisper-large-v3",
              "en-US",
              0,
              "recording"));
    }
    File orphan = wave("orphan.wav");
    CloudJobs.recover(context);
    try (NoteStore s = new NoteStore(context)) {
      assertEquals("ready", s.pending().get(0).state);
      assertTrue(s.list("").isEmpty());
    }
    assertTrue(f.exists());
    assertFalse(orphan.exists());
  }

  public void testHomeButtonStartsImmediatelyAndStopsOnRelease() {
    MainActivity a =
        (MainActivity)
            instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    instrumentation.waitForIdleSync();
    HushTests.Fake fake = new HushTests.Fake();
    main(
        () -> {
          a.setRecordingFactory(fake);
          assertNull(find(a.getWindow().getDecorView(), "Let your thoughts\nflow."));
          View label = find(a.getWindow().getDecorView(), "  Hold to dictate");
          assertNotNull(label);
          View button = (View) label.getParent();
          touch(button, MotionEvent.ACTION_DOWN);
          assertEquals(1, fake.starts);
          fake.partial("Home button text");
          touch(button, MotionEvent.ACTION_UP);
          assertEquals(1, fake.stops);
          fake.result("Home button text.");
          assertNotNull(find(a.getWindow().getDecorView(), "Saved"));
          a.finish();
        });
    try (NoteStore s = new NoteStore(context)) {
      assertEquals("Home button text.", s.list("").get(0).text);
    }
  }

  public void testAssistantSettingsButtonTargetsOs() {
    MainActivity a =
        (MainActivity)
            instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    instrumentation.waitForIdleSync();
    Instrumentation.ActivityMonitor monitor =
        instrumentation.addMonitor(
            new IntentFilter(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            new Instrumentation.ActivityResult(Activity.RESULT_OK, null),
            true);
    main(
        () -> {
          ((View) find(a.getWindow().getDecorView(), "Settings").getParent()).performClick();
          find(a.getWindow().getDecorView(), "Open Android assistant settings").performClick();
        });
    assertEquals(1, monitor.getHits());
    instrumentation.removeMonitor(monitor);
    main(a::finish);
  }
}
