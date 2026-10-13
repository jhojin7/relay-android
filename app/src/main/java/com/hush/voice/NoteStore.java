package com.hush.voice;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

/** App-private transcripts; a single durable draft protects against process loss. */
public final class NoteStore extends SQLiteOpenHelper {
  public static final class Note {
    public final long id, created;
    public final String text, language;
    public final int seconds;

    Note(long i, long c, String t, String l, int s) {
      id = i;
      created = c;
      text = t;
      language = l;
      seconds = s;
    }
  }

  public NoteStore(Context c) {
    super(c.getApplicationContext(), "hush.db", null, 2);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER NOT NULL, text"
            + " TEXT NOT NULL, language TEXT NOT NULL, seconds INTEGER NOT NULL)");
    db.execSQL(
        "CREATE TABLE draft (id INTEGER PRIMARY KEY CHECK(id=1), text TEXT NOT NULL, language TEXT"
            + " NOT NULL, seconds INTEGER NOT NULL)");
    createPending(db);
  }

  private static void createPending(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS pending (id TEXT PRIMARY KEY, created INTEGER NOT NULL, file"
            + " TEXT NOT NULL, endpoint TEXT NOT NULL, model TEXT NOT NULL, language TEXT NOT NULL,"
            + " seconds INTEGER NOT NULL, state TEXT NOT NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int from, int to) {
    if (from < 2) createPending(db);
  }

  static final class Pending {
    final String id, file, endpoint, model, language, state;
    final int seconds;
    final long created;

    Pending(String i, long c, String f, String e, String m, String l, int s, String st) {
      id = i;
      created = c;
      file = f;
      endpoint = e;
      model = m;
      language = l;
      seconds = s;
      state = st;
    }
  }

  void addPending(Pending p) {
    ContentValues v = new ContentValues();
    v.put("id", p.id);
    v.put("created", p.created);
    v.put("file", p.file);
    v.put("endpoint", p.endpoint);
    v.put("model", p.model);
    v.put("language", p.language);
    v.put("seconds", p.seconds);
    v.put("state", p.state);
    getWritableDatabase().insertOrThrow("pending", null, v);
  }

  List<Pending> pending() {
    List<Pending> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,created,file,endpoint,model,language,seconds,state FROM pending ORDER BY"
                    + " created DESC",
                null)) {
      while (c.moveToNext())
        out.add(
            new Pending(
                c.getString(0),
                c.getLong(1),
                c.getString(2),
                c.getString(3),
                c.getString(4),
                c.getString(5),
                c.getInt(6),
                c.getString(7)));
    }
    return out;
  }

  void pendingState(String id, String state, int seconds) {
    ContentValues v = new ContentValues();
    v.put("state", state);
    v.put("seconds", seconds);
    getWritableDatabase().update("pending", v, "id=?", new String[] {id});
  }

  void configurePending(String id, String endpoint, String model, String language) {
    ContentValues v = new ContentValues();
    v.put("endpoint", endpoint);
    v.put("model", model);
    v.put("language", language);
    getWritableDatabase().update("pending", v, "id=?", new String[] {id});
  }

  void removePending(String id) {
    getWritableDatabase().delete("pending", "id=?", new String[] {id});
  }

  long completePending(Pending p, String text) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      try (Cursor c = db.rawQuery("SELECT id FROM pending WHERE id=?", new String[] {p.id})) {
        if (!c.moveToFirst()) return -1;
      }
      ContentValues v = new ContentValues();
      v.put("created", p.created);
      v.put("text", text.trim());
      v.put("language", p.language);
      v.put("seconds", p.seconds);
      long id = db.insertOrThrow("notes", null, v);
      db.delete("pending", "id=?", new String[] {p.id});
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
    }
  }

  public void draft(String text, String language, int seconds) {
    if (text.trim().isEmpty()) return;
    ContentValues v = new ContentValues();
    v.put("id", 1);
    v.put("text", text);
    v.put("language", language);
    v.put("seconds", seconds);
    getWritableDatabase().insertWithOnConflict("draft", null, v, SQLiteDatabase.CONFLICT_REPLACE);
  }

  public long save(String text, String language, int seconds) {
    if (text.trim().isEmpty()) return -1;
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues v = new ContentValues();
      v.put("created", System.currentTimeMillis());
      v.put("text", text.trim());
      v.put("language", language);
      v.put("seconds", seconds);
      long id = db.insertOrThrow("notes", null, v);
      db.delete("draft", null, null);
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
    }
  }

  public boolean recoverDraft() {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT text, language, seconds FROM draft WHERE id=1", null)) {
      if (c.moveToFirst()) {
        save(c.getString(0), c.getString(1), c.getInt(2));
        return true;
      }
    }
    return false;
  }

  public void discardDraft() {
    getWritableDatabase().delete("draft", null, null);
  }

  public List<Note> list(String query) {
    List<Note> out = new ArrayList<>();
    String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,created,text,language,seconds FROM notes WHERE text LIKE ? ESCAPE '\\'"
                    + " ORDER BY created DESC, id DESC",
                new String[] {"%" + escaped + "%"})) {
      while (c.moveToNext())
        out.add(new Note(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getInt(4)));
    }
    return out;
  }

  public void update(long id, String text) {
    ContentValues v = new ContentValues();
    v.put("text", text);
    getWritableDatabase().update("notes", v, "id=?", new String[] {"" + id});
  }

  public void delete(long id) {
    getWritableDatabase().delete("notes", "id=?", new String[] {"" + id});
  }
}
