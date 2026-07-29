package com.example.geckobrowser;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Synchronisation chiffree de GeckoBrowser via un document Android persistant.
 *
 * Le document peut vivre dans le stockage local, sur une carte SD ou chez un
 * fournisseur cloud expose par le Storage Access Framework. Le fournisseur ne
 * voit qu'un paquet AES-GCM. La phrase de chiffrement n'est jamais ecrite dans
 * le fichier de synchronisation.
 */
public final class EncryptedSyncManager {

    private static final String PREFS = "geckobrowser_encrypted_sync";
    private static final String KEY_URI = "uri";
    private static final String KEY_DEVICE_ID = "deviceId";
    private static final String KEY_DEVICE_NAME = "deviceName";
    private static final String KEY_LAST_REMOTE = "lastRemote";
    private static final String KEY_LAST_OK = "lastOk";
    private static final String KEY_LAST_ERROR = "lastError";
    private static final String KEY_CONFLICT = "conflict";
    private static final String KEY_AUTO = "autoPush";
    private static final String KEY_SAVED_SECRET = "savedSecret";

    private static final String KEYSTORE_ALIAS = "geckobrowser.encrypted-sync.local.v1";
    private static final int REQ_CREATE = 9230;
    private static final int REQ_OPEN = 9231;

    private static final byte[] MAGIC = "GBSYNC02".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 2;
    private static final int KDF_ITERATIONS = 600_000;
    private static final int SALT_SIZE = 16;
    private static final int IV_SIZE = 12;
    private static final int HEADER_SIZE = 8 + 4 + 4 + SALT_SIZE + IV_SIZE;
    private static final int MAX_PLAIN_SIZE = 8 * 1024 * 1024;

    private static final String MAIN_PREFS = "geckobrowser";
    private static final String WEBAPP_PREFS = "geckobrowser_webapps";

