package com.example.geckobrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebResponse;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;

public class MainActivity extends Activity {

    private static GeckoRuntime sRuntime;
    private static String searchBase = null;   // moz-extension://<uuid>/search.html

    private GeckoSession session;
    private EditText urlBar;
    private TextView shield;
    private boolean canGoBack = false;
    private String currentUrl = "";
    private String currentTitle = "";

    private WebExtension.Port blockerPort;
    private boolean blockerEnabled = true;
    private int blockedCount = 0;
    private boolean desktopMode = false;
    private boolean privateMode = false;
    private GeckoView geckoView;

    private SharedPreferences prefs;
    private org.json.JSONArray gmCommands = new org.json.JSONArray();
    private Permissions permissions;
    private android.widget.ProgressBar progress;

    private static final int REQ_FILE = 8123;
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pendingFile;
    private GeckoSession.PromptDelegate.FilePrompt pendingFilePrompt;

    private static final String EXT_ID = "adblock@geckobrowser";
    private static final String EXT_URL = "resource://android/assets/adblock/";
    private static final String FALLBACK_HOME = "https://html.duckduckgo.com/html/";

    // -----------------------------------------------------------------------
    //  Moteurs disponibles pour la barre d'adresse.
    //  "%s" est remplace par la requete encodee.
    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    //  Profils d'appareil : nom, agent utilisateur, plateforme JS,
    //  points tactiles, mise en page bureau (1) ou mobile (0).
    // -----------------------------------------------------------------------
    private static final String[][] PROFILES = {
        { "Automatique", "", "", "", "0" },

        { "Telephone Android",
          "Mozilla/5.0 (Android 14; Mobile; rv:140.0) Gecko/20100101 Firefox/140.0",
          "Linux aarch64", "5", "0" },

        { "Tablette Android",
          "Mozilla/5.0 (Android 14; Tablet; rv:140.0) Gecko/20100101 Firefox/140.0",
          "Linux aarch64", "5", "0" },

        { "iPhone",
          "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
          + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
          "iPhone", "5", "0" },

        { "iPad",
          "Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
          + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
          "iPad", "5", "1" },

        { "PC Windows (Firefox)",
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
          "Win32", "0", "1" },

        { "PC Windows (Chrome)",
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
          "Win32", "0", "1" },

        { "Mac (Safari)",
          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
          + "(KHTML, like Gecko) Version/17.5 Safari/605.1.15",
          "MacIntel", "0", "1" },

        { "PC Linux (Firefox)",
          "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0",
          "Linux x86_64", "0", "1" },

        { "Personnalise…", "custom", "", "", "1" }
    };

    private static final String[][] ENGINES = {
        { "Metamoteur integre",  "internal" },
        { "DuckDuckGo",          "https://duckduckgo.com/?q=%s" },
        { "Qwant",               "https://www.qwant.com/?q=%s" },
        { "Ecosia",              "https://www.ecosia.org/search?q=%s" },
        { "Brave",               "https://search.brave.com/search?q=%s" },
        { "Startpage",           "https://www.startpage.com/sp/search?query=%s" },
        { "Mojeek",              "https://www.mojeek.com/search?q=%s" },
        { "Marginalia",          "https://search.marginalia.nu/search?query=%s" },
        { "Wikipedia",           "https://fr.wikipedia.org/w/index.php?search=%s" },
        { "OpenStreetMap",       "https://www.openstreetmap.org/search?query=%s" },
        { "Google",              "https://www.google.com/search?q=%s" },
        { "Bing",                "https://www.bing.com/search?q=%s" },
        { "Ahmia (.onion)",      "https://ahmia.fi/search/?q=%s" },
        { "Ahmia via Tor",
          "http://juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion/search/?q=%s" },
        { "DuckDuckGo via Tor",
          "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/?q=%s" },
        { "Personnalise…",       "custom" }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("geckobrowser", MODE_PRIVATE);
        blockerEnabled = prefs.getBoolean("blockerEnabled", true);

        geckoView = findViewById(R.id.geckoview);
        urlBar = findViewById(R.id.url_bar);
        shield = findViewById(R.id.shield);
        ImageButton goButton = findViewById(R.id.go_button);
        ImageButton menuButton = findViewById(R.id.menu_button);
        progress = findViewById(R.id.progress);

        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(this, buildSettings());
        }
        installBlocker();

        setupSession(false, null);

        goButton.setOnClickListener(v -> loadFromBar());
        menuButton.setOnClickListener(v -> showMenu());

