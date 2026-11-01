package com.hush.voice;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.speech.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity {
  private LinearLayout root, body, notesContainer;
  private NoteStore store;
  private int page = 0;
  private final BroadcastReceiver updates =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
          if (root != null && homePanel == null && recordingPanel == null && !isFinishing())
            render();
        }
      };

  // Export-control flags do not exist on Android 12. This branch is signature protected.
  @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
  private void registerUpdatesLegacy(IntentFilter filter) {
    registerReceiver(updates, filter, "com.hush.voice.INTERNAL", null);
  }

  private boolean updatesRegistered;
  private RecordingPanel homePanel;
  private TextView homeRecordLabel;
  private View homeRecordButton;
  private boolean homePointerDown, homeTouchClick;
  private DictationEngine.Factory recordingFactory;

  void setRecordingFactory(DictationEngine.Factory factory) {
    recordingFactory = factory;
  }

  private Dialog recorder;
  private RecordingPanel recordingPanel;
  private String search = "", exportText = "";
  private boolean wantsDictation, pendingAutoSave;
  private static final java.util.concurrent.ExecutorService exports =
      java.util.concurrent.Executors.newSingleThreadExecutor();

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    IntentFilter changes = new IntentFilter("com.hush.voice.NOTES_CHANGED");
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(updates, changes, Context.RECEIVER_NOT_EXPORTED);
    else registerUpdatesLegacy(changes);
    updatesRegistered = true;
    getWindow().setStatusBarColor(Ui.BG);
    getWindow().setNavigationBarColor(Ui.BG);
    getWindow().setDecorFitsSystemWindows(false);
    store = new NoteStore(this);
    if (state != null) {
      page = state.getInt("page");
      search = state.getString("search", "");
      exportText = state.getString("export", "");
    }
    if (!DictationEngine.capturing() && store.recoverDraft())
      Toast.makeText(this, "Recovered your interrupted dictation", Toast.LENGTH_LONG).show();
    CloudJobs.recover(this);
    render();
    handleIntent(getIntent());
  }

  @Override
  public void onSaveInstanceState(Bundle state) {
    state.putString("export", exportText);
    state.putInt("page", page);
    state.putString("search", search);
    super.onSaveInstanceState(state);
  }

  @Override
  protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    handleIntent(i);
  }

  private void handleIntent(Intent i) {
    if (i.getBooleanExtra("dictate", false) || Intent.ACTION_ASSIST.equals(i.getAction())) {
      i.removeExtra("dictate");
      i.setAction(null);
      new Handler(Looper.getMainLooper()).postDelayed(() -> record(true), 350);
    }
  }

  private void render() {
    closeHomePanel();
    root = Ui.column(this);
    root.setBackgroundColor(Ui.BG);
    root.setOnApplyWindowInsetsListener(
        (v, i) -> {
          android.graphics.Insets bars =
              i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
          root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return i;
        });
    setContentView(root);
    root.requestApplyInsets();
    LinearLayout header = Ui.row(this);
    Ui.pad(header, 24, 18, 10);
    Ui.Icon logo = new Ui.Icon(this, "logo", Ui.LIME);
    header.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));
    TextView brand = Ui.bold(this, " Relay", 25, Ui.WHITE);
    brand.setLetterSpacing(-.03f);
    Ui.weighted(header, brand);
    TextView local = Ui.text(this, Prefs.providerName(this), 10, Ui.LIME);
    local.setLetterSpacing(.08f);
    Ui.pad(local, 10, 8, 8);
    local.setBackground(Ui.shape(this, Ui.CARD, 12));
    header.addView(local);
    root.addView(header);
    ScrollView scroller = new ScrollView(this);
    scroller.setFillViewport(true);
    scroller.setClipToPadding(false);
    body = Ui.column(this);
    Ui.pad(body, 24, 16, 24);
    scroller.addView(body);
    root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
    if (page == 0) home();
    else if (page == 1) setup();
    else settings();
    if (page == 0) {
      LinearLayout foot = Ui.column(this);
      Ui.pad(foot, 24, 8, 8);
      LinearLayout mic = Ui.row(this);
      mic.setGravity(Gravity.CENTER);
      mic.setBackground(
          new android.graphics.drawable.RippleDrawable(
              android.content.res.ColorStateList.valueOf(0x33000000),
              Ui.shape(this, Ui.LIME, 18),
              null));
      Ui.pad(mic, 20, 16, 16);
      mic.addView(
          new Ui.Icon(this, "mic", Ui.BG),
          new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
      homeRecordLabel = Ui.bold(this, "  Hold to dictate", 17, Ui.BG);
      mic.addView(homeRecordLabel);
      homeRecordButton = mic;
      mic.setContentDescription(
          "Hold to record; release to transcribe. Activate to open recorder.");
      mic.setFocusable(true);
      mic.setOnClickListener(
          v -> {
            if (!homeTouchClick) record(false);
          });
      mic.setOnTouchListener(
          (v, event) -> {
            switch (event.getActionMasked()) {
              case MotionEvent.ACTION_DOWN -> {
                if (homePanel != null && homePanel.busy()) return true;
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                  requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 11);
                  return true;
                }
                homePointerDown = true;
                v.animate().scaleX(.98f).scaleY(.98f).setDuration(90);
                startHomeHold();
                return true;
              }
              case MotionEvent.ACTION_UP -> {
                homePointerDown = false;
                v.animate().scaleX(1).scaleY(1).setDuration(120);
                if (homePanel != null) homePanel.stop();
                homeTouchClick = true;
                v.performClick();
                homeTouchClick = false;
                updateHomeControl();
                return true;
              }
              case MotionEvent.ACTION_CANCEL -> {
                homePointerDown = false;
                v.animate().scaleX(1).scaleY(1).setDuration(120);
                if (homePanel != null) homePanel.stop();
                updateHomeControl();
                return true;
              }
            }
            return false;
          });
      foot.addView(mic);
      TextView help = Ui.text(this, "Hold to record · release to save", 12, Ui.MUTED);
      help.setGravity(Gravity.CENTER);
      Ui.gap(foot, 6);
      foot.addView(help);
      root.addView(foot);
    }
    LinearLayout nav = Ui.row(this);
    Ui.pad(nav, 16, 8, 10);
    String[] labels = {"Notes", "Shortcut", "Settings"};
    String[] icons = {"notes", "power", "settings"};
    for (int i = 0; i < 3; i++) {
      final int target = i;
      LinearLayout tab = Ui.column(this);
      tab.setGravity(Gravity.CENTER);
      Ui.pad(tab, 4, 8, 8);
      if (page == i) tab.setBackground(Ui.shape(this, Ui.CARD, 18));
      tab.addView(
          new Ui.Icon(this, icons[i], page == i ? Ui.LIME : Ui.MUTED),
          new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
      TextView label = Ui.text(this, labels[i], 11, page == i ? Ui.LIME : Ui.MUTED);
      label.setGravity(Gravity.CENTER);
      tab.addView(label);
      tab.setContentDescription(labels[i] + (page == i ? ", selected" : ""));
      tab.setFocusable(true);
      tab.setOnClickListener(
          v -> {
            page = target;
            render();
          });
      nav.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
    }
    root.addView(nav);
  }

  private TextView eyebrow(String value) {
    TextView t = Ui.text(this, value, 11, Ui.MUTED);
    t.setLetterSpacing(.13f);
    return t;
  }

  private LinearLayout card() {
    LinearLayout c = Ui.column(this);
    Ui.pad(c, 20, 20, 20);
    c.setBackground(Ui.shape(this, Ui.CARD, 24));
    return c;
  }

  private void title(String title, String subtitle) {
    body.addView(Ui.bold(this, title, 34, Ui.WHITE));
    Ui.gap(body, 8);
    body.addView(Ui.text(this, subtitle, 15, Ui.MUTED));
    Ui.gap(body, 26);
  }

  private void home() {
    body.addView(Ui.text(this, Prefs.description(this), 14, Ui.MUTED));
    Ui.gap(body, 18);
    pendingRecordings();
    LinearLayout heading = Ui.row(this);
    Ui.weighted(heading, Ui.bold(this, "Transcripts", 21, Ui.WHITE));
    TextView export = Ui.button(this, "Export", false, () -> exportAll());
    heading.addView(export);
    body.addView(heading);
    Ui.gap(body, 12);
    LinearLayout searchRow = Ui.row(this);
    searchRow.setBackground(Ui.shape(this, Ui.CARD, 16));
    Ui.pad(searchRow, 14, 0, 0);
    searchRow.addView(
        new Ui.Icon(this, "search", Ui.MUTED),
        new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setTextColor(Ui.WHITE);
    input.setTextSize(14);
    input.setHintTextColor(Ui.MUTED);
    input.setHint("Search transcripts");
    input.setContentDescription("Search local transcripts");
    input.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    Ui.pad(input, 12, 12, 12);
    input.setText(search);
    searchRow.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
    body.addView(searchRow);
    Ui.gap(body, 14);
    notesContainer = Ui.column(this);
    body.addView(notesContainer);
    renderNotes();
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int a) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            search = s.toString();
            renderNotes();
          }

          public void afterTextChanged(Editable e) {}
        });
  }

  private void renderNotes() {
    notesContainer.removeAllViews();
    List<NoteStore.Note> notes = store.list(search);
    if (notes.isEmpty()) {
      LinearLayout empty = Ui.column(this);
      empty.setGravity(Gravity.CENTER);
      Ui.pad(empty, 18, 28, 28);
      empty.addView(
          new Ui.Icon(this, "notes", Ui.MUTED),
          new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
      Ui.gap(empty, 10);
      empty.addView(
          Ui.bold(
              this,
              search.trim().isEmpty() ? "No transcripts yet" : "No matching notes",
              17,
              Ui.WHITE));
      Ui.gap(empty, 6);
      TextView info =
          Ui.text(
              this,
              search.trim().isEmpty()
                  ? "Hold the record button to start."
                  : "Try a different word.",
              13,
              Ui.MUTED);
      info.setGravity(Gravity.CENTER);
      empty.addView(info);
      notesContainer.addView(empty);
      return;
    }
    SimpleDateFormat format = new SimpleDateFormat("MMM d · h:mm a", Locale.getDefault());
    int i = 0;
    for (NoteStore.Note n : notes) {
      LinearLayout tile = card();
      Ui.pad(tile, 18, 16, 16);
      TextView metadata =
          Ui.text(
              this, format.format(new Date(n.created)) + "  /  " + n.seconds + "s", 11, Ui.MUTED);
      tile.addView(metadata);
      Ui.gap(tile, 8);
      TextView t = Ui.text(this, n.text, 17, Ui.WHITE);
      t.setMaxLines(3);
      t.setEllipsize(TextUtils.TruncateAt.END);
      tile.addView(t);
      Ui.gap(tile, 10);
      int count = n.text.trim().split("\\s+").length;
      tile.addView(
          Ui.text(this, count + " words  ·  " + n.language.toUpperCase(Locale.ROOT), 10, Ui.MUTED));
      tile.setOnClickListener(v -> note(n));
      tile.setFocusable(true);
      tile.setContentDescription("Open note: " + n.text);
      notesContainer.addView(tile);
      Ui.gap(notesContainer, 10);
    }
  }

  private boolean isAssistant() {
    return android.service.voice.VoiceInteractionService.isActiveService(
        this, new ComponentName(this, HushAssistant.class));
  }

  private void setup() {
    title("Shortcut", "Configure the side-button shortcut in Android Settings.");
    step(
        "01",
        "Microphone",
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ? "Permission granted."
            : "Allow microphone access to record.",
        "Open app permissions",
        () ->
            openSettings(
                new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()))));
    step(
        "02",
        "Default assistant",
        isAssistant()
            ? "Relay is selected. You can change it in Android Settings."
            : "In Default apps, choose Digital assistant app → Relay.",
        "Open Android assistant settings",
        this::chooseAssistant);
    step(
        "03",
        "Side button",
        "Advanced features → Side button → Long press → Digital assistant.",
        "Open Android Settings",
        () -> openSettings(new Intent(Settings.ACTION_SETTINGS)));
    body.addView(
        Ui.text(
            this,
            "Power-button long-press starts recording; a pause or Done stops it. The home-screen"
                + " button records while held and stops on release.",
            14,
            Ui.MUTED));
  }

  private void step(String num, String title, String detail, String button, Runnable action) {
    LinearLayout c = Ui.column(this);
    LinearLayout r = Ui.row(this);
    TextView number = Ui.bold(this, num, 14, Ui.LIME);
    r.addView(number, new LinearLayout.LayoutParams(Ui.dp(this, 40), -2));
    Ui.weighted(r, Ui.bold(this, title, 19, Ui.WHITE));
    c.addView(r);
    Ui.gap(c, 8);
    c.addView(Ui.text(this, detail, 14, Ui.MUTED));
    Ui.gap(c, 12);
    c.addView(Ui.button(this, button, false, action));
    body.addView(c);
    Ui.gap(body, 24);
  }

  private void chooseAssistant() {
    openSettings(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
  }

  private void openSettings(Intent intent) {
    try {
      startActivity(intent);
    } catch (ActivityNotFoundException e) {
      Toast.makeText(this, "Open Android Settings manually", Toast.LENGTH_LONG).show();
    }
  }

  private void settings() {
    title("Settings", "");
    LinearLayout dict = card();
    dict.addView(Ui.bold(this, "Transcription", 19, Ui.WHITE));
    Ui.gap(dict, 12);
    dict.addView(Ui.text(this, Prefs.description(this), 15, Ui.WHITE));
    Ui.gap(dict, 8);
    dict.addView(
        Ui.text(
            this,
            Prefs.cloud(this)
                ? "Audio is uploaded to the selected provider after recording. Transcripts stay in"
                    + " local history."
                : Prefs.offline(this)
                    ? "Uses Android's on-device speech engine."
                    : "Uses the Android speech service; it may process audio online.",
            13,
            Ui.MUTED));
    Ui.gap(dict, 12);
    dict.addView(Ui.button(this, "Change provider", false, this::chooseProvider));
    if (Prefs.cloud(this)) {
      Ui.gap(dict, 10);
      dict.addView(
          Ui.button(
              this,
              "API key and model",
              false,
              () -> CloudConfigDialog.show(this, Prefs.provider(this), this::render)));
      Ui.gap(dict, 10);
      dict.addView(Ui.button(this, "Check connection", false, this::checkConnection));
    }
    Ui.gap(dict, 20);
    dict.addView(Ui.bold(this, "Language", 16, Ui.WHITE));
    Ui.gap(dict, 6);
    dict.addView(
        Ui.text(
            this,
            Prefs.language(this).equals("auto")
                ? "Auto (cloud) / device language (Android)"
                : Locale.forLanguageTag(Prefs.language(this)).getDisplayName(),
            14,
            Ui.MUTED));
    Ui.gap(dict, 10);
    dict.addView(Ui.button(this, "Change language", false, this::language));
    if (Prefs.offline(this)) {
      Ui.gap(dict, 12);
      dict.addView(Ui.button(this, "Download language model", false, this::downloadModel));
    }
    body.addView(dict);
    Ui.gap(body, 20);
    LinearLayout after = card();
    Switch copy = new Switch(this);
    copy.setText("Copy transcript after saving");
    copy.setTextSize(15);
    copy.setTextColor(Ui.WHITE);
    copy.setChecked(Prefs.autoCopy(this));
    copy.setMinHeight(Ui.dp(this, 48));
    copy.setThumbTintList(
        new android.content.res.ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
            new int[] {Ui.LIME, Ui.MUTED}));
    copy.setOnCheckedChangeListener((b, v) -> Prefs.get(this).edit().putBoolean("copy", v).apply());
    after.addView(copy);
    body.addView(after);
    Ui.gap(body, 20);
    LinearLayout assistant = card();
    assistant.addView(Ui.bold(this, "Default assistant", 17, Ui.WHITE));
    Ui.gap(assistant, 6);
    assistant.addView(
        Ui.text(this, isAssistant() ? "Relay is selected" : "Relay is not selected", 14, Ui.MUTED));
    Ui.gap(assistant, 12);
    assistant.addView(
        Ui.button(this, "Open Android assistant settings", false, this::chooseAssistant));
    body.addView(assistant);
    Ui.gap(body, 20);
    LinearLayout evaluation = card();
    evaluation.addView(Ui.bold(this, "Evaluation data", 17, Ui.WHITE));
    Switch keep = new Switch(this);
    keep.setText("Keep recordings for evaluation");
    keep.setTextSize(15);
    keep.setTextColor(Ui.WHITE);
    keep.setMinHeight(Ui.dp(this, 48));
    keep.setChecked(Prefs.keepRecordings(this));
    keep.setOnCheckedChangeListener(
        (b, v) -> Prefs.get(this).edit().putBoolean("keep_recordings", v).apply());
    evaluation.addView(keep);
    evaluation.addView(
        Ui.text(
            this,
            "Applies to cloud recordings started while enabled. Keeps original audio and"
                + " transcription with your latest edits, provider and model on this device."
                + " Android speech recordings are not collected. Turning this off keeps previously"
                + " saved data. Delete a transcript to delete its saved audio too. Export before"
                + " uninstalling.",
            13,
            Ui.MUTED));
    Ui.gap(evaluation, 10);
    evaluation.addView(Ui.text(this, "Saved recordings: " + store.evaluationCount(), 14, Ui.MUTED));
    evaluation.addView(Ui.button(this, "Export evaluation ZIP", false, this::exportEvaluation));
    body.addView(evaluation);
    Ui.gap(body, 20);
    LinearLayout storage = card();
    storage.addView(Ui.bold(this, "Storage", 17, Ui.WHITE));
    Ui.gap(storage, 8);
    storage.addView(
        Ui.text(
            this,
            "Transcripts are stored in private app storage. Unless evaluation retention is enabled,"
                + " cloud audio is deleted after successful transcription. Failed recordings remain"
                + " available for retry or deletion. API"
                + " keys are encrypted with Android Keystore.\n\n"
                + "Export before uninstalling to keep your transcripts.",
            13,
            Ui.MUTED));
    Ui.gap(storage, 12);
    storage.addView(Ui.button(this, "Export transcripts", false, this::exportAll));
    body.addView(storage);
    Ui.gap(body, 20);
    body.addView(Ui.text(this, "Relay 1.1.0", 12, Ui.MUTED));
  }

  private void chooseProvider() {
    new AlertDialog.Builder(this)
        .setTitle("Transcription provider")
        .setItems(
            new String[] {
              "Android on-device", "Android system speech", "Groq", "Custom transcription API"
            },
            (d, i) -> {
              if (i < 2) {
                Prefs.get(this).edit().putString("provider", i == 0 ? "device" : "system").apply();
                render();
              } else CloudConfigDialog.show(this, i == 2 ? "groq" : "custom", this::render);
            })
        .show();
  }

  private void checkConnection() {
    String endpoint = Prefs.endpoint(this), model = Prefs.model(this);
    String key;
    try {
      key = CredentialStore.get(this, endpoint);
      if (endpoint.equals(Prefs.GROQ_URL) && key.isEmpty()) {
        Toast.makeText(this, "Add a Groq API key first", Toast.LENGTH_LONG).show();
        return;
      }
    } catch (Exception e) {
      Toast.makeText(this, "Re-enter your API key", Toast.LENGTH_LONG).show();
      return;
    }
    Toast.makeText(this, "Checking connection…", Toast.LENGTH_SHORT).show();
    java.lang.ref.WeakReference<MainActivity> target = new java.lang.ref.WeakReference<>(this);
    CloudJobs.workers.execute(
        () -> {
          String result;
          try {
            result = new CloudClient().check(endpoint, key, model);
          } catch (IOException e) {
            result = e.getMessage();
          }
          String message = result;
          new Handler(Looper.getMainLooper())
              .post(
                  () -> {
                    MainActivity a = target.get();
                    if (a != null && !a.isFinishing())
                      Toast.makeText(a, message, Toast.LENGTH_LONG).show();
                  });
        });
  }

  private void pendingRecordings() {
    List<NoteStore.Pending> pending = store.pending();
    if (pending.isEmpty()) return;
    body.addView(Ui.bold(this, "Pending recordings", 19, Ui.WHITE));
    Ui.gap(body, 10);
    for (NoteStore.Pending p : pending) {
      LinearLayout item = card();
      item.addView(
          Ui.text(
              this,
              (CloudJobs.busy(p.id) ? "Transcribing" : "Needs transcription")
                  + " · "
                  + p.seconds
                  + "s",
              14,
              Ui.WHITE));
      Ui.gap(item, 4);
      item.addView(Ui.text(this, p.model, 12, Ui.MUTED));
      Ui.gap(item, 10);
      LinearLayout actions = Ui.row(this);
      TextView retry = Ui.button(this, "Retry with current model", false, () -> retry(p));
      retry.setEnabled(!CloudJobs.busy(p.id) && !p.state.equals("recording"));
      actions.addView(retry, new LinearLayout.LayoutParams(0, -2, 1));
      TextView delete =
          Ui.button(
              this,
              "Delete",
              false,
              () -> {
                if (CloudJobs.busy(p.id)) return;
                new File(CloudJobs.directory(this), p.file).delete();
                store.removePending(p.id);
                render();
              });
      delete.setEnabled(!CloudJobs.busy(p.id) && !p.state.equals("recording"));
      actions.addView(delete, new LinearLayout.LayoutParams(0, -2, 1));
      item.addView(actions);
      body.addView(item);
      Ui.gap(body, 10);
    }
    Ui.gap(body, 16);
  }

  private void retry(NoteStore.Pending old) {
    if (!Prefs.cloud(this)) {
      Toast.makeText(this, "Select Groq or Custom API in Settings to retry", Toast.LENGTH_LONG)
          .show();
      return;
    }
    NoteStore.Pending p =
        new NoteStore.Pending(
            old.id,
            old.created,
            old.file,
            Prefs.endpoint(this),
            Prefs.model(this),
            Prefs.language(this),
            old.seconds,
            "ready",
            old.retain,
            Prefs.provider(this));
    store.configurePending(p.id, p.endpoint, p.model, p.language, p.provider);
    java.lang.ref.WeakReference<MainActivity> target = new java.lang.ref.WeakReference<>(this);
    CloudJobs.submit(
        this,
        p,
        new CloudClient(),
        new CloudJobs.Result() {
          private MainActivity target() {
            MainActivity a = target.get();
            return a != null && !a.isFinishing() ? a : null;
          }

          public void complete(String t, long id) {
            MainActivity a = target();
            if (a != null) a.render();
          }

          public void failed(String m) {
            MainActivity a = target();
            if (a != null) {
              Toast.makeText(a, m, Toast.LENGTH_LONG).show();
              a.render();
            }
          }
        });
    render();
  }

  private void startHomeHold() {
    closeHomePanel();
    body.removeAllViews();
    homePanel =
        new RecordingPanel(
            this,
            () -> {
              closeHomePanel();
              render();
            },
            recordingFactory);
    homePanel.observe(this::updateHomeControl);
    body.addView(homePanel);
    if (body.getParent() instanceof ScrollView scroll) scroll.scrollTo(0, 0);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    homePanel.start(false);
    updateHomeControl();
  }

  private void updateHomeControl() {
    if (homeRecordButton == null || homeRecordLabel == null) return;
    boolean processing = homePanel != null && homePanel.processing();
    homeRecordButton.setEnabled(!processing);
    homeRecordLabel.setText(
        processing
            ? "  Transcribing…"
            : homePointerDown && homePanel != null && homePanel.engineActive()
                ? "  Release to save"
                : "  Hold to dictate");
  }

  private void closeHomePanel() {
    if (homePanel != null) {
      RecordingPanel old = homePanel;
      homePanel = null;
      old.observe(null);
      old.close();
    }
    if (getWindow() != null) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  private void language() {
    String[] names = {
      "Auto",
      "Device language",
      "English (US)",
      "한국어",
      "日本語",
      "Español",
      "Français",
      "Deutsch",
      "中文 (简体)"
    };
    String[] tags = {
      "auto",
      Locale.getDefault().toLanguageTag(),
      "en-US",
      "ko-KR",
      "ja-JP",
      "es-ES",
      "fr-FR",
      "de-DE",
      "zh-CN"
    };
    new AlertDialog.Builder(this)
        .setTitle("Dictation language")
        .setItems(
            names,
            (d, i) -> {
              Prefs.get(this).edit().putString("language", tags[i]).apply();
              render();
            })
        .show();
  }

  private void downloadModel() {
    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
      Toast.makeText(this, "Enable or update Speech Services by Google first", Toast.LENGTH_LONG)
          .show();
      openSettings(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
      return;
    }
    SpeechRecognizer r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
    Intent request =
        new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.language(this));
    if (Build.VERSION.SDK_INT >= 34) {
      r.triggerModelDownload(
          request,
          getMainExecutor(),
          new ModelDownloadListener() {
            public void onProgress(int p) {}

            public void onSuccess() {
              Toast.makeText(MainActivity.this, "Language model ready", Toast.LENGTH_LONG).show();
              r.destroy();
            }

            public void onScheduled() {
              Toast.makeText(MainActivity.this, "Download scheduled by Android", Toast.LENGTH_LONG)
                  .show();
              r.destroy();
            }

            public void onError(int e) {
              Toast.makeText(
                      MainActivity.this,
                      "Provider couldn't download this model (" + e + ")",
                      Toast.LENGTH_LONG)
                  .show();
              r.destroy();
            }
          });
    } else if (Build.VERSION.SDK_INT >= 33) {
      r.triggerModelDownload(request);
      new Handler(Looper.getMainLooper()).postDelayed(r::destroy, 2000);
      Toast.makeText(this, "Requested language model from Android", Toast.LENGTH_LONG).show();
    } else {
      r.destroy();
      openSettings(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
    }
  }

  private void record(boolean autoSave) {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      wantsDictation = true;
      pendingAutoSave = autoSave;
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 10);
      return;
    }
    if (recorder != null && recorder.isShowing()) return;
    recorder = new Dialog(this);
    recordingPanel = new RecordingPanel(this, () -> recorder.dismiss());
    recorder.setContentView(recordingPanel);
    Window w = recorder.getWindow();
    if (w != null) {
      w.setBackgroundDrawable(
          new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
      w.addFlags(
          WindowManager.LayoutParams.FLAG_DIM_BEHIND
              | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      WindowManager.LayoutParams lp = w.getAttributes();
      lp.gravity = Gravity.BOTTOM;
      lp.dimAmount = .6f;
      w.setAttributes(lp);
    }
    recorder.setOnDismissListener(
        d -> {
          recordingPanel.close();
          recordingPanel = null;
          if (page == 0) render();
        });
    recorder.show();
    if (w != null) w.setLayout(-1, -2);
    recordingPanel.postDelayed(
        () -> {
          if (recordingPanel != null && autoSave) recordingPanel.start(true);
        },
        180);
  }

  @Override
  public void onRequestPermissionsResult(int req, String[] permissions, int[] result) {
    super.onRequestPermissionsResult(req, permissions, result);
    if (result.length > 0 && result[0] == PackageManager.PERMISSION_GRANTED) {
      render();
      if (req == 10 && wantsDictation) {
        wantsDictation = false;
        record(pendingAutoSave);
      }
    } else {
      wantsDictation = false;
      new AlertDialog.Builder(this)
          .setTitle("Microphone needed")
          .setMessage(
              "Relay needs microphone permission to transcribe your voice. You can enable it in"
                  + " Android's app settings.")
          .setNegativeButton("Later", null)
          .setPositiveButton(
              "Open app settings",
              (d, w) ->
                  openSettings(
                      new Intent(
                          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          android.net.Uri.parse("package:" + getPackageName()))))
          .show();
    }
  }

  private void note(NoteStore.Note n) {
    LinearLayout content = Ui.column(this);
    Ui.pad(content, 24, 8, 8);
    EditText edit = new EditText(this);
    edit.setText(n.text);
    edit.setTextColor(Ui.WHITE);
    edit.setTextSize(19);
    edit.setGravity(Gravity.TOP);
    edit.setMinLines(5);
    edit.setMaxLines(12);
    edit.setContentDescription("Edit transcript");
    content.addView(edit);
    Ui.gap(content, 12);
    LinearLayout tools = Ui.row(this);
    tools.addView(
        Ui.button(
            this,
            "Copy",
            false,
            () -> {
              Prefs.copy(this, edit.getText().toString());
              Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            }),
        new LinearLayout.LayoutParams(0, -2, 1));
    tools.addView(
        Ui.button(this, "Share", false, () -> share(edit.getText().toString())),
        new LinearLayout.LayoutParams(0, -2, 1));
    tools.addView(
        Ui.button(this, "Export", false, () -> export(edit.getText().toString())),
        new LinearLayout.LayoutParams(0, -2, 1));
    content.addView(tools);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Transcript")
            .setView(content)
            .setNegativeButton("Close", null)
            .setNeutralButton(
                "Delete",
                (d, w) ->
                    new AlertDialog.Builder(this)
                        .setTitle("Delete this note?")
                        .setMessage(
                            "This permanently deletes the local transcript and any saved evaluation"
                                + " audio and original transcription.")
                        .setNegativeButton("Keep", null)
                        .setPositiveButton(
                            "Delete",
                            (x, y) -> {
                              store.delete(n.id);
                              render();
                            })
                        .show())
            .setPositiveButton("Save changes", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String t = edit.getText().toString().trim();
                      if (t.isEmpty()) {
                        edit.setError("A note needs some text");
                        return;
                      }
                      store.update(n.id, t);
                      dialog.dismiss();
                      render();
                    }));
    dialog.show();
  }

  private void share(String text) {
    startActivity(
        Intent.createChooser(
            new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            "Share transcript"));
  }

  private void exportAll() {
    List<NoteStore.Note> notes = store.list("");
    if (notes.isEmpty()) {
      Toast.makeText(this, "No notes to export yet", Toast.LENGTH_SHORT).show();
      return;
    }
    StringBuilder out = new StringBuilder("Relay · local transcripts\n\n");
    for (NoteStore.Note n : notes) {
      out.append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(n.created)))
          .append(" · ")
          .append(n.language)
          .append("\n")
          .append(n.text)
          .append("\n\n———\n\n");
    }
    export(out.toString());
  }

  private void export(String text) {
    exportText = text;
    startActivityForResult(
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .putExtra(
                Intent.EXTRA_TITLE,
                "Relay-"
                    + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date())
                    + ".txt"),
        33);
  }

  private void exportEvaluation() {
    if (store.evaluationCount() == 0) {
      Toast.makeText(
              this,
              "No evaluation recordings yet. Enable retention and record with a cloud provider.",
              Toast.LENGTH_LONG)
          .show();
      return;
    }
    startActivityForResult(
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(
                Intent.EXTRA_TITLE,
                "Relay-evaluation-"
                    + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date())
                    + ".zip"),
        34);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 34 && result == RESULT_OK && data != null && data.getData() != null) {
      android.net.Uri uri = data.getData();
      Context app = getApplicationContext();
      Toast.makeText(this, "Exporting evaluation data…", Toast.LENGTH_SHORT).show();
      exports.execute(
          () -> {
            String message;
            try (OutputStream out = app.getContentResolver().openOutputStream(uri, "wt")) {
              if (out == null) throw new IOException();
              int count = EvaluationArchive.write(app, out);
              message = "Exported " + count + (count == 1 ? " recording" : " recordings");
            } catch (Exception e) {
              try {
                android.provider.DocumentsContract.deleteDocument(app.getContentResolver(), uri);
              } catch (Exception ignored) {
                // Some document providers cannot remove a partial export. Local originals stay
                // intact.
              }
              message = "Couldn't export. Local recordings are kept; try another folder.";
            }
            String status = message;
            new Handler(Looper.getMainLooper())
                .post(() -> Toast.makeText(app, status, Toast.LENGTH_LONG).show());
          });
    } else if (request == 33 && result == RESULT_OK && data != null && data.getData() != null) {
      try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
        if (out == null) throw new IOException();
        out.write(exportText.getBytes(StandardCharsets.UTF_8));
        Toast.makeText(this, "Notes exported", Toast.LENGTH_SHORT).show();
      } catch (IOException e) {
        Toast.makeText(this, "Couldn't export. Try another folder.", Toast.LENGTH_LONG).show();
      }
    } else if (request == 22) {
      render();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (root != null && homePanel == null && recordingPanel == null) {
      CloudJobs.recover(this);
      render();
    }
  }

  @Override
  protected void onStop() {
    homePointerDown = false;
    closeHomePanel();
    if (recorder != null && recorder.isShowing()) recorder.dismiss();
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    if (updatesRegistered) {
      unregisterReceiver(updates);
      updatesRegistered = false;
    }
    closeHomePanel();
    if (recordingPanel != null) recordingPanel.close();
    store.close();
    super.onDestroy();
  }
}
