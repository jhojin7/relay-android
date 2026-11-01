package com.hush.voice;

import android.content.Context;
import android.database.Cursor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.json.*;

/** Portable audio/text pairs. Never exports credentials, endpoints, or raw provider responses. */
final class EvaluationArchive {
  static int write(Context context, OutputStream output) throws IOException {
    try (NoteStore store = new NoteStore(context);
        Cursor rows =
            store
                .getReadableDatabase()
                .rawQuery(
                    "SELECT"
                        + " n.id,n.created,n.text,n.language,n.seconds,e.file,e.original_text,e.provider,e.model"
                        + " FROM evaluation e JOIN notes n ON n.id=e.note_id ORDER BY n.id",
                    null);
        ZipOutputStream zip = new ZipOutputStream(output)) {
      if (rows.getCount() == 0) throw new IOException("No saved evaluation recordings");
      StringBuilder metadata = new StringBuilder();
      byte[] buffer = new byte[32768];
      int count = 0;
      while (rows.moveToNext()) {
        long id = rows.getLong(0);
        String path = "audio/" + id + ".wav";
        File audio = new File(CloudJobs.directory(context), rows.getString(5));
        zip.putNextEntry(new ZipEntry(path));
        try (InputStream input = new FileInputStream(audio)) {
          int read;
          while ((read = input.read(buffer)) != -1) zip.write(buffer, 0, read);
        }
        zip.closeEntry();
        JSONObject record = new JSONObject();
        record.put("note_id", id);
        record.put("created_at_ms", rows.getLong(1));
        record.put("transcript", rows.getString(2));
        record.put("language", rows.getString(3));
        record.put("duration_seconds", rows.getInt(4));
        record.put("audio", path);
        record.put("original_transcript", rows.getString(6));
        record.put("provider", rows.getString(7));
        record.put("model", rows.getString(8));
        metadata.append(record).append('\n');
        count++;
      }
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(
          ("{\"schema_version\":1,\"recordings\":"
                  + count
                  + ",\"audio_format\":\"PCM WAV, 16000 Hz, mono, signed 16-bit little-endian\"}")
              .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("recordings.jsonl"));
      zip.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      return count;
    } catch (JSONException e) {
      throw new IOException("Couldn't encode evaluation metadata", e);
    }
  }
}