        shield.setOnClickListener(v -> toggleBlocker());
        shield.setOnLongClickListener(v -> {
            Toast.makeText(this, blockedCount + " element(s) bloque(s)", Toast.LENGTH_SHORT).show();
            return true;
        });

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadFromBar();
                return true;
            }
            return false;
        });

        updateShield();
        handleWidgetIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleWidgetIntent(intent);
    }

    /** Actions declenchees depuis un widget de l'ecran d'accueil. */
    private void handleWidgetIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra(SearchWidget.EXTRA);
        if (action == null) return;
        intent.removeExtra(SearchWidget.EXTRA);

        switch (action) {
            case "search":
                session.loadUri(homeUrl());
                urlBar.requestFocus();
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT);
                break;
            case "private":
                if (!privateMode) togglePrivate();
                break;
            case "bookmarks":
                showBookmarks();
                break;
            case "toggle":
                toggleBlocker();
                break;
        }
    }


    // =======================================================================
    //  Session (recreee lors du passage en navigation privee)
    // =======================================================================
    private void setupSession(boolean priv, String target) {
        privateMode = priv;

        int pi = profileIndex();
        if (pi > 0 && pi < PROFILES.length) desktopMode = "1".equals(PROFILES[pi][4]);

        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .usePrivateMode(priv)
                .userAgentMode(desktopMode
                        ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                        : GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(desktopMode
                        ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                        : GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build();

        GeckoSession old = session;

        session = new GeckoSession(settings);

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession s, String url,
                                         java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         Boolean hasUserGesture) {
                if (url != null) {
                    currentUrl = url;
                    urlBar.setText(url.startsWith("moz-extension://") ? "" : url);
                }
            }

            @Override
            public void onCanGoBack(GeckoSession s, boolean value) {
                canGoBack = value;
            }

            // Liens mailto:, tel:, geo:, intent:... : deleguer a l'application idoine.
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
                String uri = request.uri;
                if (uri == null) return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                if (uri.startsWith("http://") || uri.startsWith("https://")
                        || uri.startsWith("moz-extension://") || uri.startsWith("about:")
                        || uri.startsWith("data:") || uri.startsWith("blob:")
                        || uri.startsWith("resource://")) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }
                openScheme(uri);
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }

            // target="_blank" : sans onglets, on ouvre dans la session courante.
            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                if (uri != null && !uri.isEmpty()) {
                    runOnUiThread(() -> session.loadUri(uri));
                }
                return GeckoResult.fromValue(null);
            }
        });

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession s, String title) {
                currentTitle = title == null ? "" : title;
            }

            // Fichier que Gecko ne peut pas afficher : on l'enregistre.
            @Override
            public void onExternalResponse(GeckoSession s, WebResponse response) {
                Downloads.save(MainActivity.this, response);
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onProgressChange(GeckoSession s, int value) {
                progress.setProgress(value);
                progress.setVisibility(value > 0 && value < 100
                        ? android.view.View.VISIBLE : android.view.View.GONE);
            }

            @Override
            public void onPageStop(GeckoSession s, boolean success) {
                progress.setVisibility(android.view.View.GONE);
            }
        });

        session.setPromptDelegate(new Prompts(this, this::startFilePicker));

        permissions = new Permissions(this);
        session.setPermissionDelegate(permissions);

        restoreProfile();
        session.open(sRuntime);
        geckoView.setSession(session);
        session.loadUri(target != null ? target : homeUrl());


        if (old != null) {
            try { old.close(); } catch (Exception ignored) { }
        }
    }

    // =======================================================================
    //  Schemas non web et selection de fichier
    // =======================================================================
    private void openScheme(String uri) {
        try {
            Intent i;
            if (uri.startsWith("intent:")) {
                i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Aucune application pour ce lien", Toast.LENGTH_SHORT).show();
        }
    }

    private void startFilePicker(GeckoSession.PromptDelegate.FilePrompt prompt,
                                 GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result) {
        pendingFile = result;
        pendingFilePrompt = prompt;

        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");

        String[] mimes = prompt.mimeTypes;
        if (mimes != null && mimes.length > 0) {
            i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        }
        if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }

        try {
            startActivityForResult(Intent.createChooser(i, "Choisir un fichier"), REQ_FILE);
        } catch (Exception e) {
            result.complete(prompt.dismiss());
            pendingFile = null;
            pendingFilePrompt = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || pendingFile == null) return;

        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res = pendingFile;
        GeckoSession.PromptDelegate.FilePrompt prompt = pendingFilePrompt;
        pendingFile = null;
        pendingFilePrompt = null;

        if (resultCode != RESULT_OK || data == null) {
            res.complete(prompt.dismiss());
            return;
        }

        try {
            if (data.getClipData() != null) {
                android.content.ClipData clip = data.getClipData();
                Uri[] uris = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    uris[i] = clip.getItemAt(i).getUri();
                }
                res.complete(prompt.confirm(this, uris));
            } else if (data.getData() != null) {
                res.complete(prompt.confirm(this, data.getData()));
            } else {
                res.complete(prompt.dismiss());
            }
        } catch (Exception e) {
            res.complete(prompt.dismiss());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (permissions != null) permissions.onAndroidResult(requestCode, results);
    }

    // =======================================================================
    //  Menu
    // =======================================================================
    private void showMenu() {
        final String[] items = {
            "Accueil",
            "Recharger",
            "Page…",
            "Recherche…",
            "Confidentialite…",
            "Scripts et styles…",
            "Favoris…",
            blockerEnabled ? "Desactiver le blocage" : "Activer le blocage",
            "Synchronisation",
            "Aide et tutoriel"
        };

        new AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(items, (d, which) -> {
                switch (which) {
                    case 0: session.loadUri(homeUrl()); break;
                    case 1: session.reload(); break;
                    case 2: showPageMenu(); break;
                    case 3: showSearchMenu(); break;
                    case 4: showPrivacyMenu(); break;
                    case 5: showScriptsMenu(); break;
                    case 6: showBookmarksMenu(); break;
                    case 7: toggleBlocker(); break;
                    case 8: session.loadUri(extPage("sync.html")); break;
                    case 9: session.loadUri(extPage("help.html")); break;
                }
            })
            .show();
    }

    // -----------------------------------------------------------------------
    private void showPageMenu() {
        final String[] items = {
            "Analyser la page",
            "Code source",
            "Mode lecture",
            "Defilement infini ici",
            "Ne plus rediriger ce service",
            "Enregistrer en un fichier",
            "CSS de ce site",
            "Masquer un element (pointeur)",
            "Masquer ce site",
            "Partager",
            "Copier l'adresse",
            "Ouvrir dans une autre application",
            "Identite de l'appareil : " + profileName()
        };

        new AlertDialog.Builder(this)
            .setTitle("Page")
            .setItems(items, (d, which) -> {
                switch (which) {
                    case 0: inspectPage(); break;
                    case 1: viewSource(); break;
                    case 2: if (onWebPage()) sendCommand("reader"); break;
                    case 3: if (onWebPage()) sendCommand("autopagerHere"); break;
                    case 4: if (onWebPage()) sendCommand("noFrontend"); break;
                    case 5: if (onWebPage()) sendCommand("savePage"); break;
                    case 6: if (onWebPage()) sendCommand("styleThis"); break;
                    case 7: if (onWebPage()) sendCommand("pickElement"); break;
                    case 8: if (onWebPage()) sendCommand("hideSite"); break;
                    case 9: sharePage(); break;
                    case 10: copyUrl(); break;
                    case 11: openExternally(); break;
                    case 12: showProfilePicker(); break;
                }
            })
            .setNegativeButton("Retour", (d, w) -> showMenu())
            .show();
    }

    // -----------------------------------------------------------------------
    private void showSearchMenu() {
        final String[] items = {
            "Moteur : " + engineName(),
            "Filtres et categories",
            "Sources du metamoteur"
        };

        new AlertDialog.Builder(this)
            .setTitle("Recherche")
            .setItems(items, (d, which) -> {
                switch (which) {
                    case 0: showEnginePicker(); break;
                    case 1:
                    case 2: session.loadUri(extPage("search.html") + "?prefs=1"); break;
                }
            })
            .setNegativeButton("Retour", (d, w) -> showMenu())
            .show();
    }

    // -----------------------------------------------------------------------
    private void showScriptsMenu() {
        final String[] items = {
            "Mes scripts",
            "Mes styles CSS",
            "Commandes des scripts (" + gmCommands.length() + ")"
        };

        new AlertDialog.Builder(this)
            .setTitle("Scripts et styles")
            .setItems(items, (d, which) -> {
                if (which == 0) session.loadUri(extPage("scripts.html"));
                else if (which == 1) session.loadUri(extPage("styles.html"));
                else showScriptCommands();
            })
            .setNegativeButton("Retour", (d, w) -> showMenu())
            .show();
    }

    // -----------------------------------------------------------------------
    private void showBookmarksMenu() {
        final String[] items = { "Ouvrir un favori", "Ajouter cette page" };

        new AlertDialog.Builder(this)
            .setTitle("Favoris")
            .setItems(items, (d, which) -> {
                if (which == 0) showBookmarks();
                else addBookmark();
            })
            .setNegativeButton("Retour", (d, w) -> showMenu())
            .show();
    }

    // =======================================================================
    //  Analyse de page
    // =======================================================================
    /** Transmet une action a la page via l'extension. */
    private void sendCommand(String cmd) {
        if (blockerPort == null) {
            Toast.makeText(this, "Extension non connectee", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "cmd");
            msg.put("cmd", cmd);
            blockerPort.postMessage(msg);
        } catch (Exception e) {
            Toast.makeText(this, "Action indisponible", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean onWebPage() {
        if (currentUrl.isEmpty() || currentUrl.startsWith("moz-extension://")) {
            Toast.makeText(this, "Ouvrez d'abord une page web", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void inspectPage() {
        if (onWebPage()) sendCommand("inspect");
    }

    private void viewSource() {
        if (onWebPage()) session.loadUri("view-source:" + currentUrl);
    }

    private void showScriptCommands() {
        if (gmCommands.length() == 0) {
            Toast.makeText(this,
                    "Aucune commande enregistree sur cette page",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] labels = new String[gmCommands.length()];
        for (int i = 0; i < gmCommands.length(); i++) {
            JSONObject o = gmCommands.optJSONObject(i);
            labels[i] = o == null ? "?" : o.optString("label", "?");
        }
        new AlertDialog.Builder(this)
            .setTitle("Commandes des scripts")
            .setItems(labels, (d, which) -> {
                JSONObject o = gmCommands.optJSONObject(which);
                sendCommand("gm:" + (o == null ? which : o.optInt("index", which)));
            })
            .setNegativeButton("Fermer", null)
            .show();
    }

    // =======================================================================
    //  Confidentialite
    // =======================================================================
    private void togglePrivate() {
        boolean going = !privateMode;
        setupSession(going, going ? homeUrl() : homeUrl());
        Toast.makeText(this,
                going ? "Navigation privee : rien n'est conserve"
                      : "Navigation normale",
                Toast.LENGTH_SHORT).show();
    }

    private void showPrivacyMenu() {
        final String[] items = {
            privateMode ? "Quitter la navigation privee" : "Navigation privee",
            "Niveau de protection : " + Privacy.levelName(Privacy.level(this)),
            "DNS chiffre : " + (prefs.getBoolean("doh", false) ? "actif" : "inactif"),
            "Redirections vers les facades",
            TorSupport.isEnabled(this) ? "Tor : active" : "Tor : desactive",
            "Effacer toutes les donnees",
            "Diagnostic d'empreinte",
            "Ce que ce navigateur revele"
        };

        new AlertDialog.Builder(this)
            .setTitle("Confidentialite")
            .setItems(items, (d, which) -> {
                switch (which) {
                    case 0: togglePrivate(); break;
                    case 1: showLevelPicker(); break;
                    case 2: toggleDoh(); break;
                    case 3: session.loadUri(extPage("frontends.html")); break;
                    case 4: showTorMenu(); break;
                    case 5: clearAllData(); break;
                    case 6: if (onWebPage()) sendCommand("fingerprint"); break;
                    case 7: privacyInfo(); break;
                }
            })
            .setNegativeButton("Retour", (d, w) -> showMenu())
            .show();
    }

    private void showLevelPicker() {
        final String[] names = { "Standard", "Renforce", "Strict" };
        new AlertDialog.Builder(this)
            .setTitle("Niveau de protection")
            .setSingleChoiceItems(names, Privacy.level(this), (d, which) -> {
                d.dismiss();
                new AlertDialog.Builder(this)
                    .setTitle(names[which])
                    .setMessage(Privacy.sideEffects(which)
                            + "\n\nL'application va redemarrer.")
                    .setPositiveButton("Appliquer", (d2, w2) -> {
                        prefs.edit().putInt("privacyLevel", which).apply();
                        Privacy.writeConfig(this);
                        TorSupport.restart(this);
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
            })
            .setNegativeButton("Fermer", null)
            .show();
    }

    private void toggleDoh() {
        final boolean on = prefs.getBoolean("doh", false);
        if (on) {
            prefs.edit().putBoolean("doh", false).apply();
            Privacy.writeConfig(this);
            TorSupport.restart(this);
            return;
        }
        final String[] names = { "Quad9 (9.9.9.9)", "Cloudflare", "Mullvad", "dns0.eu" };
        final String[] uris = {
            "https://dns.quad9.net/dns-query",
            "https://mozilla.cloudflare-dns.com/dns-query",
            "https://dns.mullvad.net/dns-query",
            "https://zero.dns0.eu/"
        };
        new AlertDialog.Builder(this)
            .setTitle("Resolveur DNS chiffre")
            .setItems(names, (d, which) -> {
                prefs.edit().putBoolean("doh", true)
                     .putString("dohUri", uris[which]).apply();
                Privacy.writeConfig(this);
                TorSupport.restart(this);
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void clearAllData() {
        new AlertDialog.Builder(this)
            .setTitle("Effacer toutes les donnees ?")
            .setMessage("Cookies, cache, stockage local et sessions ouvertes. "
                      + "Vos favoris, scripts et filtres sont conserves.")
            .setPositiveButton("Effacer", (d, w) -> {
                try {
                    sRuntime.getStorageController()
                            .clearData(StorageController.ClearFlags.ALL);
                    Toast.makeText(this, "Donnees effacees", Toast.LENGTH_SHORT).show();
                    session.reload();
                } catch (Throwable t) {
                    Toast.makeText(this, "Effacement partiel : " + t.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void privacyInfo() {
        new AlertDialog.Builder(this)
            .setTitle("Ce que ce navigateur revele")
            .setMessage(
                "Le niveau renforce uniformise ce qu'un site peut lire de votre "
              + "appareil : agent, langue, fuseau, taille d'ecran, canvas, "
              + "precision des minuteurs. Les cookies et le cache sont cloisonnes "
              + "par site, donc un traqueur ne vous suit plus d'un site a l'autre.\n\n"
              + "Ce qui reste identifiant, et qu'aucun reglage ne corrige :\n\n"
              + "• Ce navigateur est rare. Un moteur Gecko avec cette combinaison "
              + "d'extensions forme deja une signature.\n\n"
              + "• Vos scripts utilisateur modifient les pages de facon observable "
              + "par le site.\n\n"
              + "• Vos listes de filtres personnalisees changent ce qui se charge, "
              + "ce qui est mesurable.\n\n"
              + "L'anonymat vient de la ressemblance : Tor Browser protege parce que "
              + "ses utilisateurs sont identiques entre eux. Un navigateur "
              + "personnalise vous distingue par construction. Ce mode vous protege "
              + "tres bien du pistage commercial ; il ne vous rend pas anonyme face "
              + "a un adversaire determine.")
            .setPositiveButton("Compris", null)
            .show();
    }

    // =======================================================================
    //  Tor
    // =======================================================================
    private void showTorMenu() {
        final boolean on = TorSupport.isEnabled(this);
        final String[] items = {
            on ? "Desactiver le routage Tor" : "Activer le routage Tor",
            "Verifier la connexion Tor",
            "Lancer Orbot",
            "A propos de ce mode"
        };

        new AlertDialog.Builder(this)
            .setTitle(on ? "Tor : active" : "Tor : desactive")
            .setItems(items, (d, which) -> {
                switch (which) {
                    case 0:
                        TorSupport.toggle(this);
                        break;
                    case 1:
                        session.loadUri("https://check.torproject.org/");
                        break;
                    case 2:
                        if (TorSupport.isOrbotInstalled(this)) TorSupport.startOrbot(this);
                        else TorSupport.offerInstall(this);
                        break;
                    case 3:
                        torInfo();
                        break;
                }
            })
            .setNegativeButton("Retour", (d, w) -> showPrivacyMenu())
            .show();
    }

    private void torInfo() {
        new AlertDialog.Builder(this)
            .setTitle("Ce que fait ce mode")
            .setMessage(
                "Le trafic est envoye au proxy SOCKS d'Orbot, avec resolution DNS "
              + "cote Tor et acces aux adresses .onion. WebRTC, la prelecture DNS "
              + "et le predicteur reseau sont coupes, car ils contourneraient le proxy.\n\n"
              + "Ce que ce mode ne fait PAS : il ne reproduit pas les protections "
              + "d'anonymat de Tor Browser. Votre empreinte de navigateur reste "
              + "distinctive, il n'y a ni cloisonnement par onglet ni normalisation "
              + "de la taille de fenetre, et vos scripts utilisateur comme vos "
              + "reglages vous rendent identifiable.\n\n"
              + "Pour un besoin reel d'anonymat, utilisez Tor Browser.")
            .setPositiveButton("Compris", null)
            .show();
    }

    // =======================================================================
    //  Moteurs de recherche
    // =======================================================================
    private String engineTemplate() {
        return prefs.getString("engine", "internal");
    }

    private String engineName() {
        String tpl = engineTemplate();
        for (String[] e : ENGINES) {
            if (e[1].equals(tpl)) return e[0];
        }
        return "Personnalise";
    }

    private void showEnginePicker() {
        final String[] names = new String[ENGINES.length];
        for (int i = 0; i < ENGINES.length; i++) names[i] = ENGINES[i][0];

        int checked = -1;
        String current = engineTemplate();
        for (int i = 0; i < ENGINES.length; i++) {
            if (ENGINES[i][1].equals(current)) { checked = i; break; }
        }

        new AlertDialog.Builder(this)
            .setTitle("Moteur de recherche")
            .setSingleChoiceItems(names, checked, (d, which) -> {
                String tpl = ENGINES[which][1];
                d.dismiss();
                if ("custom".equals(tpl)) {
                    askCustomEngine();
                } else {
                    prefs.edit().putString("engine", tpl).apply();
                    Toast.makeText(this, "Moteur : " + ENGINES[which][0],
                            Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Fermer", null)
            .show();
    }

    private void askCustomEngine() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://exemple.org/search?q=%s");
        String saved = prefs.getString("engineCustom", "");
        if (!saved.isEmpty()) input.setText(saved);

        new AlertDialog.Builder(this)
            .setTitle("Moteur personnalise")
            .setMessage("Utilisez %s a la place de la requete. Exemple pour une "
                      + "instance SearXNG : https://searx.be/search?q=%s")
            .setView(input)
            .setPositiveButton("Valider", (d, w) -> {
                String tpl = input.getText().toString().trim();
                if (!tpl.contains("%s")) {
                    Toast.makeText(this, "Le modele doit contenir %s", Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.edit().putString("engine", tpl).putString("engineCustom", tpl).apply();
                Toast.makeText(this, "Moteur personnalise enregistre", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private String searchUrl(String query) {
        String tpl = engineTemplate();
        String q = Uri.encode(query);
        if ("internal".equals(tpl)) {
            return searchBase != null ? searchBase + "?q=" + q : FALLBACK_HOME + "?q=" + q;
        }
        return tpl.replace("%s", q);
    }

    // =======================================================================
    //  Favoris
    // =======================================================================
    private JSONArray bookmarks() {
        try { return new JSONArray(prefs.getString("bookmarks", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private void addBookmark() {
        if (currentUrl.isEmpty() || currentUrl.startsWith("moz-extension://")) {
            Toast.makeText(this, "Rien a enregistrer ici", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONArray arr = bookmarks();
            for (int i = 0; i < arr.length(); i++) {
                if (currentUrl.equals(arr.getJSONObject(i).optString("url"))) {
                    Toast.makeText(this, "Deja dans les favoris", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            JSONObject o = new JSONObject();
            o.put("url", currentUrl);
            o.put("title", currentTitle.isEmpty() ? currentUrl : currentTitle);
            arr.put(o);
            prefs.edit().putString("bookmarks", arr.toString()).apply();
            Toast.makeText(this, "Ajoute aux favoris", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Echec de l'enregistrement", Toast.LENGTH_SHORT).show();
        }
    }

    private void showBookmarks() {
        final JSONArray arr = bookmarks();
        if (arr.length() == 0) {
            Toast.makeText(this, "Aucun favori", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] titles = new String[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            titles[i] = arr.optJSONObject(i).optString("title");
        }

        new AlertDialog.Builder(this)
            .setTitle("Favoris")
            .setItems(titles, (d, which) -> {
                String url = arr.optJSONObject(which).optString("url");
                if (!url.isEmpty()) session.loadUri(url);
            })
            .setNeutralButton("Supprimer…", (d, w) -> deleteBookmark(arr, titles))
            .setNegativeButton("Fermer", null)
            .show();
    }

    private void deleteBookmark(final JSONArray arr, String[] titles) {
        new AlertDialog.Builder(this)
            .setTitle("Supprimer un favori")
            .setItems(titles, (d, which) -> {
                JSONArray outArr = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    if (i != which) outArr.put(arr.optJSONObject(i));
                }
                prefs.edit().putString("bookmarks", outArr.toString()).apply();
                Toast.makeText(this, "Favori supprime", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    // =======================================================================
    //  Actions systeme
    // =======================================================================
    private void sharePage() {
        if (currentUrl.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, currentTitle);
        i.putExtra(Intent.EXTRA_TEXT, currentUrl);
        startActivity(Intent.createChooser(i, "Partager la page"));
    }

    private void copyUrl() {
        if (currentUrl.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("url", currentUrl));
            Toast.makeText(this, "Adresse copiee", Toast.LENGTH_SHORT).show();
        }
    }

    private void openExternally() {
        if (currentUrl.isEmpty() || currentUrl.startsWith("moz-extension://")) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(Intent.createChooser(i, "Ouvrir avec"));
        } catch (Exception e) {
            Toast.makeText(this, "Aucune application disponible", Toast.LENGTH_SHORT).show();
        }
    }

    // =======================================================================
    //  Identite de l'appareil
    // =======================================================================
    private int profileIndex() {
        return prefs.getInt("profile", 0);
    }

    private String profileName() {
        int i = profileIndex();
        if (i < 0 || i >= PROFILES.length) return "Automatique";
        if ("custom".equals(PROFILES[i][1])) return "Personnalise";
        return PROFILES[i][0];
    }

    private void showProfilePicker() {
        final String[] names = new String[PROFILES.length];
        for (int i = 0; i < PROFILES.length; i++) names[i] = PROFILES[i][0];

        new AlertDialog.Builder(this)
            .setTitle("Identite de l'appareil")
            .setSingleChoiceItems(names, profileIndex(), (d, which) -> {
                d.dismiss();
                if ("custom".equals(PROFILES[which][1])) askCustomProfile(which);
                else applyProfile(which, PROFILES[which][1], PROFILES[which][2],
                                  PROFILES[which][3], "1".equals(PROFILES[which][4]));
            })
            .setNeutralButton("A savoir", (d, w) -> profileInfo())
            .setNegativeButton("Retour", (d, w) -> showPageMenu())
            .show();
    }

    private void askCustomProfile(final int index) {
        final EditText input = new EditText(this);
        input.setHint("Mozilla/5.0 …");
        input.setText(prefs.getString("profileCustomUa", ""));

        new AlertDialog.Builder(this)
            .setTitle("Agent utilisateur personnalise")
            .setMessage("Collez la chaine complete. La mise en page passe en mode "
                      + "ordinateur.")
            .setView(input)
            .setPositiveButton("Appliquer", (d, w) -> {
                String ua = input.getText().toString().trim();
                if (ua.isEmpty()) return;
                prefs.edit().putString("profileCustomUa", ua).apply();
                applyProfile(index, ua, "", "", true);
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void applyProfile(int index, String ua, String platform,
                              String touch, boolean desktop) {
        prefs.edit().putInt("profile", index).apply();
        desktopMode = desktop;

        GeckoSessionSettings st = session.getSettings();
        try {
            // Chaine vide : Gecko reprend son agent normal.
            st.setUserAgentOverride(ua.isEmpty() ? null : ua);
        } catch (Throwable ignored) { }

        st.setUserAgentMode(desktop
                ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
        st.setViewportMode(desktop
                ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);

        // Les proprietes JavaScript associees sont alignees par l'extension,
        // sinon un site reperait la contradiction entre agent et plateforme.
        if (blockerPort != null) {
            try {
                JSONObject p = new JSONObject();
                p.put("ua", ua);
                p.put("platform", platform);
                p.put("touch", touch.isEmpty() ? -1 : Integer.parseInt(touch));
                p.put("desktop", desktop);

                JSONObject msg = new JSONObject();
                msg.put("type", "setProfile");
                msg.put("profile", p);
                blockerPort.postMessage(msg);
            } catch (Exception ignored) { }
        }

        Toast.makeText(this, "Identite : " + profileName(), Toast.LENGTH_SHORT).show();
        session.reload();
    }

    /** Reapplique le profil apres recreation de la session. */
    private void restoreProfile() {
        int i = profileIndex();
        if (i <= 0 || i >= PROFILES.length) return;
        String ua = "custom".equals(PROFILES[i][1])
                ? prefs.getString("profileCustomUa", "") : PROFILES[i][1];
        if (ua.isEmpty()) return;
        try {
            session.getSettings().setUserAgentOverride(ua);
        } catch (Throwable ignored) { }
    }

    private void profileInfo() {
        new AlertDialog.Builder(this)
            .setTitle("Portee de cette option")
            .setMessage(
                "L'agent utilisateur est remplace a la fois dans les en-tetes HTTP et "
              + "dans navigator.userAgent, et la mise en page bascule en mode "
              + "ordinateur ou mobile. L'extension aligne aussi la plateforme et les "
              + "points tactiles annonces, sinon un site repererait la contradiction.\n\n"
              + "Ce que cela ne fait pas : le moteur reste Gecko. Se declarer Chrome ou "
              + "Safari ne change ni les fonctions disponibles ni le rendu, et un site "
              + "qui teste les capacites plutot que l'agent verra la difference.\n\n"
              + "Attention aussi a la combinaison avec la protection anti-empreinte : "
              + "aux niveaux renforce et strict, celle-ci impose deja son propre agent. "
              + "Superposer un profil recree une incoherence, donc un signal distinctif. "
              + "Verifiez le resultat dans Confidentialite, Diagnostic d'empreinte.")
            .setPositiveButton("Compris", null)
            .show();
    }

    // =======================================================================
    //  Extension
    // =======================================================================
    private GeckoRuntimeSettings buildSettings() {
        String configPath = Privacy.writeConfig(this);

        ContentBlocking.Settings blocking = new ContentBlocking.Settings.Builder()
                .antiTracking(ContentBlocking.AntiTracking.AD
                        | ContentBlocking.AntiTracking.ANALYTIC
                        | ContentBlocking.AntiTracking.SOCIAL
                        | ContentBlocking.AntiTracking.CRYPTOMINING
                        | ContentBlocking.AntiTracking.FINGERPRINTING
                        | ContentBlocking.AntiTracking.CONTENT)
                .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                .build();

        GeckoRuntimeSettings.Builder b = new GeckoRuntimeSettings.Builder()
                .contentBlocking(blocking)
                .javaScriptEnabled(true);

        if (configPath != null) {
            try { b.configFilePath(configPath); }
            catch (Throwable ignored) { }
        }
        return b.build();
    }

    private void installBlocker() {
        sRuntime.getWebExtensionController()
                .ensureBuiltIn(EXT_URL, EXT_ID)
                .accept(
                    this::bindPort,
                    e -> runOnUiThread(() -> Toast.makeText(this,
                            "Extension indisponible : " + e.getMessage(),
                            Toast.LENGTH_LONG).show())
                );
    }

    private void bindPort(WebExtension ext) {
        if (ext == null) return;

        try {
            if (ext.metaData != null && ext.metaData.baseUrl != null) {
                searchBase = ext.metaData.baseUrl + "search.html";
                runOnUiThread(() -> {
                    if (session != null && currentUrl.isEmpty()) session.loadUri(homeUrl());
                });
            }
        } catch (Throwable ignored) { }

        ext.setMessageDelegate(new WebExtension.MessageDelegate() {
            @Override
            public void onConnect(WebExtension.Port port) {
                blockerPort = port;

                // Retablit l'etat choisi precedemment, y compris depuis un widget.
                if (!blockerEnabled) {
                    try {
                        JSONObject init = new JSONObject();
                        init.put("type", "setEnabled");
                        init.put("value", false);
                        port.postMessage(init);
                    } catch (Exception ignored) { }
                }

                port.setDelegate(new WebExtension.PortDelegate() {
                    @Override
                    public void onPortMessage(Object message, WebExtension.Port p) {
                        if (!(message instanceof JSONObject)) return;
                        JSONObject json = (JSONObject) message;
                        String kind = json.optString("type");

                        if ("download".equals(kind)) {
                            org.json.JSONArray arr = json.optJSONArray("urls");
                            if (arr != null && arr.length() > 0) {
                                final String[] urls = new String[arr.length()];
                                for (int i = 0; i < arr.length(); i++) urls[i] = arr.optString(i);
                                final String ref = json.optString("referer", currentUrl);
                                runOnUiThread(() -> Downloads.saveUrls(
                                        MainActivity.this, urls, ref));
                            }
                            return;
                        }

                        if ("getBookmarks".equals(kind)) {
                            try {
                                JSONObject reply = new JSONObject();
                                reply.put("type", "bookmarks");
                                reply.put("list", bookmarks());
                                p.postMessage(reply);
                            } catch (Exception ignored) { }
                            return;
                        }

                        if ("setBookmarks".equals(kind)) {
                            org.json.JSONArray list = json.optJSONArray("list");
                            if (list != null) {
                                prefs.edit().putString("bookmarks", list.toString()).apply();
                                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                        list.length() + " favori(s) restaure(s)",
                                        Toast.LENGTH_SHORT).show());
                            }
                            return;
                        }

                        if ("gmCommands".equals(kind)) {
                            org.json.JSONArray list = json.optJSONArray("list");
                            gmCommands = list != null ? list : new org.json.JSONArray();
                            return;
                        }

                        if ("extractAudio".equals(kind)) {
                            org.json.JSONArray arr = json.optJSONArray("urls");
                            if (arr != null && arr.length() > 0) {
                                final String[] urls = new String[arr.length()];
                                for (int i = 0; i < arr.length(); i++) urls[i] = arr.optString(i);
                                final String ref = json.optString("referer", currentUrl);
                                runOnUiThread(() -> AudioExtractor.extract(
                                        MainActivity.this, urls, ref));
                            }
                            return;
                        }

                        if ("downloadText".equals(kind)) {
                            final String name = json.optString("name", "liste.txt");
                            final String text = json.optString("text", "");
                            runOnUiThread(() -> Downloads.saveText(
                                    MainActivity.this, name, text));
                            return;
                        }

                        if (!"state".equals(kind)) return;
                        blockedCount = json.optInt("blocked", blockedCount);
                        blockerEnabled = json.optBoolean("enabled", blockerEnabled);
                        runOnUiThread(MainActivity.this::updateShield);
                    }

                    @Override
                    public void onDisconnect(WebExtension.Port p) {
                        if (p == blockerPort) blockerPort = null;
                    }
                });
            }
        }, "browser");
    }

    private String homeUrl() {
        return searchBase != null ? searchBase : FALLBACK_HOME;
    }

    private String extPage(String file) {
        if (searchBase != null) return searchBase.replace("search.html", file);
        Toast.makeText(this, "Extension non chargee", Toast.LENGTH_SHORT).show();
        return FALLBACK_HOME;
    }

    private void toggleBlocker() {
        blockerEnabled = !blockerEnabled;
        prefs.edit().putBoolean("blockerEnabled", blockerEnabled).apply();
        lastWidgetPush = 0;
        updateShield();
        if (blockerPort != null) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "setEnabled");
                msg.put("value", blockerEnabled);
                blockerPort.postMessage(msg);
            } catch (Exception ignored) { }
        }
        Toast.makeText(this, blockerEnabled ? "Blocage active" : "Blocage desactive",
                Toast.LENGTH_SHORT).show();
        session.reload();
    }

    private long lastWidgetPush = 0;

    private void pushWidgets() {
        long now = System.currentTimeMillis();
        if (now - lastWidgetPush < 4000) return;   // evite les rafraichissements en rafale
        lastWidgetPush = now;
        try { StatsWidget.publish(this, blockedCount, blockerEnabled); }
        catch (Throwable ignored) { }
    }

    private void updateShield() {
        pushWidgets();
        if (!blockerEnabled) {
            shield.setText("OFF");
            shield.setTextColor(0xFF9E9E9E);
        } else {
            shield.setText(blockedCount > 999 ? "999+" : String.valueOf(blockedCount));
            shield.setTextColor(0xFF4CAF50);
        }
    }

    // =======================================================================
    //  Navigation
    // =======================================================================
    private void loadFromBar() {
        String input = urlBar.getText().toString().trim();
        if (input.isEmpty()) return;

        String url;
        if (input.startsWith("http://") || input.startsWith("https://")
                || input.startsWith("moz-extension://")) {
            url = input;
        } else if (input.contains(".") && !input.contains(" ")) {
            url = "https://" + input;
        } else {
            url = searchUrl(input);
        }

        session.loadUri(url);
        hideKeyboard();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        urlBar.clearFocus();
    }

    @Override
    public void onBackPressed() {
        if (canGoBack) session.goBack();
        else super.onBackPressed();
    }
}
