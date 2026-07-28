package com.example.geckobrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.Autocomplete;
import org.mozilla.geckoview.GeckoResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Coffre local de mots de passe pour GeckoView.
 *
 * Le fichier reste dans le stockage prive de l'application. Son contenu est
 * chiffre avec AES-GCM, a l'aide d'une cle non exportable creee dans
 * AndroidKeyStore. Les sauvegardes portables sont elles aussi chiffrees, mais
 * avec une cle derivee de la phrase secrète choisie par l'utilisateur.
 */
public final class PasswordVault implements Autocomplete.StorageDelegate {

    public interface AutofillController {
        void setAutofillEnabled(boolean enabled);
    }

    private static final String PREFS = "geckobrowser";
    private static final String PREF_AUTOFILL = "passwordAutofill";
    private static final String PREF_SAVE = "passwordSavePrompts";
    private static final String PREF_NEVER = "passwordNeverSave";
    private static final String PREF_INSECURE_ACK = "passwordNoLockAcknowledged";

    private static final String FILE_NAME = "password-vault.bin";
    private static final String TEMP_NAME = "password-vault.bin.tmp";
    private static final String KEY_ALIAS = "geckobrowser.password-vault.v1";
    private static final byte[] MAGIC = new byte[] { 'G', 'B', 'P', 'V', 1 };
    private static final byte[] AAD =
            "GeckoBrowser.PasswordVault.v1".getBytes(StandardCharsets.UTF_8);

    private static final byte[] BACKUP_MAGIC = new byte[] { 'G', 'B', 'P', 'B', 1 };
    private static final int BACKUP_ITERATIONS = 210_000;
    private static final int MAX_BACKUP_BYTES = 10 * 1024 * 1024;

    private static final int REQ_AUTH = 9126;
    private static final int REQ_IMPORT = 9127;
    private static final long CLIPBOARD_TTL_MS = 30_000L;

    private static volatile PasswordVault instance;

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();

    private volatile Throwable lastError;
    private WeakReference<Activity> authActivity = new WeakReference<>(null);
    private Runnable pendingAuth;
    private Uri pendingImportUri;
    private Runnable pendingImportBack;
    private AutofillController pendingImportController;

