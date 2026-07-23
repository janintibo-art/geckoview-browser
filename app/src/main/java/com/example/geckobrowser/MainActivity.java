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

    /** Un onglet : sa session et ce qu'on affiche a son sujet. */
    private static class Tab {
        GeckoSession session;
        String url = "";
        String title = "";
        boolean priv;
    }

    private final java.util.List<Tab> tabs = new java.util.ArrayList<>();
    private int active = -1;
    private TextView tabButton;

    private SharedPreferences prefs;
    private org.json.JSONArray gmCommands = new org.json.JSONArray();
    private Permissions permissions;
    private android.widget.ProgressBar progress;
    private android.view.View splash;
    private boolean homeLoaded = false;

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
        splash = findViewById(R.id.splash);
        tabButton = findViewById(R.id.tab_button);

        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(this, buildSettings());
        }
        installBlocker();

        setupSession(false, null);
        restoreTabs();

        goButton.setOnClickListener(v -> loadFromBar());
        menuButton.setOnClickListener(v -> showMenu());

        tabButton.setOnClickListener(v -> showTabs());
        tabButton.setOnLongClickListener(v -> {
            setupSession(false, null);
            selectTab(tabs.size() - 1);
            return true;
        });

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
            case "openUrl":
                String dest = intent.getStringExtra("url");
                if (dest != null && !dest.isEmpty()) {
                    setupSession(false, dest);
                    selectTab(tabs.size() - 1);
                }
                break;
        }
    }


    // =======================================================================
    //  Session (recreee lors du passage en navigation privee)
    // =======================================================================
    /** Cree un onglet, l'ajoute a la liste et l'affiche. */
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

        final Tab tab = new Tab();
        tab.priv = priv;
        session = new GeckoSession(settings);
        tab.session = session;

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession s, String url,
                                         java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         Boolean hasUserGesture) {
                if (url == null) return;
                tab.url = url;
                // Un onglet d'arriere-plan ne doit pas ecraser la barre d'adresse.
                if (s != session) return;
                currentUrl = url;
                urlBar.setText(url.startsWith("moz-extension://") ? "" : url);
            }

            @Override
            public void onCanGoBack(GeckoSession s, boolean value) {
                if (s == session) canGoBack = value;
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

            // target="_blank" : un onglet est ouvert en arriere-plan.
            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                if (uri != null && !uri.isEmpty()) {
                    runOnUiThread(() -> {
                        int previous = active;
                        setupSession(privateMode, uri);
                        selectTab(previous);
                        Toast.makeText(MainActivity.this,
                                "Ouvert dans un nouvel onglet", Toast.LENGTH_SHORT).show();
                    });
                }
                return GeckoResult.fromValue(null);
            }
        });

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession s, String title) {
                tab.title = title == null ? "" : title;
                if (s == session) currentTitle = tab.title;
            }

            // Fichier que Gecko ne peut pas afficher : on l'enregistre.
            @Override
            public void onExternalResponse(GeckoSession s, WebResponse response) {
                Downloads.save(MainActivity.this, response);
            }

            /**
             * Premier rendu effectif du contenu. C'est le bon moment pour
             * retirer l'ecran de demarrage : la fin du chargement reseau
             * survient trop tot, avant que quoi que ce soit ne soit peint.
             */
            @Override
            public void onFirstContentfulPaint(GeckoSession s) {
                if (s == session) hideSplash();
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onProgressChange(GeckoSession s, int value) {
                if (s != session) return;
                progress.setProgress(value);
                progress.setVisibility(value > 0 && value < 100
                        ? android.view.View.VISIBLE : android.view.View.GONE);
            }

            @Override
            public void onPageStop(GeckoSession s, boolean success) {
                if (s != session) return;
                progress.setVisibility(android.view.View.GONE);
                // Repli : si aucun rendu n'a eu lieu, on ne laisse pas
                // l'ecran de demarrage indefiniment.
                splash.postDelayed(MainActivity.this::hideSplash, 400);
            }
        });

        session.setPromptDelegate(new Prompts(this, this::startFilePicker));

        permissions = new Permissions(this);
        session.setPermissionDelegate(permissions);

        restoreProfile();
        session.open(sRuntime);

        // Gecko peint en blanc tant que rien n'est rendu : on impose le fond
        // sombre du navigateur, sinon un eclair blanc traverse chaque chargement.
        try {
            session.getCompositorController().setClearColor(0xFF0B0D10);
        } catch (Throwable ignored) { }
        geckoView.setSession(session);

        if (target != null) {
            session.loadUri(target);
        } else if (searchBase != null) {
            session.loadUri(homeUrl());
        } else {
            // L'extension n'est pas encore prete : sans cette attente, le premier
            // lancement afficherait le moteur de repli au lieu du notre.
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                if (!homeLoaded) {
                    homeLoaded = true;
                    session.loadUri(homeUrl());
                    hideSplash();
                }
            }, 5000);
        }


        tabs.add(tab);
        active = tabs.size() - 1;
        updateTabButton();
    }

    // =======================================================================
    //  Onglets
    // =======================================================================
    private void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        active = index;
        Tab t = tabs.get(index);
        session = t.session;
        privateMode = t.priv;
        currentUrl = t.url;
        currentTitle = t.title;

        geckoView.setSession(session);
        urlBar.setText(currentUrl.startsWith("moz-extension://") ? "" : currentUrl);
        updateTabButton();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab t = tabs.get(index);

        // Le dernier onglet n'est pas ferme : on le ramene a l'accueil.
        if (tabs.size() == 1) {
            t.url = "";
            t.title = "";
            session.loadUri(homeUrl());
            return;
        }

        try { t.session.close(); } catch (Exception ignored) { }
        tabs.remove(index);
        selectTab(Math.min(index, tabs.size() - 1));
        Toast.makeText(this, tabs.size() + " onglet(s)", Toast.LENGTH_SHORT).show();
    }

    private void updateTabButton() {
        if (tabButton == null) return;
        tabButton.setText(String.valueOf(tabs.size()));
        tabButton.setTextColor(privateMode ? 0xFF8AB4F8 : 0xFFE8EAEE);
    }

    private String tabLabel(Tab t) {
        if (t.title != null && !t.title.isEmpty()) return t.title;
        if (t.url != null && !t.url.isEmpty()) {
            if (t.url.startsWith("moz-extension://")) return "Accueil";
            try {
                String h = Uri.parse(t.url).getHost();
                if (h != null) return h.replaceFirst("^www\\.", "");
            } catch (Exception ignored) { }
            return t.url;
        }
        return "Nouvel onglet";
    }

    private void showTabs() {
        Menus m = new Menus(this, tabs.size() + " onglet(s)");
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            final Tab t = tabs.get(i);
            String mark = (i == active ? "\u25CF" : (t.priv ? "\u25D1" : "\u25CB"));
            String host = t.url.isEmpty() ? "vide" : t.url;
            if (host.length() > 46) host = host.substring(0, 46) + "…";
            m.add(mark, tabLabel(t), host, () -> selectTab(index));
        }
        m.add("\u002B", "Nouvel onglet", () -> {
            setupSession(false, null);
            selectTab(tabs.size() - 1);
        });
        m.add("\u25D1", "Nouvel onglet prive", () -> {
            setupSession(true, null);
            selectTab(tabs.size() - 1);
        });
        m.add("\u2327", "Fermer l'onglet courant", tabLabel(tabs.get(active)),
              () -> closeTab(active));
        if (tabs.size() > 1) {
            m.add("\u2327", "Fermer tous les autres", this::closeOthers);
        }
        m.back(this::showMenu).show();
    }

    private void closeOthers() {
        Tab keep = tabs.get(active);
        for (Tab t : tabs) {
            if (t != keep) {
                try { t.session.close(); } catch (Exception ignored) { }
            }
        }
        tabs.clear();
        tabs.add(keep);
        selectTab(0);
    }

    // -----------------------------------------------------------------------
    //  Restauration de session
    // -----------------------------------------------------------------------
    private void saveTabs() {
        try {
            JSONArray arr = new JSONArray();
            for (Tab t : tabs) {
                // Les onglets prives ne laissent aucune trace, par definition.
                if (t.priv || t.url.isEmpty() || t.url.startsWith("moz-extension://")) continue;
                JSONObject o = new JSONObject();
                o.put("url", t.url);
                o.put("title", t.title);
                arr.put(o);
            }
            prefs.edit().putString("session", arr.toString())
                 .putInt("sessionActive", active).apply();
        } catch (Exception ignored) { }
    }

    /** Rouvre les onglets du dernier lancement, l'accueil restant le premier. */
    private void restoreTabs() {
        if (!prefs.getBoolean("restoreSession", true)) return;
        try {
            JSONArray arr = new JSONArray(prefs.getString("session", "[]"));
            int limit = Math.min(arr.length(), 12);
            for (int i = 0; i < limit; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("url", "");
                if (u.isEmpty()) continue;
                setupSession(false, u);
            }
            if (tabs.size() > 1) selectTab(0);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveTabs();
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
        new Menus(this, "GeckoBrowser")
            .add("\u2302", "Accueil", () -> session.loadUri(homeUrl()))
            .add("\u21BB", "Recharger", () -> session.reload())
            .sub("\u25A5", "Onglets", tabs.size() + " ouvert(s)", this::showTabs)
            .sub("\u25A4", "Page", pageHost(), this::showPageMenu)
            .sub("\u2315", "Recherche", engineName(), this::showSearchMenu)
            .sub("\u26E8", "Confidentialite",
                 Privacy.levelName(Privacy.level(this))
                   + (TorSupport.isEnabled(this) ? " \u00B7 Tor" : "")
                   + (privateMode ? " \u00B7 prive" : ""),
                 this::showPrivacyMenu)
            .sub("\u2699", "Scripts et styles", null, this::showScriptsMenu)
            .sub("\u2605", "Favoris", bookmarks().length() + " enregistre(s)",
                 this::showBookmarksMenu)
            .add("\u26D4", blockerEnabled ? "Desactiver le blocage" : "Activer le blocage",
                 blockerEnabled ? blockedCount + " elements bloques" : "blocage inactif",
                 this::toggleBlocker)
            .sub("\u2630", "File de lecture", null,
                 () -> session.loadUri(extPage("queue.html")))
            .sub("\u23F1", "Surveillances", null,
                 () -> session.loadUri(extPage("watch.html")))
            .sub("\u21C6", "Archives de pages", null,
                 () -> session.loadUri(extPage("versions.html")))
            .add("\u21C4", "Synchronisation", () -> session.loadUri(extPage("sync.html")))
            .add("\u24D8", "Aide et tutoriel", () -> session.loadUri(extPage("help.html")))
            .show();
    }

    private String pageHost() {
        if (currentUrl.isEmpty() || currentUrl.startsWith("moz-extension://")) return null;
        try {
            String h = android.net.Uri.parse(currentUrl).getHost();
            return h == null ? null : h.replaceFirst("^www\\.", "");
        } catch (Exception e) { return null; }
    }

    // -----------------------------------------------------------------------
    private void showPageMenu() {
        new Menus(this, "Page")
            .add("\u2315", "Analyser la page", this::inspectPage)
            .add("\u2039", "Code source", this::viewSource)
            .add("\u26A0", "Qui parle a qui",
                 () -> { if (onWebPage()) sendCommand("thirdParty"); })
            .add("\u2630", "Mode lecture", () -> { if (onWebPage()) sendCommand("reader"); })
            .add("\u2913", "Enregistrer en un fichier",
                 () -> { if (onWebPage()) sendCommand("savePage"); })
            .add("\u221E", "Defilement infini ici",
                 () -> { if (onWebPage()) sendCommand("autopagerHere"); })
            .add("\u21B6", "Revenir au site d'origine", this::backToOriginal)
            .add("\u21BA", "Ne plus rediriger ce service",
                 () -> { if (onWebPage()) sendCommand("noFrontend"); })
            .add("\u270E", "CSS de ce site", () -> { if (onWebPage()) sendCommand("styleThis"); })
            .add("\u2611", "Lire plus tard",
                 () -> { if (onWebPage()) sendCommand("readLater"); })
            .add("\u2913", "Archiver cette version",
                 () -> { if (onWebPage()) sendCommand("archive"); })
            .add("\u21C6", "Comparer avec l'archive",
                 () -> { if (onWebPage()) sendCommand("compare"); })
            .add("\u23F1", "Surveiller un element",
                 () -> { if (onWebPage()) sendCommand("watch"); })
            .add("\u25CE", "Masquer un element",
                 () -> { if (onWebPage()) sendCommand("pickElement"); })
            .add("\u2298", "Masquer ce site", () -> { if (onWebPage()) sendCommand("hideSite"); })
            .add("\u21AA", "Partager", this::sharePage)
            .add("\u29C9", "Copier l'adresse", this::copyUrl)
            .add("\u2197", "Ouvrir ailleurs", this::openExternally)
            .sub("\u25A3", "Identite de l'appareil", profileName(), this::showProfilePicker)
            .back(this::showMenu)
            .show();
    }

    // -----------------------------------------------------------------------
    private void showSearchMenu() {
        new Menus(this, "Recherche")
            .sub("\u2315", "Moteur", engineName(), this::showEnginePicker)
            .add("\u2611", "Filtres et categories",
                 () -> session.loadUri(extPage("search.html") + "?prefs=1"))
            .add("\u229E", "Sources du metamoteur",
                 () -> session.loadUri(extPage("search.html") + "?prefs=1"))
            .back(this::showMenu)
            .show();
    }

    // -----------------------------------------------------------------------
    private void showScriptsMenu() {
        new Menus(this, "Scripts et styles")
            .add("\u2328", "Mes scripts", () -> session.loadUri(extPage("scripts.html")))
            .add("\u270E", "Mes styles CSS", () -> session.loadUri(extPage("styles.html")))
            .add("\u2318", "Commandes des scripts",
                 gmCommands.length() + " disponible(s)", this::showScriptCommands)
            .back(this::showMenu)
            .show();
    }

    // -----------------------------------------------------------------------
    private void showBookmarksMenu() {
        new Menus(this, "Favoris")
            .add("\u2605", "Ouvrir un favori", bookmarks().length() + " enregistre(s)",
                 this::showBookmarks)
            .add("\u2606", "Ajouter cette page", this::addBookmark)
            .add("\u2699", "Organiser", "classer, deplacer, supprimer",
                 this::organizeBookmarks)
            .add("\u29C9", "Copier les adresses",
                 "une par ligne, pret a coller", this::copyAllBookmarks)
            .add("\u21AA", "Partager les adresses", this::shareAllBookmarks)
            .add("\u2913", "Exporter dans Telechargements",
                 "dossier GeckoBrowser", () -> exportBookmarks(false))
            .back(this::showMenu)
            .show();
    }

    // =======================================================================
    //  Actions transmises a la page
    // =======================================================================
    /** Transmet une action aux scripts de contenu via l'extension. */
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

    /** Vrai si une page web ordinaire est ouverte. */
    private boolean onWebPage() {
        if (currentUrl.isEmpty() || currentUrl.startsWith("moz-extension://")) {
            Toast.makeText(this, "Ouvrez d'abord une page web", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    /**
     * Recharge l'adresse d'origine d'une redirection. Passe par le port plutot
     * que par la page : une facade en echec n'affiche parfois rien du tout,
     * et aucun script de contenu n'y est joignable.
     */
    private void backToOriginal() {
        if (blockerPort == null) {
            Toast.makeText(this, "Extension non connectee", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "askOriginal");
            blockerPort.postMessage(msg);
        } catch (Exception ignored) { }
    }

    // =======================================================================
    //  Notification de surveillance
    // =======================================================================
    private static final String CHANNEL = "watches";

    /**
     * Previent d'un changement detecte. Un appui ouvre la page concernee.
     * Repli sur un message a l'ecran si les notifications sont refusees.
     */
    private void showChangeNotification(String id, String title, String text, String url) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) throw new Exception("service indisponible");

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        CHANNEL, "Surveillances",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Changements detectes sur les pages surveillees");
                nm.createNotificationChannel(ch);
            }

            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                       != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 4712);
                Toast.makeText(this, title + " — " + text, Toast.LENGTH_LONG).show();
                return;
            }

            Intent open = new Intent(this, MainActivity.class);
            open.setAction("watch." + id);
            open.putExtra(SearchWidget.EXTRA, "openUrl");
            open.putExtra("url", url);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, id.hashCode(), open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);

            android.app.Notification.Builder b =
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                            ? new android.app.Notification.Builder(this, CHANNEL)
                            : new android.app.Notification.Builder(this);

            b.setSmallIcon(android.R.drawable.ic_popup_reminder)
             .setContentTitle(title)
             .setContentText(text)
             .setStyle(new android.app.Notification.BigTextStyle().bigText(text))
             .setAutoCancel(true)
             .setContentIntent(pi);

            nm.notify(id.hashCode(), b.build());
        } catch (Throwable t) {
            Toast.makeText(this, title + " — " + text, Toast.LENGTH_LONG).show();
        }
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
        new AlertDialog.Builder(this, R.style.GeckoDialog)
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
        new Menus(this, "Confidentialite")
            .add("\u25D1", privateMode ? "Quitter la navigation privee" : "Navigation privee",
                 this::togglePrivate)
            .sub("\u26E8", "Niveau de protection",
                 Privacy.levelName(Privacy.level(this)), this::showLevelPicker)
            .sub("\u2318", "DNS chiffre",
                 prefs.getBoolean("doh", false) ? "actif" : "inactif", this::toggleDoh)
            .add("\u21BA", "Redirections vers les facades",
                 () -> session.loadUri(extPage("frontends.html")))
            .sub("\u2609", "Tor",
                 TorSupport.isEnabled(this) ? "active" : "desactive", this::showTorMenu)
            .add("\u21BA", "Restaurer les onglets au demarrage",
                 prefs.getBoolean("restoreSession", true) ? "actif" : "inactif", () -> {
                     boolean v = !prefs.getBoolean("restoreSession", true);
                     prefs.edit().putBoolean("restoreSession", v).apply();
                     Toast.makeText(this, v ? "Onglets restaures au demarrage"
                             : "Demarrage sur un onglet vierge",
                             Toast.LENGTH_SHORT).show();
                 })
            .add("\u2327", "Effacer toutes les donnees", this::clearAllData)
            .add("\u25CE", "Diagnostic d'empreinte",
                 () -> { if (onWebPage()) sendCommand("fingerprint"); })
            .add("\u24D8", "Ce que ce navigateur revele", this::privacyInfo)
            .back(this::showMenu)
            .show();
    }

    private void showLevelPicker() {
        final String[] names = { "Standard", "Renforce", "Strict" };
        new AlertDialog.Builder(this, R.style.GeckoDialog)
            .setTitle("Niveau de protection")
            .setSingleChoiceItems(names, Privacy.level(this), (d, which) -> {
                d.dismiss();
                new AlertDialog.Builder(this, R.style.GeckoDialog)
                    .setTitle(names[which])
                    .setMessage(Privacy.sideEffects(which)
                            + "\n\nL'application va redemarrer. Pour verifier "
                            + "l'effet reel, ouvrez ensuite un site ordinaire puis "
                            + "Confidentialite, Diagnostic d'empreinte : le fuseau "
                            + "horaire et la langue annonces changent selon le niveau.")
                    .setPositiveButton("Appliquer", (d2, w2) -> {
                        // commit() et non apply() : le redemarrage tue le
                        // processus, une ecriture differee serait perdue.
                        prefs.edit().putInt("privacyLevel", which).commit();
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
            prefs.edit().putBoolean("doh", false).commit();
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
        new AlertDialog.Builder(this, R.style.GeckoDialog)
            .setTitle("Resolveur DNS chiffre")
            .setItems(names, (d, which) -> {
                prefs.edit().putBoolean("doh", true)
                     .putString("dohUri", uris[which]).commit();
                Privacy.writeConfig(this);
                TorSupport.restart(this);
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void clearAllData() {
        new AlertDialog.Builder(this, R.style.GeckoDialog)
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
        new AlertDialog.Builder(this, R.style.GeckoDialog)
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
        new Menus(this, on ? "Tor : active" : "Tor : desactive")
            .add("\u2609", on ? "Desactiver le routage Tor" : "Activer le routage Tor",
                 () -> TorSupport.toggle(this))
            .add("\u2713", "Verifier la connexion",
                 () -> session.loadUri("https://check.torproject.org/"))
            .add("\u25B6", "Lancer Orbot", () -> {
                if (TorSupport.isOrbotInstalled(this)) TorSupport.startOrbot(this);
                else TorSupport.offerInstall(this);
            })
            .add("\u24D8", "A propos de ce mode", this::torInfo)
            .back(this::showPrivacyMenu)
            .show();
    }

    private void torInfo() {
        new AlertDialog.Builder(this, R.style.GeckoDialog)
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
    /** Transmet le moteur choisi a la page d'accueil, qui vit dans l'extension. */
    private void pushEngine() {
        if (blockerPort == null) return;
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "setEngine");
            msg.put("template", engineTemplate());
            blockerPort.postMessage(msg);
        } catch (Exception ignored) { }
    }

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

        new AlertDialog.Builder(this, R.style.GeckoDialog)
            .setTitle("Moteur de recherche")
            .setSingleChoiceItems(names, checked, (d, which) -> {
                String tpl = ENGINES[which][1];
                d.dismiss();
                if ("custom".equals(tpl)) {
                    askCustomEngine();
                } else {
                    prefs.edit().putString("engine", tpl).apply();
                    pushEngine();
                    Toast.makeText(this, "Moteur : " + ENGINES[which][0],
                            Toast.LENGTH_SHORT).show();
                    if (currentUrl.isEmpty() || currentUrl.startsWith("moz-extension://")) {
                        session.loadUri(homeUrl());
                    }
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

        new AlertDialog.Builder(this, R.style.GeckoDialog)
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
                pushEngine();
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

    private static final String CAT_DEFAULT = "Sans categorie";

    /** Categories existantes, par ordre alphabetique, la non-classee en dernier. */
    private java.util.List<String> bookmarkCats() {
        java.util.TreeSet<String> set = new java.util.TreeSet<>();
        JSONArray arr = bookmarks();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) set.add(catOf(o));
        }
        java.util.List<String> out = new java.util.ArrayList<>(set);
        if (out.remove(CAT_DEFAULT)) out.add(CAT_DEFAULT);
        return out;
    }

    private String catOf(JSONObject o) {
        String c = o.optString("cat", "").trim();
        return c.isEmpty() ? CAT_DEFAULT : c;
    }

    private int countInCat(String cat) {
        return inCat(cat).length();
    }

    /** Favoris d'une categorie, ou tous si cat vaut null. */
    private JSONArray inCat(String cat) {
        JSONArray arr = bookmarks();
        if (cat == null) return arr;
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && catOf(o).equals(cat)) out.put(o);
        }
        return out;
    }

    /** Choix d'une categorie existante, ou creation. */
    private void pickCategory(String title, final java.util.function.Consumer<String> then) {
        final java.util.List<String> cats = bookmarkCats();
        final String[] items = new String[cats.size() + 1];
        for (int i = 0; i < cats.size(); i++) items[i] = cats.get(i);
        items[cats.size()] = "Nouvelle categorie…";

        Menus.choice(this, title)
            .setItems(items, (d, which) -> {
                if (which < cats.size()) { then.accept(cats.get(which)); return; }
                final EditText input = new EditText(this);
                input.setHint("Nom de la categorie");
                Menus.choice(this, "Nouvelle categorie")
                    .setView(input)
                    .setPositiveButton("Valider", (d2, w2) -> {
                        String c = input.getText().toString().trim();
                        then.accept(c.isEmpty() ? CAT_DEFAULT : c);
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    // -----------------------------------------------------------------------
    //  Formats d'export
    // -----------------------------------------------------------------------
    /** Adresses seules, une par ligne : le plus simple a recoller ailleurs. */
    private String bookmarkUrls(String cat) {
        JSONArray arr = inCat(cat);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String u = o.optString("url", "");
            if (!u.isEmpty()) sb.append(u).append("\n");
        }
        return sb.toString();
    }

    /** Meme liste, regroupee par categorie, avec un en-tete par groupe. */
    private String bookmarkText() {
        StringBuilder sb = new StringBuilder();
        for (String cat : bookmarkCats()) {
            sb.append("# ").append(cat).append("\n").append(bookmarkUrls(cat)).append("\n");
        }
        return sb.toString();
    }

    /** Format Netscape : chaque categorie devient un dossier de favoris. */
    private String bookmarkHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
          .append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
          .append("<TITLE>Favoris GeckoBrowser</TITLE>\n")
          .append("<H1>Favoris GeckoBrowser</H1>\n<DL><p>\n");
        for (String cat : bookmarkCats()) {
            sb.append("    <DT><H3>").append(escapeHtml(cat)).append("</H3>\n    <DL><p>\n");
            JSONArray arr = inCat(cat);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                sb.append("        <DT><A HREF=\"")
                  .append(escapeHtml(o.optString("url", "")))
                  .append("\">").append(escapeHtml(o.optString("title", "")))
                  .append("</A>\n");
            }
            sb.append("    </DL><p>\n");
        }
        sb.append("</DL><p>\n");
        return sb.toString();
    }

    private static String escapeHtml(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Ecrit la liste dans Telechargements/GeckoBrowser, hors du stockage prive
     * de l'application, donc consultable depuis un gestionnaire de fichiers.
     */
    private void exportBookmarks(final boolean silent) {
        final String urls = bookmarkText();
        final String html = bookmarkHtml();
        if (urls.trim().isEmpty() && !silent) {
            Toast.makeText(this, "Aucun favori a exporter", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            String message;
            try {
                Downloads.saveTextTo(this, "GeckoBrowser", "favoris.txt", urls);
                String path = Downloads.saveTextTo(this, "GeckoBrowser",
                        "favoris.html", html);
                message = "Exporte : " + path;
            } catch (Exception e) {
                message = "Export impossible : " + e.getMessage();
            }
            if (silent) return;
            final String m = message;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }, "export-favoris").start();
    }

    // -----------------------------------------------------------------------
    //  Copie et partage groupes
    // -----------------------------------------------------------------------
    private void copyAllBookmarks() {
        chooseCatThen("Copier quelles adresses ?", this::copyCat);
    }

    private void shareAllBookmarks() {
        chooseCatThen("Partager quelles adresses ?", this::shareCat);
    }

    /** Propose « toutes » puis chaque categorie, ou agit directement s'il n'y en a qu'une. */
    private void chooseCatThen(String title, final java.util.function.Consumer<String> then) {
        if (bookmarks().length() == 0) {
            Toast.makeText(this, "Aucun favori", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<String> cats = bookmarkCats();
        if (cats.size() <= 1) { then.accept(null); return; }

        Menus m = new Menus(this, title);
        m.add("\u2630", "Toutes", bookmarks().length() + " adresse(s)",
              () -> then.accept(null));
        for (final String c : cats) {
            m.add("\u25B8", c, countInCat(c) + " adresse(s)", () -> then.accept(c));
        }
        m.back(this::showBookmarksMenu).show();
    }

    private void copyCat(String cat) {
        String urls = bookmarkUrls(cat);
        if (urls.trim().isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("favoris", urls));
        int n = urls.trim().split("\n").length;
        Toast.makeText(this, n + " adresse(s) copiee(s)"
                + (cat == null ? "" : " — " + cat), Toast.LENGTH_SHORT).show();
    }

    private void shareCat(String cat) {
        String urls = bookmarkUrls(cat);
        if (urls.trim().isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT,
                cat == null ? "Mes favoris" : "Favoris — " + cat);
        i.putExtra(Intent.EXTRA_TEXT, urls);
        startActivity(Intent.createChooser(i, "Partager les favoris"));
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
        } catch (Exception ignored) { }

        final String url = currentUrl;
        final String title = currentTitle.isEmpty() ? currentUrl : currentTitle;

        pickCategory("Classer dans", cat -> saveBookmark(url, title, cat));
    }

    private void saveBookmark(String url, String title, String cat) {
        try {
            JSONArray arr = bookmarks();
            JSONObject o = new JSONObject();
            o.put("url", url);
            o.put("title", title);
            o.put("cat", cat);
            arr.put(o);
            prefs.edit().putString("bookmarks", arr.toString()).apply();
            exportBookmarks(true);
            Toast.makeText(this, "Ajoute dans " + cat, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Echec de l'enregistrement", Toast.LENGTH_SHORT).show();
        }
    }

    private void showBookmarks() {
        if (bookmarks().length() == 0) {
            Toast.makeText(this, "Aucun favori", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<String> cats = bookmarkCats();
        if (cats.size() <= 1) {
            listBookmarks(cats.isEmpty() ? null : cats.get(0));
            return;
        }

        Menus m = new Menus(this, "Categories");
        m.add("\u2630", "Toutes", bookmarks().length() + " favori(s)",
              () -> listBookmarks(null));
        for (final String cat : cats) {
            m.add("\u25B8", cat, countInCat(cat) + " favori(s)",
                  () -> listBookmarks(cat));
        }
        m.back(this::showBookmarksMenu).show();
    }

    private void listBookmarks(final String cat) {
        final JSONArray arr = inCat(cat);
        if (arr.length() == 0) {
            Toast.makeText(this, "Categorie vide", Toast.LENGTH_SHORT).show();
            return;
        }
        Menus m = new Menus(this, cat == null ? "Tous les favoris" : cat);
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String host = o.optString("url", "");
            try {
                String h = Uri.parse(host).getHost();
                if (h != null) host = h.replaceFirst("^www\\.", "");
            } catch (Exception ignored) { }
            m.add("\u2605", o.optString("title", ""), host,
                  () -> {
                      String u = o.optString("url", "");
                      if (!u.isEmpty()) session.loadUri(u);
                  });
        }
        m.back(this::showBookmarks).show();
    }

    /** Deplacer ou supprimer, categorie par categorie. */
    private void organizeBookmarks() {
        final JSONArray arr = bookmarks();
        if (arr.length() == 0) {
            Toast.makeText(this, "Aucun favori", Toast.LENGTH_SHORT).show();
            return;
        }
        Menus m = new Menus(this, "Organiser");
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final int index = i;
            m.add("\u2699", o.optString("title", ""), catOf(o),
                  () -> bookmarkActions(index, o));
        }
        m.back(this::showBookmarksMenu).show();
    }

    private void bookmarkActions(final int index, final JSONObject o) {
        new Menus(this, o.optString("title", ""))
            .add("\u25B8", "Changer de categorie", catOf(o),
                 () -> pickCategory("Deplacer vers", cat -> moveBookmark(index, cat)))
            .add("\u29C9", "Copier l'adresse", () -> {
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("url", o.optString("url", "")));
                    Toast.makeText(this, "Adresse copiee", Toast.LENGTH_SHORT).show();
                }
            })
            .add("\u2327", "Supprimer", () -> removeBookmark(index))
            .back(this::organizeBookmarks)
            .show();
    }

    private void moveBookmark(int index, String cat) {
        try {
            JSONArray arr = bookmarks();
            JSONObject o = arr.optJSONObject(index);
            if (o == null) return;
            o.put("cat", cat);
            prefs.edit().putString("bookmarks", arr.toString()).apply();
            exportBookmarks(true);
            Toast.makeText(this, "Deplace dans " + cat, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    private void removeBookmark(int index) {
        JSONArray arr = bookmarks();
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            if (i != index) out.put(arr.optJSONObject(i));
        }
        prefs.edit().putString("bookmarks", out.toString()).apply();
        exportBookmarks(true);
        Toast.makeText(this, "Favori supprime", Toast.LENGTH_SHORT).show();
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

        new AlertDialog.Builder(this, R.style.GeckoDialog)
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

        new AlertDialog.Builder(this, R.style.GeckoDialog)
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
        new AlertDialog.Builder(this, R.style.GeckoDialog)
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
                    if (session != null && !homeLoaded && currentUrl.isEmpty()) {
                        homeLoaded = true;
                        session.loadUri(homeUrl());
                    }
                });
            }
        } catch (Throwable ignored) { }

        ext.setMessageDelegate(new WebExtension.MessageDelegate() {
            @Override
            public void onConnect(WebExtension.Port port) {
                blockerPort = port;

                pushEngine();

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

                        if ("notify".equals(kind)) {
                            final String nTitle = json.optString("title", "Changement");
                            final String nText = json.optString("text", "");
                            final String nUrl = json.optString("url", "");
                            final String nId = json.optString("id", "w");
                            runOnUiThread(() -> showChangeNotification(nId, nTitle, nText, nUrl));
                            return;
                        }

                        if ("navigate".equals(kind)) {
                            final String dest = json.optString("url", "");
                            final String note = json.optString("notice", "");
                            runOnUiThread(() -> {
                                if (!note.isEmpty()) {
                                    Toast.makeText(MainActivity.this, note,
                                            Toast.LENGTH_LONG).show();
                                }
                                if (!dest.isEmpty()) session.loadUri(dest);
                            });
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
                                exportBookmarks(true);
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

    private void hideSplash() {
        if (splash == null || splash.getVisibility() != android.view.View.VISIBLE) return;
        splash.animate().alpha(0f).setDuration(220)
              .withEndAction(() -> splash.setVisibility(android.view.View.GONE))
              .start();
    }

    /**
     * Accueil : la page de marque n'a de sens qu'avec le metamoteur integre.
     * Avec un autre moteur, on ouvre directement son propre accueil.
     */
    private String homeUrl() {
        String tpl = engineTemplate();
        if (!"internal".equals(tpl)) {
            try {
                java.net.URL u = new java.net.URL(tpl.replace("%s", "x"));
                return u.getProtocol() + "://" + u.getHost() + "/";
            } catch (Exception ignored) { }
        }
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
        if (canGoBack) {
            session.goBack();
        } else if (tabs.size() > 1) {
            // Fermer l'onglet plutot que quitter : c'est l'attente courante.
            closeTab(active);
        } else {
            super.onBackPressed();
        }
    }
}
