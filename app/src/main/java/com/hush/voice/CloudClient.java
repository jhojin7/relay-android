package com.hush.voice;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** OpenAI-compatible multipart audio transcription. Never follows credential-bearing redirects. */
final class CloudClient {
  interface Connections {
    HttpsURLConnection open(URL url) throws IOException;
  }

  private final Connections connections;

  CloudClient() {
    this(url -> (HttpsURLConnection) url.openConnection());
  }

  CloudClient(Connections factory) {
    connections = factory;
  }

  static URL endpoint(String value) throws IOException {
    try {
      URL url = new URI(value.trim()).toURL();
      if (!url.getProtocol().equals("https")
          || url.getHost().isEmpty()
          || url.getUserInfo() != null
          || url.getRef() != null
          || url.getQuery() != null)
        throw new IOException(
            "Use an HTTPS transcription URL without credentials, query parameters or fragments.");
      return url;
    } catch (URISyntaxException | IllegalArgumentException e) {
      throw new IOException("Enter a valid HTTPS transcription URL.");
    }
  }

  static void validateModel(String model) throws IOException {
    if (!model.matches("[A-Za-z0-9][A-Za-z0-9._/:+\\-]{0,199}"))
      throw new IOException("Enter a valid speech-to-text model ID.");
  }

  private HttpsURLConnection connect(URL url, String key, String method) throws IOException {
    HttpsURLConnection c = connections.open(url);
    c.setConnectTimeout(15000);
    c.setReadTimeout(90000);
    c.setInstanceFollowRedirects(false);
    c.setRequestMethod(method);
    if (key.contains("\r") || key.contains("\n")) throw new IOException("Invalid API key.");
    if (!key.trim().isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key.trim());
    c.setRequestProperty("Accept", "application/json");
    return c;
  }

  String transcribe(File audio, String address, String model, String key, String language)
      throws IOException {
    URL url = endpoint(address);
    validateModel(model);
    if (!audio.isFile() || audio.length() <= 44)
      throw new IOException("No recorded audio to transcribe.");
    if (audio.length() > 24L * 1024 * 1024)
      throw new IOException("Recording exceeds the upload limit.");
    String boundary = "Relay-" + java.util.UUID.randomUUID();
    HttpsURLConnection c = connect(url, key, "POST");
    try {
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
      ByteArrayOutputStream prefix = new ByteArrayOutputStream();
      field(prefix, boundary, "model", model);
      field(prefix, boundary, "response_format", "json");
      if (!language.equals("auto")) {
        String code = Locale.forLanguageTag(language).getLanguage();
        if (!code.isEmpty()) field(prefix, boundary, "language", code);
      }
      write(
          prefix,
          "--"
              + boundary
              + "\r\n"
              + "Content-Disposition: form-data; name=\"file\"; filename=\"recording.wav\"\r\n"
              + "Content-Type: audio/wav\r\n\r\n");
      byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
      c.setFixedLengthStreamingMode(prefix.size() + audio.length() + end.length);
      try (OutputStream out = new BufferedOutputStream(c.getOutputStream())) {
        prefix.writeTo(out);
        try (InputStream in = new FileInputStream(audio)) {
          byte[] buf = new byte[16384];
          int n;
          while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        out.write(end);
      }
      int status = c.getResponseCode();
      if (status < 200 || status >= 300) throw new IOException(error(status));
      String body = read(c.getInputStream());
      try {
        String text = new JSONObject(body).optString("text", "").trim();
        if (text.isEmpty())
          throw new IOException("Provider returned no transcript. Audio was kept for retry.");
        return text;
      } catch (JSONException e) {
        throw new IOException("Provider returned an invalid transcript response.");
      }
    } catch (java.net.SocketTimeoutException e) {
      throw new IOException("Request timed out. Audio is kept; retry from Transcripts.");
    } finally {
      c.disconnect();
    }
  }

  String check(String address, String key, String model) throws IOException {
    URL url = endpoint(address);
    String path = url.getPath();
    int marker = path.lastIndexOf("/audio/transcriptions");
    if (marker < 0)
      throw new IOException("This endpoint has no known model-list URL. Test it with a recording.");
    URL list =
        new URL(
            url.getProtocol(), url.getHost(), url.getPort(), path.substring(0, marker) + "/models");
    HttpsURLConnection c = connect(list, key, "GET");
    try {
      int code = c.getResponseCode();
      if (code == 404) return "No model-list endpoint. Test with a recording.";
      if (code < 200 || code >= 300) throw new IOException(error(code));
      try {
        JSONArray data = new JSONObject(read(c.getInputStream())).optJSONArray("data");
        if (data != null)
          for (int i = 0; i < data.length(); i++)
            if (model.equals(data.getJSONObject(i).optString("id")))
              return "Connected. Model is available.";
        return "Connected. Model was not listed; check its ID.";
      } catch (JSONException e) {
        return "Connected. Check the provider's supported transcription models.";
      }
    } finally {
      c.disconnect();
    }
  }

  static String error(int status) {
    return switch (status) {
      case 401, 403 -> "API key rejected (" + status + "). Update the key in Settings.";
      case 429 -> "Provider rate limit or quota reached (429). Retry later.";
      case 400, 404, 422 ->
          "Provider rejected the model, endpoint or audio (" + status + "). Check Settings.";
      case 413 -> "Recording is too large for this provider (413).";
      default ->
          status >= 300 && status < 400
              ? "Redirect refused. Enter the final HTTPS transcription URL."
              : "Transcription provider failed (HTTP " + status + "). Retry later.";
    };
  }

  private static void field(OutputStream out, String boundary, String name, String value)
      throws IOException {
    write(
        out,
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\""
            + name
            + "\"\r\n\r\n"
            + value
            + "\r\n");
  }

  private static void write(OutputStream out, String s) throws IOException {
    out.write(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String read(InputStream in) throws IOException {
    try (InputStream stream = in;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = stream.read(buf)) != -1) {
        if (out.size() + n > 2 * 1024 * 1024)
          throw new IOException("Provider response is too large.");
        out.write(buf, 0, n);
      }
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }
}