    private PasswordVault(Context context) {
        Context application = context.getApplicationContext();
        app = application == null ? context : application;
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static PasswordVault get(Context context) {
        PasswordVault current = instance;
        if (current != null) return current;
        synchronized (PasswordVault.class) {
            if (instance == null) instance = new PasswordVault(context);
            return instance;
        }
    }

    // ---------------------------------------------------------------------
    // GeckoView Autocomplete.StorageDelegate
    // ---------------------------------------------------------------------

    @Override
    public GeckoResult<Autocomplete.LoginEntry[]> onLoginFetch(String domain) {
        if (!isAutofillEnabled()) {
            return GeckoResult.fromValue(new Autocomplete.LoginEntry[0]);
        }
        try {
            List<Entry> entries;
            synchronized (lock) { entries = readEntriesLocked(); }
            List<Autocomplete.LoginEntry> out = new ArrayList<>();
            for (Entry entry : entries) {
                if (matchesDomain(entry.origin, domain)) out.add(entry.toLogin());
            }
            lastError = null;
            return GeckoResult.fromValue(out.toArray(new Autocomplete.LoginEntry[0]));
        } catch (Throwable error) {
            lastError = error;
            return GeckoResult.fromValue(new Autocomplete.LoginEntry[0]);
        }
    }

    @Override
    public GeckoResult<Autocomplete.LoginEntry[]> onLoginFetch() {
        if (!isAutofillEnabled()) {
            return GeckoResult.fromValue(new Autocomplete.LoginEntry[0]);
        }
        try {
            List<Entry> entries;
            synchronized (lock) { entries = readEntriesLocked(); }
            Autocomplete.LoginEntry[] out = new Autocomplete.LoginEntry[entries.size()];
            for (int i = 0; i < entries.size(); i++) out[i] = entries.get(i).toLogin();
            lastError = null;
            return GeckoResult.fromValue(out);
        } catch (Throwable error) {
            lastError = error;
            return GeckoResult.fromValue(new Autocomplete.LoginEntry[0]);
        }
    }

    @Override
    public void onLoginSave(Autocomplete.LoginEntry login) {
        if (login == null || !isSaveEnabled()) return;
        try {
            boolean updated;
            synchronized (lock) { updated = upsertLocked(Entry.fromLogin(login)); }
            lastError = null;
            toast(updated ? "Mot de passe mis a jour" : "Mot de passe enregistre");
        } catch (Throwable error) {
            lastError = error;
            toast("Le mot de passe n'a pas pu etre enregistre");
        }
    }

    @Override
    public void onLoginUsed(Autocomplete.LoginEntry login, int usedFields) {
        if (login == null) return;
        try {
            synchronized (lock) {
                List<Entry> entries = readEntriesLocked();
                Entry found = find(entries, login.guid, login.origin, login.username,
                        login.httpRealm);
                if (found == null) return;
                found.used = System.currentTimeMillis();
                found.useCount++;
                writeEntriesLocked(entries);
            }
            lastError = null;
        } catch (Throwable error) {
            lastError = error;
        }
    }

    // ---------------------------------------------------------------------
    // Reglages et integration des invites GeckoView
    // ---------------------------------------------------------------------

    public boolean isAutofillEnabled() {
        return prefs.getBoolean(PREF_AUTOFILL, true);
    }

    public boolean isSaveEnabled() {
        return prefs.getBoolean(PREF_SAVE, true);
    }

    public boolean isBlocked(String origin) {
        return prefs.getStringSet(PREF_NEVER, Collections.emptySet())
                .contains(originKey(origin));
    }

    public void blockOrigin(String origin) {
        String key = originKey(origin);
        if (key.isEmpty()) return;
        Set<String> values = new HashSet<>(
                prefs.getStringSet(PREF_NEVER, Collections.emptySet()));
        values.add(key);
        prefs.edit().putStringSet(PREF_NEVER, values).apply();
    }

    public boolean hasLogin(Autocomplete.LoginEntry login) {
        if (login == null) return false;
        try {
            synchronized (lock) {
                List<Entry> entries = readEntriesLocked();
                return find(entries, login.guid, login.origin, login.username,
                        login.httpRealm) != null;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    public String summary() {
        Throwable error = lastError;
        if (error != null) return "coffre indisponible";
        int count = count();
        return count + " compte(s) · " + (isAutofillEnabled() ? "remplissage actif" : "inactif");
    }

    public int count() {
        try {
            synchronized (lock) { return readEntriesLocked().size(); }
        } catch (Throwable error) {
            lastError = error;
            return 0;
        }
    }

    public static String displayHost(String origin) {
        String host = hostOf(origin);
        return host.isEmpty() ? safe(origin) : host;
    }

    // ---------------------------------------------------------------------
    // Interface native du coffre
    // ---------------------------------------------------------------------

    public void show(Activity activity, Runnable back, AutofillController controller) {
        authenticate(activity, () -> showUnlocked(activity, back, controller));
    }

    private void showUnlocked(Activity activity, Runnable back, AutofillController controller) {
        List<Entry> entries;
        try {
            synchronized (lock) { entries = readEntriesLocked(); }
            lastError = null;
        } catch (Throwable error) {
            lastError = error;
            showBrokenVault(activity, back, controller, error);
            return;
        }

        entries.sort(Comparator
                .comparing((Entry entry) -> displayHost(entry.origin),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.username, String.CASE_INSENSITIVE_ORDER));

        Menus menu = new Menus(activity, "Mots de passe");
        menu.add(isAutofillEnabled() ? "●" : "○", "Remplissage automatique",
                isAutofillEnabled() ? "active" : "desactive", () -> {
                    boolean enabled = !isAutofillEnabled();
                    prefs.edit().putBoolean(PREF_AUTOFILL, enabled).apply();
                    if (controller != null) controller.setAutofillEnabled(enabled);
                    showUnlocked(activity, back, controller);
                });
        menu.add(isSaveEnabled() ? "●" : "○", "Proposer l'enregistrement",
                isSaveEnabled() ? "active" : "desactive", () -> {
                    prefs.edit().putBoolean(PREF_SAVE, !isSaveEnabled()).apply();
                    showUnlocked(activity, back, controller);
                });
        menu.add("＋", "Ajouter un compte", "saisie manuelle",
                () -> editEntry(activity, null, back, controller));
        menu.add("⚄", "Generer un mot de passe", "20 caracteres aleatoires",
                () -> showGeneratedPassword(activity));
        menu.sub("⇄", "Sauvegarde chiffree", "exporter ou restaurer",
                () -> showBackupMenu(activity, back, controller));
        menu.sub("⊘", "Sites ignores", blockedCount() + " site(s)",
                () -> showBlocked(activity, back, controller));

        for (Entry entry : entries) {
            String value = entry.username.isEmpty() ? "sans identifiant" : entry.username;
            if (entry.used > 0) value += " · utilise " + shortDate(activity, entry.used);
            final String guid = entry.guid;
            menu.sub("●", displayHost(entry.origin), value,
                    () -> showEntry(activity, guid, back, controller));
        }

        if (entries.isEmpty()) {
            menu.add("∅", "Aucun mot de passe", "les comptes enregistres apparaitront ici",
                    PasswordVault::noop);
        }
        menu.add("ⓘ", "Securite du coffre", "chiffrement local et presse-papiers",
                () -> showSecurityInfo(activity, back, controller));
        if (back != null) menu.back(back);
        menu.show();
    }

    private void showEntry(Activity activity, String guid, Runnable back,
                           AutofillController controller) {
        Entry entry = findByGuid(guid);
        if (entry == null) {
            toast("Compte introuvable");
            showUnlocked(activity, back, controller);
            return;
        }

        Menus menu = new Menus(activity, displayHost(entry.origin));
        menu.add("👤", "Copier l'identifiant",
                entry.username.isEmpty() ? "vide" : entry.username,
                () -> copySensitive(activity, "identifiant", entry.username, false));
        menu.add("●", "Copier le mot de passe", mask(entry.password),
                () -> copySensitive(activity, "mot de passe", entry.password, true));
        menu.add("◉", "Afficher le mot de passe", () -> Menus.info(activity,
                "Mot de passe", entry.password));
        menu.add("✎", "Modifier", () -> editEntry(activity, entry, back, controller));
        menu.add("⌫", "Supprimer", () -> confirmDelete(activity, entry, back, controller));
        menu.add("ⓘ", "Details", entryDetails(activity, entry), PasswordVault::noop);
        menu.back(() -> showUnlocked(activity, back, controller)).show();
    }

    private void editEntry(Activity activity, Entry existing, Runnable back,
                           AutofillController controller) {
        LinearLayout box = verticalBox(activity);
        EditText origin = field(activity, "https://exemple.fr", false);
        EditText username = field(activity, "Identifiant", false);
        EditText password = field(activity, "Mot de passe", true);
        CheckBox reveal = new CheckBox(activity);
        reveal.setText("Afficher le mot de passe");
        Button generate = new Button(activity);
        generate.setText("Generer un mot de passe fort");

        if (existing != null) {
            origin.setText(existing.origin);
            username.setText(existing.username);
            password.setText(existing.password);
        }
        reveal.setOnCheckedChangeListener((button, checked) -> {
            int pos = password.getSelectionStart();
            password.setInputType(InputType.TYPE_CLASS_TEXT |
                    (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                            : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            if (pos >= 0) password.setSelection(Math.min(pos, password.length()));
        });
        generate.setOnClickListener(view -> {
            password.setText(generatePassword(20));
            password.setSelection(password.length());
        });

        box.addView(label(activity, "Site"));
        box.addView(origin);
        box.addView(label(activity, "Identifiant"));
        box.addView(username);
        box.addView(label(activity, "Mot de passe"));
        box.addView(password);
        box.addView(reveal);
        box.addView(generate);

        Menus.dialog(activity)
                .setTitle(existing == null ? "Ajouter un compte" : "Modifier le compte")
                .setView(box)
                .setPositiveButton("Enregistrer", (dialog, which) -> {
                    String o = normalizeOrigin(origin.getText().toString());
                    String u = username.getText().toString().trim();
                    String p = password.getText().toString();
                    if (o.isEmpty() || p.isEmpty()) {
                        toast("Le site et le mot de passe sont obligatoires");
                        return;
                    }
                    Entry value = existing == null ? new Entry() : existing.copy();
                    if (value.guid.isEmpty()) value.guid = UUID.randomUUID().toString();
                    value.origin = o;
                    value.username = u;
                    value.password = p;
                    value.modified = System.currentTimeMillis();
                    if (value.created <= 0) value.created = value.modified;
                    try {
                        synchronized (lock) { upsertLocked(value); }
                        lastError = null;
                        toast(existing == null ? "Compte ajoute" : "Compte modifie");
                    } catch (Throwable error) {
                        lastError = error;
                        toast("Enregistrement impossible");
                    }
                    showUnlocked(activity, back, controller);
                })
                .setNegativeButton("Annuler", (dialog, which) ->
                        showUnlocked(activity, back, controller))
                .show();
    }

    private void confirmDelete(Activity activity, Entry entry, Runnable back,
                               AutofillController controller) {
        Menus.dialog(activity)
                .setTitle("Supprimer ce mot de passe ?")
                .setMessage(displayHost(entry.origin) + "\n"
                        + (entry.username.isEmpty() ? "Sans identifiant" : entry.username))
                .setPositiveButton("Supprimer", (dialog, which) -> {
                    try {
                        synchronized (lock) {
                            List<Entry> entries = readEntriesLocked();
                            entries.removeIf(item -> item.guid.equals(entry.guid));
                            writeEntriesLocked(entries);
                        }
                        lastError = null;
                        toast("Mot de passe supprime");
                    } catch (Throwable error) {
                        lastError = error;
                        toast("Suppression impossible");
                    }
                    showUnlocked(activity, back, controller);
                })
                .setNegativeButton("Annuler", (dialog, which) ->
                        showEntry(activity, entry.guid, back, controller))
                .show();
    }

    private void showGeneratedPassword(Activity activity) {
        String value = generatePassword(20);
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextIsSelectable(true);
        text.setTextSize(18f);
        text.setPadding(dp(activity, 24), dp(activity, 20), dp(activity, 24), dp(activity, 12));
        Menus.dialog(activity)
                .setTitle("Mot de passe genere")
                .setView(text)
                .setPositiveButton("Copier", (dialog, which) ->
                        copySensitive(activity, "mot de passe", value, true))
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void showBlocked(Activity activity, Runnable back,
                             AutofillController controller) {
        List<String> blocked = new ArrayList<>(
                prefs.getStringSet(PREF_NEVER, Collections.emptySet()));
        blocked.sort(String.CASE_INSENSITIVE_ORDER);
        Menus menu = new Menus(activity, "Sites ignores");
        if (blocked.isEmpty()) {
            menu.add("∅", "Aucun site ignore", PasswordVault::noop);
        } else {
            for (String origin : blocked) {
                menu.add("⊘", origin, "toucher pour autoriser a nouveau", () -> {
                    Set<String> values = new HashSet<>(
                            prefs.getStringSet(PREF_NEVER, Collections.emptySet()));
                    values.remove(origin);
                    prefs.edit().putStringSet(PREF_NEVER, values).apply();
                    showBlocked(activity, back, controller);
                });
            }
            menu.add("⌫", "Tout effacer", () -> {
                prefs.edit().remove(PREF_NEVER).apply();
                showBlocked(activity, back, controller);
            });
        }
        menu.back(() -> showUnlocked(activity, back, controller)).show();
    }

    private int blockedCount() {
        return prefs.getStringSet(PREF_NEVER, Collections.emptySet()).size();
    }

    private void showSecurityInfo(Activity activity, Runnable back,
                                  AutofillController controller) {
        Menus menu = new Menus(activity, "Securite du coffre");
        menu.add("🔒", "Chiffrement local",
                "AES-256-GCM · cle AndroidKeyStore", PasswordVault::noop);
        menu.add("⌛", "Presse-papiers temporaire",
                "effacement automatique apres 30 secondes", PasswordVault::noop);
        menu.add("⇄", "Sauvegardes portables",
                "toujours chiffrees par phrase secrete", PasswordVault::noop);
        menu.add("⚠", "Reinitialiser le coffre",
                "supprime definitivement tous les comptes", () ->
                        confirmReset(activity, back, controller));
        menu.back(() -> showUnlocked(activity, back, controller)).show();
    }

    private void showBrokenVault(Activity activity, Runnable back,
                                 AutofillController controller, Throwable error) {
        Menus.dialog(activity)
                .setTitle("Coffre indisponible")
                .setMessage("Le fichier chiffre ne peut pas etre ouvert. Cela peut arriver "
                        + "apres une restauration Android sans la cle du Keystore.\n\n"
                        + errorName(error) + "\n\nLa reinitialisation supprime le coffre illisible.")
                .setPositiveButton("Reinitialiser", (dialog, which) ->
                        authenticate(activity, () -> resetVault(activity, back, controller)))
                .setNegativeButton("Retour", (dialog, which) -> {
                    if (back != null) back.run();
                })
                .show();
    }

    private void confirmReset(Activity activity, Runnable back,
                              AutofillController controller) {
        Menus.dialog(activity)
                .setTitle("Reinitialiser le coffre ?")
                .setMessage("Tous les mots de passe enregistres seront perdus. "
                        + "Les sauvegardes .gbvault ne sont pas supprimees.")
                .setPositiveButton("Tout supprimer", (dialog, which) ->
                        resetVault(activity, back, controller))
                .setNegativeButton("Annuler", (dialog, which) ->
                        showSecurityInfo(activity, back, controller))
                .show();
    }

    private void resetVault(Activity activity, Runnable back,
                            AutofillController controller) {
        try {
            synchronized (lock) {
                new File(app.getFilesDir(), FILE_NAME).delete();
                new File(app.getFilesDir(), TEMP_NAME).delete();
                KeyStore store = KeyStore.getInstance("AndroidKeyStore");
                store.load(null);
                if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
            }
            lastError = null;
            toast("Coffre reinitialise");
            showUnlocked(activity, back, controller);
        } catch (Throwable error) {
            lastError = error;
            toast("Reinitialisation impossible");
        }
    }

    // ---------------------------------------------------------------------
    // Sauvegarde portable chiffree
    // ---------------------------------------------------------------------

    private void showBackupMenu(Activity activity, Runnable back,
                                AutofillController controller) {
        new Menus(activity, "Sauvegarde chiffree")
                .add("⇧", "Exporter le coffre", "fichier .gbvault protege par mot de passe",
                        () -> askExportPassphrase(activity, back, controller))
                .add("⇩", "Restaurer une sauvegarde", "fusionne les comptes sans les effacer",
                        () -> pickBackup(activity, back, controller))
                .add("ⓘ", "A savoir",
                        "la phrase secrete est indispensable pour restaurer",
                        PasswordVault::noop)
                .back(() -> showUnlocked(activity, back, controller))
                .show();
    }

    private void askExportPassphrase(Activity activity, Runnable back,
                                     AutofillController controller) {
        LinearLayout box = verticalBox(activity);
        EditText first = field(activity, "Phrase secrete (8 caracteres minimum)", true);
        EditText second = field(activity, "Confirmer la phrase secrete", true);
        box.addView(first);
        box.addView(second);
        Menus.dialog(activity)
                .setTitle("Exporter le coffre")
                .setMessage("Le fichier exporte ne contient jamais de mot de passe en clair.")
                .setView(box)
                .setPositiveButton("Exporter", (dialog, which) -> {
                    String a = first.getText().toString();
                    String b = second.getText().toString();
                    if (a.length() < 8 || !a.equals(b)) {
                        toast("Les phrases doivent etre identiques et contenir au moins 8 caracteres");
                        showBackupMenu(activity, back, controller);
                        return;
                    }
                    exportBackup(activity, a, back, controller);
                })
                .setNegativeButton("Annuler", (dialog, which) ->
                        showBackupMenu(activity, back, controller))
                .show();
    }

    private void exportBackup(Activity activity, String passphrase, Runnable back,
                              AutofillController controller) {
        toast("Creation de la sauvegarde…");
        new Thread(() -> {
            String message;
            try {
                byte[] bytes;
                synchronized (lock) { bytes = createBackupLocked(passphrase); }
                String name = "geckobrowser-passwords-"
                        + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date())
                        + ".gbvault";
                String path = writePublicBackup(name, bytes);
                message = "Sauvegarde chiffree : " + path;
            } catch (Throwable error) {
                message = "Echec de la sauvegarde : " + errorName(error);
            }
            final String result = message;
            ui.post(() -> {
                Toast.makeText(activity, result, Toast.LENGTH_LONG).show();
                showBackupMenu(activity, back, controller);
            });
        }, "password-vault-export").start();
    }

    private void pickBackup(Activity activity, Runnable back,
                            AutofillController controller) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/octet-stream", "application/zip", "*/*"
        });
        pendingImportBack = back;
        pendingImportController = controller;
        try {
            activity.startActivityForResult(Intent.createChooser(intent,
                    "Choisir une sauvegarde GeckoBrowser"), REQ_IMPORT);
        } catch (Throwable error) {
            pendingImportBack = null;
            pendingImportController = null;
            toast("Aucun selecteur de fichier disponible");
            showBackupMenu(activity, back, controller);
        }
    }

    private void askImportPassphrase(Activity activity, Uri uri, Runnable back,
                                     AutofillController controller) {
        EditText pass = field(activity, "Phrase secrete de la sauvegarde", true);
        LinearLayout box = verticalBox(activity);
        box.addView(pass);
        Menus.dialog(activity)
                .setTitle("Restaurer la sauvegarde")
                .setMessage("Les comptes plus recents sont conserves. Aucun compte existant "
                        + "n'est efface automatiquement.")
                .setView(box)
                .setPositiveButton("Restaurer", (dialog, which) -> {
                    String phrase = pass.getText().toString();
                    if (phrase.isEmpty()) {
                        toast("La phrase secrete est obligatoire");
                        showBackupMenu(activity, back, controller);
                        return;
                    }
                    importBackup(activity, uri, phrase, back, controller);
                })
                .setNegativeButton("Annuler", (dialog, which) ->
                        showBackupMenu(activity, back, controller))
                .show();
    }

    private void importBackup(Activity activity, Uri uri, String passphrase, Runnable back,
                              AutofillController controller) {
        toast("Lecture de la sauvegarde…");
        new Thread(() -> {
            String message;
            try {
                byte[] bytes = readUri(uri);
                List<Entry> incoming = decryptBackup(bytes, passphrase);
                int changed;
                synchronized (lock) { changed = mergeLocked(incoming); }
                lastError = null;
                message = changed + " compte(s) ajoute(s) ou mis a jour";
            } catch (Throwable error) {
                message = "Sauvegarde invalide ou phrase secrete incorrecte";
            }
            final String result = message;
            ui.post(() -> {
                Toast.makeText(activity, result, Toast.LENGTH_LONG).show();
                showBackupMenu(activity, back, controller);
            });
        }, "password-vault-import").start();
    }

    public boolean onActivityResult(Activity activity, int requestCode,
                                    int resultCode, Intent data) {
        if (requestCode == REQ_AUTH) {
            Runnable action = pendingAuth;
            pendingAuth = null;
            authActivity = new WeakReference<>(null);
            if (resultCode == Activity.RESULT_OK && action != null) action.run();
            else toast("Coffre verrouille");
            return true;
        }
        if (requestCode == REQ_IMPORT) {
            Runnable back = pendingImportBack;
            AutofillController controller = pendingImportController;
            pendingImportBack = null;
            pendingImportController = null;
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                pendingImportUri = uri;
                try {
                    app.getContentResolver().takePersistableUriPermission(uri,
                            data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) { }
                authenticate(activity, () -> {
                    Uri selected = pendingImportUri;
                    pendingImportUri = null;
                    askImportPassphrase(activity, selected, back, controller);
                });
            } else {
                pendingImportUri = null;
                showBackupMenu(activity, back, controller);
            }
            return true;
        }
        return false;
    }

    public void onActivityDestroyed(Activity activity) {
        Activity waiting = authActivity.get();
        if (waiting == activity) {
            pendingAuth = null;
            authActivity = new WeakReference<>(null);
        }
    }

    // ---------------------------------------------------------------------
    // Authentification systeme et presse-papiers sensible
    // ---------------------------------------------------------------------

    private void authenticate(Activity activity, Runnable action) {
        KeyguardManager keyguard =
                (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isDeviceSecure()) {
            Intent intent = keyguard.createConfirmDeviceCredentialIntent(
                    "Deverrouiller le coffre",
                    "Confirmez le code, le schema ou le mot de passe de l'appareil.");
            if (intent != null) {
                pendingAuth = action;
                authActivity = new WeakReference<>(activity);
                try {
                    activity.startActivityForResult(intent, REQ_AUTH);
                    return;
                } catch (Throwable ignored) {
                    pendingAuth = null;
                    authActivity = new WeakReference<>(null);
                }
            }
        }

        if (prefs.getBoolean(PREF_INSECURE_ACK, false)) {
            action.run();
            return;
        }
        Menus.dialog(activity)
                .setTitle("Aucun verrouillage d'ecran")
                .setMessage("Le coffre est chiffre, mais l'affichage des mots de passe ne peut "
                        + "pas etre protege par le code de l'appareil tant qu'aucun verrouillage "
                        + "securise n'est configure.")
                .setPositiveButton("Continuer", (dialog, which) -> {
                    prefs.edit().putBoolean(PREF_INSECURE_ACK, true).apply();
                    action.run();
                })
                .setNeutralButton("Reglages de securite", (dialog, which) -> {
                    try { activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); }
                    catch (Throwable ignored) { }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void copySensitive(Activity activity, String label, String value,
                               boolean sensitive) {
        if (value == null || value.isEmpty()) {
            toast("Rien a copier");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText(label, value);
        if (sensitive) {
            try {
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            } catch (Throwable ignored) { }
        }
        clipboard.setPrimaryClip(clip);
        toast(sensitive ? "Mot de passe copie pour 30 secondes" : "Identifiant copie");
        final String expected = value;
        ui.postDelayed(() -> clearClipboardIfSame(clipboard, expected), CLIPBOARD_TTL_MS);
    }

    private static void clearClipboardIfSame(ClipboardManager clipboard, String expected) {
        try {
            if (!clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null
                    || clipboard.getPrimaryClip().getItemCount() == 0) return;
            CharSequence current = clipboard.getPrimaryClip().getItemAt(0).getText();
            if (current == null || !expected.contentEquals(current)) return;
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip();
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------------
    // Stockage chiffre AndroidKeyStore
    // ---------------------------------------------------------------------

    private List<Entry> readEntriesLocked() throws Exception {
        File file = new File(app.getFilesDir(), FILE_NAME);
        if (!file.isFile()) return new ArrayList<>();
        byte[] raw = Files.readAllBytes(file.toPath());
        if (raw.length < MAGIC.length + 1 + 4) throw new Exception("fichier tronque");

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) throw new Exception("format inconnu");
            int ivLength = in.readUnsignedByte();
            if (ivLength < 12 || ivLength > 32) throw new Exception("vecteur invalide");
            byte[] iv = new byte[ivLength];
            in.readFully(iv);
            int cipherLength = in.readInt();
            if (cipherLength < 16 || cipherLength > raw.length) {
                throw new Exception("taille invalide");
            }
            byte[] encrypted = new byte[cipherLength];
            in.readFully(encrypted);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyLocked(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            byte[] plain = cipher.doFinal(encrypted);
            JSONArray array = new JSONArray(new String(plain, StandardCharsets.UTF_8));
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Entry entry = Entry.fromJson(object);
                if (!entry.origin.isEmpty() && !entry.password.isEmpty()) entries.add(entry);
            }
            return entries;
        }
    }

    private void writeEntriesLocked(List<Entry> entries) throws Exception {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) array.put(entry.toJson());
        byte[] plain = array.toString().getBytes(StandardCharsets.UTF_8);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keyLocked());
        cipher.updateAAD(AAD);
        byte[] encrypted = cipher.doFinal(plain);
        byte[] iv = cipher.getIV();

        File target = new File(app.getFilesDir(), FILE_NAME);
        File temp = new File(app.getFilesDir(), TEMP_NAME);
        try (FileOutputStream file = new FileOutputStream(temp);
             DataOutputStream out = new DataOutputStream(file)) {
            out.write(MAGIC);
            out.writeByte(iv.length);
            out.write(iv);
            out.writeInt(encrypted.length);
            out.write(encrypted);
            out.flush();
            file.getFD().sync();
        }
        try {
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private SecretKey keyLocked() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry current = store.getEntry(KEY_ALIAS, null);
        if (current instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) current).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private boolean upsertLocked(Entry incoming) throws Exception {
        List<Entry> entries = readEntriesLocked();
        Entry existing = find(entries, incoming.guid, incoming.origin, incoming.username,
                incoming.httpRealm);
        boolean updated = existing != null;
        if (existing == null) {
            if (incoming.guid.isEmpty()) incoming.guid = UUID.randomUUID().toString();
            if (incoming.created <= 0) incoming.created = System.currentTimeMillis();
            incoming.modified = Math.max(incoming.modified, incoming.created);
            entries.add(incoming);
        } else {
            existing.origin = incoming.origin;
            existing.formActionOrigin = incoming.formActionOrigin;
            existing.httpRealm = incoming.httpRealm;
            existing.username = incoming.username;
            existing.password = incoming.password;
            existing.modified = System.currentTimeMillis();
            if (incoming.used > existing.used) existing.used = incoming.used;
            if (incoming.useCount > existing.useCount) existing.useCount = incoming.useCount;
        }
        writeEntriesLocked(entries);
        return updated;
    }

    private int mergeLocked(List<Entry> incoming) throws Exception {
        List<Entry> current = readEntriesLocked();
        int changed = 0;
        for (Entry candidate : incoming) {
            Entry existing = find(current, candidate.guid, candidate.origin,
                    candidate.username, candidate.httpRealm);
            if (existing == null) {
                if (candidate.guid.isEmpty()) candidate.guid = UUID.randomUUID().toString();
                current.add(candidate);
                changed++;
            } else if (candidate.modified > existing.modified) {
                existing.origin = candidate.origin;
                existing.formActionOrigin = candidate.formActionOrigin;
                existing.httpRealm = candidate.httpRealm;
                existing.username = candidate.username;
                existing.password = candidate.password;
                existing.modified = candidate.modified;
                existing.used = Math.max(existing.used, candidate.used);
                existing.useCount = Math.max(existing.useCount, candidate.useCount);
                changed++;
            }
        }
        if (changed > 0) writeEntriesLocked(current);
        return changed;
    }

    private Entry findByGuid(String guid) {
        try {
            synchronized (lock) {
                for (Entry entry : readEntriesLocked()) {
                    if (entry.guid.equals(guid)) return entry;
                }
            }
        } catch (Throwable error) {
            lastError = error;
        }
        return null;
    }

    private static Entry find(List<Entry> entries, String guid, String origin,
                              String username, String realm) {
        String g = safe(guid);
        if (!g.isEmpty()) {
            for (Entry entry : entries) if (g.equals(entry.guid)) return entry;
        }
        String key = originKey(origin);
        String user = safe(username);
        String r = safe(realm);
        for (Entry entry : entries) {
            if (originKey(entry.origin).equals(key)
                    && entry.username.equals(user)
                    && entry.httpRealm.equals(r)) return entry;
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Format de sauvegarde portable
    // ---------------------------------------------------------------------

    private byte[] createBackupLocked(String passphrase) throws Exception {
        List<Entry> entries = readEntriesLocked();
        JSONArray array = new JSONArray();
        for (Entry entry : entries) array.put(entry.toJson());
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("created", System.currentTimeMillis());
        root.put("entries", array);
        byte[] plain = root.toString().getBytes(StandardCharsets.UTF_8);

        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(iv);
        SecretKey key = backupKey(passphrase, salt, BACKUP_ITERATIONS);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(BACKUP_MAGIC);
        byte[] encrypted = cipher.doFinal(plain);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(BACKUP_MAGIC);
            out.writeInt(BACKUP_ITERATIONS);
            out.writeByte(salt.length);
            out.write(salt);
            out.writeByte(iv.length);
            out.write(iv);
            out.writeInt(encrypted.length);
            out.write(encrypted);
        }
        return bytes.toByteArray();
    }

    private List<Entry> decryptBackup(byte[] raw, String passphrase) throws Exception {
        if (raw == null || raw.length < 32 || raw.length > MAX_BACKUP_BYTES) {
            throw new Exception("taille invalide");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            byte[] magic = new byte[BACKUP_MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, BACKUP_MAGIC)) throw new Exception("format invalide");
            int iterations = in.readInt();
            if (iterations < 100_000 || iterations > 1_000_000) {
                throw new Exception("parametre invalide");
            }
            int saltLength = in.readUnsignedByte();
            if (saltLength < 12 || saltLength > 64) throw new Exception("sel invalide");
            byte[] salt = new byte[saltLength];
            in.readFully(salt);
            int ivLength = in.readUnsignedByte();
            if (ivLength < 12 || ivLength > 32) throw new Exception("iv invalide");
            byte[] iv = new byte[ivLength];
            in.readFully(iv);
            int encryptedLength = in.readInt();
            if (encryptedLength < 16 || encryptedLength > raw.length) {
                throw new Exception("donnees invalides");
            }
            byte[] encrypted = new byte[encryptedLength];
            in.readFully(encrypted);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, backupKey(passphrase, salt, iterations),
                    new GCMParameterSpec(128, iv));
            cipher.updateAAD(BACKUP_MAGIC);
            byte[] plain = cipher.doFinal(encrypted);
            JSONObject root = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            JSONArray array = root.optJSONArray("entries");
            if (array == null) throw new Exception("liste absente");
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Entry entry = Entry.fromJson(object);
                if (!entry.origin.isEmpty() && !entry.password.isEmpty()) entries.add(entry);
            }
            return entries;
        }
    }

    private static SecretKey backupKey(String passphrase, byte[] salt,
                                       int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256);
        try {
            byte[] encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(encoded, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private String writePublicBackup(String name, byte[] bytes) throws Exception {
        ContentResolver resolver = app.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            String relative = Environment.DIRECTORY_DOWNLOADS + "/GeckoBrowser";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH, relative);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("dossier inaccessible");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new Exception("ecriture impossible");
                out.write(bytes);
                out.flush();
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return relative + "/" + name;
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "GeckoBrowser");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("dossier inaccessible");
        File target = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
        return target.getAbsolutePath();
    }

    private byte[] readUri(Uri uri) throws Exception {
        if (uri == null) throw new Exception("fichier absent");
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("lecture impossible");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[32 * 1024];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BACKUP_BYTES) throw new Exception("fichier trop grand");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------------
    // Utilitaires
    // ---------------------------------------------------------------------

    private static final class Entry {
        String guid = "";
        String origin = "";
        String formActionOrigin = "";
        String httpRealm = "";
        String username = "";
        String password = "";
        long created = System.currentTimeMillis();
        long modified = created;
        long used = 0;
        int useCount = 0;

        static Entry fromLogin(Autocomplete.LoginEntry login) {
            Entry entry = new Entry();
            entry.guid = safe(login.guid);
            entry.origin = normalizeOrigin(login.origin);
            entry.formActionOrigin = safe(login.formActionOrigin);
            entry.httpRealm = safe(login.httpRealm);
            entry.username = safe(login.username);
            entry.password = safe(login.password);
            if (entry.guid.isEmpty()) entry.guid = UUID.randomUUID().toString();
            return entry;
        }

        static Entry fromJson(JSONObject object) {
            Entry entry = new Entry();
            entry.guid = object.optString("guid", "");
            entry.origin = object.optString("origin", "");
            entry.formActionOrigin = object.optString("formActionOrigin", "");
            entry.httpRealm = object.optString("httpRealm", "");
            entry.username = object.optString("username", "");
            entry.password = object.optString("password", "");
            entry.created = object.optLong("created", System.currentTimeMillis());
            entry.modified = object.optLong("modified", entry.created);
            entry.used = object.optLong("used", 0);
            entry.useCount = object.optInt("useCount", 0);
            if (entry.guid.isEmpty()) entry.guid = UUID.randomUUID().toString();
            return entry;
        }

        JSONObject toJson() throws Exception {
            JSONObject object = new JSONObject();
            object.put("guid", guid);
            object.put("origin", origin);
            object.put("formActionOrigin", formActionOrigin);
            object.put("httpRealm", httpRealm);
            object.put("username", username);
            object.put("password", password);
            object.put("created", created);
            object.put("modified", modified);
            object.put("used", used);
            object.put("useCount", useCount);
            return object;
        }

        Autocomplete.LoginEntry toLogin() {
            Autocomplete.LoginEntry.Builder builder = new Autocomplete.LoginEntry.Builder()
                    .guid(guid)
                    .origin(origin)
                    .username(username)
                    .password(password);
            if (!formActionOrigin.isEmpty()) builder.formActionOrigin(formActionOrigin);
            if (!httpRealm.isEmpty()) builder.httpRealm(httpRealm);
            return builder.build();
        }

        Entry copy() {
            Entry value = new Entry();
            value.guid = guid;
            value.origin = origin;
            value.formActionOrigin = formActionOrigin;
            value.httpRealm = httpRealm;
            value.username = username;
            value.password = password;
            value.created = created;
            value.modified = modified;
            value.used = used;
            value.useCount = useCount;
            return value;
        }
    }

    private static boolean matchesDomain(String origin, String domain) {
        String wanted = normalizeHost(domain);
        String host = normalizeHost(hostOf(origin));
        return !wanted.isEmpty() && wanted.equals(host);
    }

    private static String normalizeHost(String value) {
        String host = safe(value).trim().toLowerCase(Locale.ROOT);
        if (host.contains("://")) host = hostOf(host);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) host = host.substring(0, colon);
        if (host.startsWith("www.")) host = host.substring(4);
        return host;
    }

    private static String hostOf(String origin) {
        String value = safe(origin).trim();
        if (value.isEmpty()) return "";
        try {
            Uri uri = Uri.parse(value.contains("://") ? value : "https://" + value);
            String host = uri.getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String normalizeOrigin(String value) {
        String origin = safe(value).trim();
        if (origin.isEmpty()) return "";
        if (!origin.contains("://")) origin = "https://" + origin;
        try {
            Uri uri = Uri.parse(origin);
            String scheme = safe(uri.getScheme()).toLowerCase(Locale.ROOT);
            String host = safe(uri.getHost()).toLowerCase(Locale.ROOT);
            if (scheme.isEmpty() || host.isEmpty()) return "";
            int port = uri.getPort();
            return scheme + "://" + host + (port > 0 ? ":" + port : "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String originKey(String origin) {
        String normalized = normalizeOrigin(origin);
        return normalized.isEmpty() ? safe(origin).trim().toLowerCase(Locale.ROOT) : normalized;
    }

    private String generatePassword(int length) {
        final String lower = "abcdefghijkmnopqrstuvwxyz";
        final String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        final String digits = "23456789";
        final String symbols = "!@#$%&*+-_=?.";
        final String all = lower + upper + digits + symbols;
        int size = Math.max(12, length);
        char[] out = new char[size];
        out[0] = lower.charAt(random.nextInt(lower.length()));
        out[1] = upper.charAt(random.nextInt(upper.length()));
        out[2] = digits.charAt(random.nextInt(digits.length()));
        out[3] = symbols.charAt(random.nextInt(symbols.length()));
        for (int i = 4; i < out.length; i++) out[i] = all.charAt(random.nextInt(all.length()));
        for (int i = out.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = out[i]; out[i] = out[j]; out[j] = tmp;
        }
        return new String(out);
    }

    private static LinearLayout verticalBox(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 22);
        box.setPadding(pad, dp(context, 12), pad, dp(context, 4));
        return box;
    }

    private static EditText field(Context context, String hint, boolean password) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setSingleLine(true);
        if (password) {
            input.setInputType(InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        input.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private static TextView label(Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setPadding(0, dp(context, 8), 0, 0);
        return label;
    }

    private static String mask(String password) {
        int count = Math.max(8, Math.min(16, safe(password).length()));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append('•');
        return out.toString();
    }

    private static String entryDetails(Context context, Entry entry) {
        StringBuilder out = new StringBuilder();
        out.append("Site : ").append(entry.origin);
        if (!entry.httpRealm.isEmpty()) out.append("\nRealm : ").append(entry.httpRealm);
        out.append("\nCree : ").append(shortDate(context, entry.created));
        out.append("\nModifie : ").append(shortDate(context, entry.modified));
        if (entry.used > 0) out.append("\nDerniere utilisation : ")
                .append(shortDate(context, entry.used));
        out.append("\nUtilisations : ").append(entry.useCount);
        return out.toString();
    }

    private static String shortDate(Context context, long time) {
        if (time <= 0) return "jamais";
        try {
            return android.text.format.DateFormat.getMediumDateFormat(context)
                    .format(new Date(time));
        } catch (Throwable ignored) {
            return new SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(new Date(time));
        }
    }

    private static String errorName(Throwable error) {
        if (error == null) return "erreur inconnue";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private void toast(String text) {
        ui.post(() -> Toast.makeText(app, text, Toast.LENGTH_SHORT).show());
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void noop() { }
}
