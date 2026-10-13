package com.hush.voice;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/**
 * API keys are encrypted with a non-exportable Android Keystore AES key. The exact endpoint is
 * authenticated as AAD so credentials cannot migrate between URLs.
 */
final class CredentialStore {
  private static final String ALIAS = "hush.api.credentials.v1";

  private static SecretKey key() throws GeneralSecurityException, java.io.IOException {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
    KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    gen.init(
        new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
    return gen.generateKey();
  }

  private static android.content.SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("credentials", Context.MODE_PRIVATE);
  }

  static synchronized void put(Context c, String endpoint, String secret) throws Exception {
    if (secret.trim().isEmpty()) {
      remove(c, endpoint);
      return;
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    cipher.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
    byte[] encrypted = cipher.doFinal(secret.trim().getBytes(StandardCharsets.UTF_8));
    String value =
        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + ":"
            + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    if (!prefs(c).edit().putString(endpoint, value).commit())
      throw new java.io.IOException("Couldn't save credentials");
  }

  static synchronized String get(Context c, String endpoint) throws Exception {
    String value = prefs(c).getString(endpoint, "");
    if (value.isEmpty()) return "";
    String[] parts = value.split(":", 2);
    if (parts.length != 2) throw new GeneralSecurityException("Invalid credential record");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    cipher.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
    return new String(
        cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }

  static boolean has(Context c, String endpoint) {
    return prefs(c).contains(endpoint);
  }

  static void remove(Context c, String endpoint) {
    prefs(c).edit().remove(endpoint).apply();
  }
}