    private final Activity activity;
    private final Context app;
    private final SharedPreferences prefs;
    private final Runnable flushBeforeSnapshot;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "geckobrowser-encrypted-sync");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();

    private volatile boolean busy;
    private volatile boolean released;

    public EncryptedSyncManager(Activity activity, Runnable flushBeforeSnapshot) {
        this.activity = activity;
        Context application = activity.getApplicationContext();
        this.app = application == null ? activity : application;
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.flushBeforeSnapshot = flushBeforeSnapshot;
        ensureDeviceIdentity();
    }

    public String summary() {
        if (busy) return "operation en cours…";
        if (!isLinked()) return "aucun fichier lie";
        if (prefs.getBoolean(KEY_CONFLICT, false)) return "conflit distant";
        long last = prefs.getLong(KEY_LAST_OK, 0L);
        String auto = prefs.getBoolean(KEY_AUTO, false) ? " · auto" : "";
        if (last <= 0L) return "fichier lie" + auto;
        return "synchro " + relative(last) + auto;
    }

    public void show(Runnable back, Runnable legacySync) {
        Menus menu = new Menus(activity, "Synchronisation chiffree");
        Uri linked = linkedUri();

        if (linked == null) {
            menu.add("＋", "Creer un fichier chiffre",
                    "stockage local ou fournisseur cloud", this::createDocument);
            menu.add("↗", "Relier un fichier existant",
                    "ouvrir un paquet .gbsync", this::openDocument);
        } else {
            menu.add("◆", "Fichier lie", displayUri(linked), () -> { });
            menu.add("↑", "Envoyer cet appareil",
                    prefs.getBoolean(KEY_CONFLICT, false)
                            ? "bloque tant que le conflit n'est pas resolu"
                            : "chiffrer puis remplacer le fichier",
                    () -> withPhrase("Envoyer la synchronisation", false,
                            phrase -> push(phrase, false, false)));
            menu.add("↓", "Recevoir et fusionner",
                    "favoris, reglages, applications web et onglets",
                    () -> withPhrase("Recevoir la synchronisation", false,
                            this::pullAndConfirm));
            menu.add("≋", "Comparer les versions",
                    "sans modifier les donnees",
                    () -> withPhrase("Comparer les versions", false, this::compare));

            if (prefs.getBoolean(KEY_CONFLICT, false)) {
                menu.add("⚠", "Forcer l'envoi local",
                        "ecrase explicitement la version distante",
                        () -> confirmForcePush());
            }

            boolean remembered = hasRememberedPhrase();
            menu.add(remembered ? "●" : "○", "Phrase memorisee",
                    remembered ? "protegee par Android Keystore" : "non memorisee",
                    remembered ? this::forgetPhrase : this::rememberPhraseOnly);

            boolean auto = prefs.getBoolean(KEY_AUTO, false);
            menu.add(auto ? "●" : "○", "Sauvegarde automatique",
                    auto ? "a la mise en arriere-plan" : "desactivee",
                    () -> toggleAuto(auto));

            menu.add("✎", "Nom de cet appareil", deviceName(), this::renameDevice);
            menu.add("⌫", "Delier le fichier", "le document n'est pas supprime",
                    this::unlink);
        }

        if (legacySync != null) {
            menu.add("⌘", "Scripts et styles de l'extension",
                    "ouvrir l'ancien outil GitHub non chiffre", legacySync);
        }
        menu.add("ⓘ", "Securite et contenu synchronise", this::showHelp);
        if (back != null) menu.back(back);
        menu.show();
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_CREATE && requestCode != REQ_OPEN) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            toast("Aucun fichier selectionne");
            return true;
        }

        Uri uri = data.getData();
        int flags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            app.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Throwable ignored) { }

        prefs.edit()
                .putString(KEY_URI, uri.toString())
                .putBoolean(KEY_CONFLICT, false)
                .remove(KEY_LAST_ERROR)
                .apply();
        toast(requestCode == REQ_CREATE
                ? "Fichier de synchronisation cree"
                : "Fichier de synchronisation relie");
        return true;
    }

    /** Appelé apres la sauvegarde locale des onglets. */
    public void onPause() {
        if (released || busy || !prefs.getBoolean(KEY_AUTO, false) || !isLinked()) return;
        if (!hasRememberedPhrase()) return;
        long last = prefs.getLong("lastAutoAttempt", 0L);
        if (System.currentTimeMillis() - last < 60_000L) return;
        prefs.edit().putLong("lastAutoAttempt", System.currentTimeMillis()).apply();
        char[] phrase = loadRememberedPhrase();
        if (phrase == null || phrase.length == 0) return;
        push(phrase, false, true);
    }

    public void release() {
        released = true;
        executor.shutdownNow();
    }

    private void createDocument() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, "geckobrowser-sync.gbsync")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQ_CREATE);
        } catch (Throwable error) {
            toast("Le selecteur de fichiers est indisponible");
        }
    }

    private void openDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQ_OPEN);
        } catch (Throwable error) {
            toast("Le selecteur de fichiers est indisponible");
        }
    }

    private void toggleAuto(boolean currentlyEnabled) {
        if (!currentlyEnabled && !hasRememberedPhrase()) {
            Menus.info(activity, "Phrase requise",
                    "La sauvegarde automatique necessite une phrase memorisee sur cet appareil. "
                  + "Activez d'abord « Phrase memorisee ».");
            return;
        }
        prefs.edit().putBoolean(KEY_AUTO, !currentlyEnabled).apply();
        toast(!currentlyEnabled
                ? "Sauvegarde automatique activee"
                : "Sauvegarde automatique desactivee");
    }

    private void unlink() {
        Uri uri = linkedUri();
        if (uri != null) {
            try {
                app.getContentResolver().releasePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable ignored) { }
        }
        prefs.edit()
                .remove(KEY_URI)
                .remove(KEY_LAST_REMOTE)
                .remove(KEY_LAST_OK)
                .remove(KEY_LAST_ERROR)
                .putBoolean(KEY_CONFLICT, false)
                .putBoolean(KEY_AUTO, false)
                .apply();
        toast("Fichier delie");
    }

    private void confirmForcePush() {
        Menus.dialog(activity)
                .setTitle("Ecraser la version distante ?")
                .setMessage("La version distante semble avoir ete modifiee par un autre appareil. "
                          + "Cette action la remplacera par les donnees locales.")
                .setPositiveButton("Forcer l'envoi", (d, w) ->
                        withPhrase("Forcer l'envoi", false,
                                phrase -> push(phrase, true, false)))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void push(char[] phrase, boolean force, boolean automatic) {
        if (!begin()) {
            wipe(phrase);
            return;
        }
        executor.execute(() -> {
            try {
                if (flushBeforeSnapshot != null && !automatic) {
                    ui.post(flushBeforeSnapshot);
                    Thread.sleep(450L);
                }
                JSONObject local = snapshot();
                JSONObject remote = null;
                byte[] existing = readLinkedBytes(false);
                if (existing != null && existing.length > 0) {
                    remote = decrypt(existing, phrase);
                }

                long lastSeen = prefs.getLong(KEY_LAST_REMOTE, 0L);
                if (!force && remote != null
                        && remote.optLong("updated", 0L) > lastSeen
                        && !deviceId().equals(remote.optString("deviceId", ""))) {
                    prefs.edit()
                            .putBoolean(KEY_CONFLICT, true)
                            .putString(KEY_LAST_ERROR, "version distante plus recente")
                            .apply();
                    if (!automatic) toast("Conflit : recevez la version distante ou forcez l'envoi");
                    return;
                }

                byte[] encrypted = encrypt(local, phrase);
                writeLinkedBytes(encrypted);
                long updated = local.optLong("updated", System.currentTimeMillis());
                prefs.edit()
                        .putLong(KEY_LAST_REMOTE, updated)
                        .putLong(KEY_LAST_OK, System.currentTimeMillis())
                        .putBoolean(KEY_CONFLICT, false)
                        .remove(KEY_LAST_ERROR)
                        .apply();
                if (!automatic) toast("Synchronisation chiffree envoyee");
            } catch (WrongPhrase error) {
                recordError("Phrase incorrecte ou fichier endommage", automatic);
            } catch (Throwable error) {
                recordError(message(error), automatic);
            } finally {
                wipe(phrase);
                end();
            }
        });
    }

    private void pullAndConfirm(char[] phrase) {
        if (!begin()) {
            wipe(phrase);
            return;
        }
        executor.execute(() -> {
            try {
                byte[] bytes = readLinkedBytes(true);
                JSONObject remote = decrypt(bytes, phrase);
                String detail = describe(remote)
                        + "\n\nDate : " + formatDate(remote.optLong("updated", 0L))
                        + "\nAppareil : " + remote.optString("deviceName", "inconnu")
                        + "\n\nLes listes sont fusionnees. Pour les autres reglages, "
                        + "la version distante est appliquee.";
                ui.post(() -> Menus.dialog(activity)
                        .setTitle("Fusionner la version distante ?")
                        .setMessage(detail)
                        .setPositiveButton("Fusionner", (d, w) -> applyRemote(remote))
                        .setNegativeButton("Annuler", (d, w) -> end())
                        .setOnCancelListener(d -> end())
                        .show());
            } catch (WrongPhrase error) {
                recordError("Phrase incorrecte ou fichier endommage", false);
                end();
            } catch (Throwable error) {
                recordError(message(error), false);
                end();
            } finally {
                wipe(phrase);
            }
        });
    }

    private void applyRemote(JSONObject remote) {
        executor.execute(() -> {
            try {
                applyPreferences(remote.optJSONObject("preferences"));
                applySession(remote.optJSONObject("session"));
                long updated = remote.optLong("updated", 0L);
                prefs.edit()
                        .putLong(KEY_LAST_REMOTE, updated)
                        .putLong(KEY_LAST_OK, System.currentTimeMillis())
                        .putBoolean(KEY_CONFLICT, false)
                        .remove(KEY_LAST_ERROR)
                        .apply();
                ui.post(() -> Menus.dialog(activity)
                        .setTitle("Synchronisation restauree")
                        .setMessage("Les donnees ont ete fusionnees. Fermez puis rouvrez "
                                  + "GeckoBrowser pour recharger tous les reglages et onglets.")
                        .setPositiveButton("Compris", null)
                        .show());
            } catch (Throwable error) {
                recordError(message(error), false);
            } finally {
                end();
            }
        });
    }

    private void compare(char[] phrase) {
        if (!begin()) {
            wipe(phrase);
            return;
        }
        executor.execute(() -> {
            try {
                JSONObject local = snapshot();
                JSONObject remote = decrypt(readLinkedBytes(true), phrase);
                String text = "LOCAL\n" + describe(local)
                        + "\nAppareil : " + local.optString("deviceName", "")
                        + "\n\nDISTANT\n" + describe(remote)
                        + "\nDate : " + formatDate(remote.optLong("updated", 0L))
                        + "\nAppareil : " + remote.optString("deviceName", "inconnu");
                ui.post(() -> Menus.info(activity, "Comparaison", text));
            } catch (WrongPhrase error) {
                recordError("Phrase incorrecte ou fichier endommage", false);
            } catch (Throwable error) {
                recordError(message(error), false);
            } finally {
                wipe(phrase);
                end();
            }
        });
    }

    private JSONObject snapshot() throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "geckobrowser-encrypted-sync");
        root.put("version", FORMAT_VERSION);
        root.put("updated", System.currentTimeMillis());
        root.put("deviceId", deviceId());
        root.put("deviceName", deviceName());

        JSONObject allPrefs = new JSONObject();
        allPrefs.put(MAIN_PREFS, encodePreferences(MAIN_PREFS));
        allPrefs.put(WEBAPP_PREFS, encodePreferences(WEBAPP_PREFS));
        root.put("preferences", allPrefs);

        SessionStore.Snapshot session = SessionStore.read(app);
        JSONObject sessionJson = new JSONObject();
        if (session != null) {
            sessionJson.put("active", session.active);
            sessionJson.put("tabs", new JSONArray(session.tabs.toString()));
        } else {
            sessionJson.put("active", -1);
            sessionJson.put("tabs", new JSONArray());
        }
        root.put("session", sessionJson);
        return root;
    }

    private JSONObject encodePreferences(String name) throws Exception {
        SharedPreferences source = app.getSharedPreferences(name, Context.MODE_PRIVATE);
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> entry : source.getAll().entrySet()) {
            String key = entry.getKey();
            if (!syncableKey(name, key)) continue;
            Object value = entry.getValue();
            JSONObject encoded = encodeValue(value);
            if (encoded != null) out.put(key, encoded);
        }
        return out;
    }

    private JSONObject encodeValue(Object value) throws Exception {
        JSONObject out = new JSONObject();
        if (value instanceof String) {
            out.put("type", "string");
            out.put("value", value);
        } else if (value instanceof Boolean) {
            out.put("type", "boolean");
            out.put("value", value);
        } else if (value instanceof Integer) {
            out.put("type", "int");
            out.put("value", value);
        } else if (value instanceof Long) {
            out.put("type", "long");
            out.put("value", value);
        } else if (value instanceof Float) {
            out.put("type", "float");
            out.put("value", ((Float) value).doubleValue());
        } else if (value instanceof Set) {
            out.put("type", "set");
            JSONArray values = new JSONArray();
            for (Object item : (Set<?>) value) values.put(String.valueOf(item));
            out.put("value", values);
        } else {
            return null;
        }
        return out;
    }

    private void applyPreferences(JSONObject groups) throws Exception {
        if (groups == null) return;
        applyPreferenceGroup(MAIN_PREFS, groups.optJSONObject(MAIN_PREFS));
        applyPreferenceGroup(WEBAPP_PREFS, groups.optJSONObject(WEBAPP_PREFS));
    }

    private void applyPreferenceGroup(String name, JSONObject remote) throws Exception {
        if (remote == null) return;
        SharedPreferences local = app.getSharedPreferences(name, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = local.edit();
        java.util.Iterator<String> keys = remote.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!syncableKey(name, key)) continue;
            JSONObject encoded = remote.optJSONObject(key);
            if (encoded == null) continue;
            String type = encoded.optString("type", "");
            Object value = encoded.opt("value");

            if (MAIN_PREFS.equals(name) && "bookmarks".equals(key)) {
                value = mergeJsonArrays(local.getString(key, "[]"), String.valueOf(value),
                        "url", 500);
            } else if (MAIN_PREFS.equals(name) && "smartQuotes".equals(key)) {
                value = mergeJsonArrays(local.getString(key, "[]"), String.valueOf(value),
                        "text", 200);
            } else if (MAIN_PREFS.equals(name) && "trash".equals(key)) {
                value = mergeJsonArrays(local.getString(key, "[]"), String.valueOf(value),
                        "url", 50);
            } else if (WEBAPP_PREFS.equals(name) && "apps".equals(key)) {
                value = mergeJsonArrays(local.getString(key, "[]"), String.valueOf(value),
                        "id", 80);
            }

            switch (type) {
                case "string": editor.putString(key, value == null ? "" : String.valueOf(value)); break;
                case "boolean": editor.putBoolean(key, encoded.optBoolean("value", false)); break;
                case "int": editor.putInt(key, encoded.optInt("value", 0)); break;
                case "long": editor.putLong(key, encoded.optLong("value", 0L)); break;
                case "float": editor.putFloat(key, (float) encoded.optDouble("value", 0.0)); break;
                case "set":
                    JSONArray array = encoded.optJSONArray("value");
                    Set<String> set = new HashSet<>();
                    if (array != null) {
                        for (int i = 0; i < array.length(); i++) set.add(array.optString(i, ""));
                    }
                    editor.putStringSet(key, set);
                    break;
                default: break;
            }
        }
        if (!editor.commit()) throw new IllegalStateException("ecriture des reglages impossible");
    }

    private void applySession(JSONObject remote) throws Exception {
        if (remote == null) return;
        JSONArray remoteTabs = remote.optJSONArray("tabs");
        if (remoteTabs == null) remoteTabs = new JSONArray();
        SessionStore.Snapshot local = SessionStore.read(app);
        JSONArray localTabs = local == null ? new JSONArray() : local.tabs;
        JSONArray merged = mergeTabs(remoteTabs, localTabs, 20);
        int active = remote.optInt("active", merged.length() > 0 ? 0 : -1);
        if (!SessionStore.write(app, merged, Math.min(active, merged.length() - 1))) {
            throw new IllegalStateException("ecriture de la session impossible");
        }
    }

    private static JSONArray mergeTabs(JSONArray primary, JSONArray secondary, int limit)
            throws Exception {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        appendUnique(out, seen, primary, "url", limit);
        appendUnique(out, seen, secondary, "url", limit);
        return out;
    }

    private static String mergeJsonArrays(String localText, String remoteText,
                                          String identity, int limit) {
        try {
            JSONArray remote = new JSONArray(remoteText == null ? "[]" : remoteText);
            JSONArray local = new JSONArray(localText == null ? "[]" : localText);
            JSONArray out = new JSONArray();
            Set<String> seen = new HashSet<>();
            appendUnique(out, seen, remote, identity, limit);
            appendUnique(out, seen, local, identity, limit);
            return out.toString();
        } catch (Throwable ignored) {
            return remoteText == null ? "[]" : remoteText;
        }
    }

    private static void appendUnique(JSONArray out, Set<String> seen, JSONArray source,
                                     String identity, int limit) throws Exception {
        if (source == null) return;
        for (int i = 0; i < source.length() && out.length() < limit; i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString(identity, "");
            if (id.isEmpty()) id = item.toString();
            String normalized = id.trim().toLowerCase(Locale.ROOT);
            if (seen.add(normalized)) out.put(new JSONObject(item.toString()));
        }
    }

    private static boolean syncableKey(String group, String key) {
        if (key == null || key.isEmpty()) return false;
        if (MAIN_PREFS.equals(group)) {
            return !"session".equals(key)
                    && !"sessionActive".equals(key)
                    && !"tor".equals(key)
                    && !"passwordNoLockAcknowledged".equals(key);
        }
        return true;
    }

    private byte[] encrypt(JSONObject snapshot, char[] phrase) throws Exception {
        byte[] salt = new byte[SALT_SIZE];
        byte[] iv = new byte[IV_SIZE];
        random.nextBytes(salt);
        random.nextBytes(iv);

        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        header.put(MAGIC);
        header.putInt(FORMAT_VERSION);
        header.putInt(KDF_ITERATIONS);
        header.put(salt);
        header.put(iv);
        byte[] aad = header.array();

        byte[] compressed = gzip(snapshot.toString().getBytes(StandardCharsets.UTF_8));
        byte[] keyBytes = deriveKey(phrase, salt, KDF_ITERATIONS);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(aad.length + encrypted.length);
            out.write(aad);
            out.write(encrypted);
            return out.toByteArray();
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
            Arrays.fill(compressed, (byte) 0);
        }
    }

    private JSONObject decrypt(byte[] payload, char[] phrase) throws Exception {
        if (payload == null || payload.length <= HEADER_SIZE + 16) {
            throw new IllegalArgumentException("fichier vide ou incomplet");
        }
        byte[] aad = Arrays.copyOfRange(payload, 0, HEADER_SIZE);
        ByteBuffer header = ByteBuffer.wrap(aad);
        byte[] magic = new byte[MAGIC.length];
        header.get(magic);
        if (!Arrays.equals(MAGIC, magic)) throw new WrongPhrase();
        int version = header.getInt();
        int iterations = header.getInt();
        if (version != FORMAT_VERSION || iterations < 100_000 || iterations > 2_000_000) {
            throw new IllegalArgumentException("format de synchronisation non pris en charge");
        }
        byte[] salt = new byte[SALT_SIZE];
        byte[] iv = new byte[IV_SIZE];
        header.get(salt);
        header.get(iv);
        byte[] cipherText = Arrays.copyOfRange(payload, HEADER_SIZE, payload.length);
        byte[] keyBytes = deriveKey(phrase, salt, iterations);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad);
            byte[] compressed;
            try {
                compressed = cipher.doFinal(cipherText);
            } catch (Throwable authenticationFailure) {
                throw new WrongPhrase();
            }
            byte[] plain = gunzip(compressed);
            Arrays.fill(compressed, (byte) 0);
            try {
                JSONObject root = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                if (!"geckobrowser-encrypted-sync".equals(root.optString("format", ""))) {
                    throw new IllegalArgumentException("contenu distant invalide");
                }
                return root;
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
            Arrays.fill(cipherText, (byte) 0);
        }
    }

    private static byte[] deriveKey(char[] phrase, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(phrase, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] gzip(byte[] plain) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(plain);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] compressed) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_PLAIN_SIZE) throw new IllegalArgumentException("archive trop grande");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private byte[] readLinkedBytes(boolean requireContent) throws Exception {
        Uri uri = linkedUri();
        if (uri == null) throw new IllegalStateException("aucun fichier lie");
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new FileNotFoundException("fichier inaccessible");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > MAX_PLAIN_SIZE) {
                    throw new IllegalArgumentException("fichier distant trop grand");
                }
                out.write(buffer, 0, count);
            }
            byte[] bytes = out.toByteArray();
            if (requireContent && bytes.length == 0) throw new IllegalArgumentException("fichier vide");
            return bytes;
        }
    }

    private void writeLinkedBytes(byte[] bytes) throws Exception {
        Uri uri = linkedUri();
        if (uri == null) throw new IllegalStateException("aucun fichier lie");
        OutputStream stream = app.getContentResolver().openOutputStream(uri, "wt");
        if (stream == null) stream = app.getContentResolver().openOutputStream(uri);
        if (stream == null) throw new FileNotFoundException("fichier non modifiable");
        try (OutputStream out = stream) {
            out.write(bytes);
            out.flush();
        }
    }

    private void withPhrase(String title, boolean forceRemember, PhraseAction action) {
        char[] remembered = loadRememberedPhrase();
        if (remembered != null && remembered.length > 0 && !forceRemember) {
            action.run(remembered);
            return;
        }

        EditText input = new EditText(activity);
        input.setHint("Phrase secrete");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        CheckBox remember = new CheckBox(activity);
        remember.setText("Memoriser sur cet appareil avec Android Keystore");
        remember.setChecked(forceRemember);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input);
        box.addView(remember);

        android.app.AlertDialog dialog = Menus.dialog(activity)
                .setTitle(title)
                .setMessage("Utilisez la meme phrase sur tous les appareils. Minimum 10 caracteres.")
                .setView(box)
                .setPositiveButton("Continuer", null)
                .setNegativeButton("Annuler", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    char[] phrase = input.getText().toString().toCharArray();
                    if (phrase.length < 10) {
                        input.setError("10 caracteres minimum");
                        wipe(phrase);
                        return;
                    }
                    if (remember.isChecked()) rememberPhrase(phrase);
                    input.setText("");
                    dialog.dismiss();
                    action.run(phrase);
                }));
        dialog.show();
    }

    private void rememberPhraseOnly() {
        withPhrase("Memoriser la phrase", true, phrase -> {
            boolean saved = hasRememberedPhrase();
            wipe(phrase);
            if (saved) toast("Phrase memorisee avec Android Keystore");
        });
    }

    private void rememberPhrase(char[] phrase) {
        try {
            SecretKey key = localKeystoreKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] plain = new String(phrase).getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = cipher.doFinal(plain);
            byte[] iv = cipher.getIV();
            Arrays.fill(plain, (byte) 0);
            String packed = Base64.encodeToString(iv, Base64.NO_WRAP) + "."
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            prefs.edit().putString(KEY_SAVED_SECRET, packed).apply();
        } catch (Throwable error) {
            toast("Impossible de memoriser la phrase");
        }
    }

    private char[] loadRememberedPhrase() {
        String packed = prefs.getString(KEY_SAVED_SECRET, "");
        if (packed.isEmpty()) return null;
        try {
            String[] parts = packed.split("\\.", 2);
            if (parts.length != 2) return null;
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, localKeystoreKey(), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(encrypted);
            char[] phrase = new String(plain, StandardCharsets.UTF_8).toCharArray();
            Arrays.fill(plain, (byte) 0);
            return phrase;
        } catch (Throwable error) {
            prefs.edit().remove(KEY_SAVED_SECRET).putBoolean(KEY_AUTO, false).apply();
            return null;
        }
    }

    private boolean hasRememberedPhrase() {
        return !prefs.getString(KEY_SAVED_SECRET, "").isEmpty();
    }

    private void forgetPhrase() {
        prefs.edit().remove(KEY_SAVED_SECRET).putBoolean(KEY_AUTO, false).apply();
        toast("Phrase oubliee sur cet appareil");
    }

    private SecretKey localKeystoreKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEYSTORE_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            generator.generateKey();
        }
        KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) store.getEntry(
                KEYSTORE_ALIAS, null);
        if (entry == null) throw new IllegalStateException("cle locale absente");
        return entry.getSecretKey();
    }

    private void renameDevice() {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(deviceName());
        input.setSelectAllOnFocus(true);
        Menus.dialog(activity)
                .setTitle("Nom de cet appareil")
                .setView(input)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) prefs.edit().putString(KEY_DEVICE_NAME, value).apply();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showHelp() {
        Menus.info(activity, "Synchronisation chiffree",
                "Le fichier est chiffre sur l'appareil avant d'etre remis au fournisseur "
              + "de documents Android. Google Drive, Nextcloud, Dropbox ou un stockage "
              + "local ne voient qu'un paquet AES-256-GCM.\n\n"
              + "La cle du paquet est derivee de votre phrase avec PBKDF2-HMAC-SHA256 "
              + "et un sel aleatoire. Sans cette phrase, la restauration est impossible.\n\n"
              + "Sont synchronises : favoris, citations, reglages compatibles, catalogue "
              + "des applications web et onglets non prives. Les onglets prives, les "
              + "telechargements et le coffre de mots de passe lie a Android Keystore "
              + "sont exclus. Utilisez l'export .gbvault du coffre pour les mots de passe.\n\n"
              + "Les scripts et styles de l'extension gardent leur ancien outil GitHub, "
              + "accessible depuis ce menu.");
    }

    private String describe(JSONObject root) {
        JSONObject groups = root.optJSONObject("preferences");
        JSONObject main = groups == null ? null : groups.optJSONObject(MAIN_PREFS);
        JSONObject web = groups == null ? null : groups.optJSONObject(WEBAPP_PREFS);
        int bookmarks = countArrayPref(main, "bookmarks");
        int quotes = countArrayPref(main, "smartQuotes");
        int apps = countArrayPref(web, "apps");
        JSONObject session = root.optJSONObject("session");
        int tabs = session == null || session.optJSONArray("tabs") == null
                ? 0 : session.optJSONArray("tabs").length();
        return bookmarks + " favori(s), " + quotes + " citation(s), "
                + apps + " application(s) web, " + tabs + " onglet(s)";
    }

    private static int countArrayPref(JSONObject group, String key) {
        try {
            JSONObject item = group == null ? null : group.optJSONObject(key);
            if (item == null) return 0;
            return new JSONArray(item.optString("value", "[]")).length();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void ensureDeviceIdentity() {
        SharedPreferences.Editor edit = prefs.edit();
        if (prefs.getString(KEY_DEVICE_ID, "").isEmpty()) {
            edit.putString(KEY_DEVICE_ID, UUID.randomUUID().toString());
        }
        if (prefs.getString(KEY_DEVICE_NAME, "").isEmpty()) {
            String maker = Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER.trim();
            String model = Build.MODEL == null ? "appareil" : Build.MODEL.trim();
            String name = (maker + " " + model).trim();
            edit.putString(KEY_DEVICE_NAME, name.isEmpty() ? "Appareil Android" : name);
        }
        edit.apply();
    }

    private String deviceId() {
        return prefs.getString(KEY_DEVICE_ID, "");
    }

    private String deviceName() {
        return prefs.getString(KEY_DEVICE_NAME, "Appareil Android");
    }

    private boolean isLinked() {
        return linkedUri() != null;
    }

    private Uri linkedUri() {
        String raw = prefs.getString(KEY_URI, "");
        if (raw.isEmpty()) return null;
        try { return Uri.parse(raw); }
        catch (Throwable ignored) { return null; }
    }

    private boolean begin() {
        if (released || busy) {
            toast("Une synchronisation est deja en cours");
            return false;
        }
        busy = true;
        return true;
    }

    private void end() {
        busy = false;
    }

    private void recordError(String text, boolean silent) {
        prefs.edit().putString(KEY_LAST_ERROR, text).apply();
        if (!silent) toast("Synchronisation impossible : " + text);
    }

    private void toast(String text) {
        ui.post(() -> Toast.makeText(activity, text, Toast.LENGTH_LONG).show());
    }

    private String displayUri(Uri uri) {
        String value = uri == null ? "" : uri.toString();
        if (value.length() > 58) value = value.substring(0, 55) + "…";
        return value;
    }

    private static String message(Throwable error) {
        if (error == null) return "erreur inconnue";
        String value = error.getMessage();
        if (value == null || value.trim().isEmpty()) value = error.getClass().getSimpleName();
        return value;
    }

    private static String formatDate(long value) {
        if (value <= 0L) return "inconnue";
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
                Locale.getDefault()).format(new Date(value));
    }

    private static String relative(long value) {
        long age = Math.max(0L, System.currentTimeMillis() - value);
        long minutes = age / 60_000L;
        if (minutes < 1) return "a l'instant";
        if (minutes < 60) return "il y a " + minutes + " min";
        long hours = minutes / 60;
        if (hours < 24) return "il y a " + hours + " h";
        return "il y a " + (hours / 24) + " j";
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void wipe(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    private interface PhraseAction {
        void run(char[] phrase);
    }

    private static final class WrongPhrase extends Exception {
        WrongPhrase() { super("phrase incorrecte"); }
    }
}
