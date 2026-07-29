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
        /** Adresse de repli si l'etat Gecko ne peut pas etre restaure. */
        String pending;
        /** Etat Gecko serialise : historique, defilement, zoom et formulaires. */
        String state;
        /** Etat a restaurer lors de la premiere selection de l'onglet. */
        String pendingState;
        /** Langue detectee par Gecko, pour la traduction de page. */
        String langTag;

        // TAB_WORKSPACE_V1 — organisation, apercu et economie de memoire.
        String id = java.util.UUID.randomUUID().toString();
        String group = "";
        boolean pinned = false;
        boolean sleeping = false;
        long lastUsed = System.currentTimeMillis();

        // WEB_APPS_V1 — onglet ouvert comme application web autonome.
        boolean webAppMode = false;
        String webAppId = "";
        String webAppName = "";
        String webAppScope = "";
        String webAppTheme = "";
        String webAppDisplay = "standalone";
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
    private android.os.Handler sessionSaveHandler;
    private final Runnable sessionSaveRunnable = this::saveTabs;

    // MEDIA_HUB_V1 — lecture native, notification, plein ecran et PiP.
    private MediaHub mediaHub;
    private boolean mediaFullscreen = false;

    // PASSWORD_VAULT_V1 — coffre chiffre et remplissage GeckoView.
    private PasswordVault passwordVault;

    // WEB_APPS_V1 — manifestes, catalogue et raccourcis Android.
    private WebAppManager webApps;

    // SPLIT_SCREEN_V1 — deux onglets visibles et redimensionnables.
    private SplitScreenManager splitScreen;

    // ENCRYPTED_SYNC_V1 — paquet chiffre partage via le selecteur Android.
    private EncryptedSyncManager encryptedSync;

    // EXTENSION_MANAGER_V1 — installation, permissions et actions WebExtension.
    private ExtensionManager extensionManager;

    private static final int REQ_FILE = 8123;
    private static final int REQ_EXTENSION = 8124;
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

        prefs = getSharedPreferences("geckobrowser", MODE_PRIVATE);
        ThemeManager.applyWindow(this);
        setContentView(R.layout.activity_main);

        blockerEnabled = prefs.getBoolean("blockerEnabled", true);

        geckoView = findViewById(R.id.geckoview);
        urlBar = findViewById(R.id.url_bar);
        shield = findViewById(R.id.shield);
        ImageButton goButton = findViewById(R.id.go_button);
        ImageButton menuButton = findViewById(R.id.menu_button);
        progress = findViewById(R.id.progress);
        splash = findViewById(R.id.splash);
        tabButton = findViewById(R.id.tab_button);
        initFindBar();
        ThemeManager.apply(this);
        sessionSaveHandler = new android.os.Handler(getMainLooper());
        mediaHub = new MediaHub(this);

        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(this, buildSettings());
        }
        extensionManager = new ExtensionManager(
                this, sRuntime, EXT_ID,
                url -> {
                    setupSession(false, url);
                    selectTab(tabs.size() - 1);
                },
                this::pickExtensionPackage);
        passwordVault = PasswordVault.get(this);
        sRuntime.setAutocompleteStorageDelegate(passwordVault);
        try {
            sRuntime.getSettings().setLoginAutofillEnabled(
                    passwordVault.isAutofillEnabled());
        } catch (Throwable ignored) { }
        webApps = new WebAppManager(this);
        splitScreen = new SplitScreenManager(this, geckoView,
                new SplitScreenManager.Host() {
                    @Override
                    public void onPaneFocused(GeckoSession target) {
                        focusSplitSession(target);
                    }

                    @Override
                    public String titleFor(GeckoSession target) {
                        return splitTitleFor(target);
                    }
                });
        encryptedSync = new EncryptedSyncManager(this, this::flushAndSaveTabs);
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

        // Appui : tableau de bord detaille. Appui long : interrupteur rapide.
        shield.setOnClickListener(v -> showPrivacyCockpit());
        shield.setOnLongClickListener(v -> {
            toggleBlocker();
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
        handleIncomingLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleWidgetIntent(intent);
        handleIncomingLink(intent);
    }

    /**
     * Liens ouverts depuis une autre application, ou depuis un raccourci
     * epingle sur l'ecran d'accueil. L'application declarait les accepter
     * sans rien en faire.
     */
    private void handleIncomingLink(Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return;

        WebAppManager.App launchedApp = WebAppManager.fromIntent(intent);
        if (launchedApp != null) {
            WebAppManager.consumeIntent(intent);
            openWebApp(launchedApp, true);
            return;
        }

        android.net.Uri data = intent.getData();
        if (data == null) return;

        String url = data.toString();
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;

        intent.setData(null);   // eviter de rouvrir au prochain retour

        if (currentUrl.isEmpty() || currentUrl.startsWith("moz-extension://")) {
            session.loadUri(url);
        } else {
            setupSession(privateMode, url);
            selectTab(tabs.size() - 1);
        }
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
        setupSession(priv, target, false, null);
    }

    /**
     * @param lazy vrai pour la restauration paresseuse : l'onglet est cree
     *             mais la session n'est ni ouverte ni chargee avant sa
     *             premiere selection. Ouvrir et charger une dizaine de pages
     *             simultanement au demarrage rendait le lancement poussif.
     */
    private void setupSession(boolean priv, String target, boolean lazy) {
        setupSession(priv, target, lazy, null);
    }

    /** Cree une session, eventuellement avec un etat Gecko a restaurer. */
    private void setupSession(boolean priv, String target, boolean lazy, String restoredState) {
        privateMode = priv;
        if (!lazy) DownloadCenter.setPrivateBrowsing(priv);

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
        tab.state = restoredState;
        tab.lastUsed = System.currentTimeMillis();
        session = new GeckoSession(settings);
        tab.session = session;
        PrivacyCockpit.attach(tab.session);
        mediaHub.attach(tab.session);
        if (extensionManager != null) extensionManager.attachSession(tab.session);

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession s, String url,
                                         java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         Boolean hasUserGesture) {
                if (url == null) return;
                tab.url = url;
                webApps.onLocation(s, url);
                boolean leftWebApp = tab.webAppMode
                        && (url.startsWith("http://") || url.startsWith("https://"))
                        && !WebAppManager.inScope(url, tab.webAppScope);
                if (leftWebApp) {
                    tab.webAppMode = false;
                    tab.webAppId = "";
                }
                scheduleSessionSave();
                // Un onglet d'arriere-plan ne doit pas ecraser la barre d'adresse.
                if (s != session) return;
                currentUrl = url;
                urlBar.setText(url.startsWith("moz-extension://") ? "" : url);
                if (leftWebApp) {
                    applyWebAppChrome(tab);
                    Toast.makeText(MainActivity.this,
                            "Lien ouvert hors de l'application web",
                            Toast.LENGTH_SHORT).show();
                }
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
                scheduleSessionSave();
                if (s == session) currentTitle = tab.title;
            }

            @Override
            public void onWebAppManifest(GeckoSession s, JSONObject manifest) {
                webApps.onManifest(s, manifest, tab.url, tab.title);
            }

            // Fichier que Gecko ne peut pas afficher : on l'enregistre.
            @Override
            public void onExternalResponse(GeckoSession s, WebResponse response) {
                if (extensionManager != null
                        && extensionManager.handleExternalResponse(response)) return;
                Downloads.save(MainActivity.this, response);
            }

            /**
             * Premier rendu effectif du contenu. C'est le bon moment pour
             * retirer l'ecran de demarrage : la fin du chargement reseau
             * survient trop tot, avant que quoi que ce soit ne soit peint.
             */
            @Override
            public void onFirstContentfulPaint(GeckoSession s) {
                // about:blank declenche aussi ce signal : ceder maintenant
                // decouvrirait la page vierge, blanche par defaut.
                if (s == session && isRealPage(tab.url)) hideSplash();
            }

            @Override
            public void onFullScreen(GeckoSession s, boolean fullScreen) {
                if (s == session) setMediaFullscreen(fullScreen);
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession s, String url) {
                PrivacyCockpit.onPageStart(s, url);
            }

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
                if (isRealPage(tab.url)) {
                    splash.postDelayed(MainActivity.this::hideSplash, 300);
                }
                if (success && !tab.priv && s == session) {
                    geckoView.postDelayed(() -> captureTabPreview(tab), 380);
                }
            }

            @Override
            public void onSecurityChange(GeckoSession s,
                    GeckoSession.ProgressDelegate.SecurityInformation info) {
                PrivacyCockpit.onSecurityChange(s, info);
            }

            @Override
            public void onSessionStateChange(GeckoSession s,
                    GeckoSession.SessionState sessionState) {
                if (tab.priv || sessionState == null) return;
                try {
                    String encoded = sessionState.toString();
                    if (encoded != null && !encoded.isEmpty()) {
                        tab.state = encoded;
                        scheduleSessionSave();
                    }
                } catch (Throwable ignored) { }
            }
        });

        session.setPromptDelegate(new VaultPrompts(
                this, this::startFilePicker, passwordVault,
                this::isPrivateSession));
        session.setSelectionActionDelegate(new SmartSelectionDelegate(
                this,
                this::searchUrl,
                () -> sendCommand("translateSel"),
                () -> currentTitle,
                () -> currentUrl));

        // Traduction de page : Gecko detecte la langue du document, on la
        // retient pour proposer « traduire depuis X » sans rien deviner.
        try {
            session.setTranslationsSessionDelegate(
                    new org.mozilla.geckoview.TranslationsController
                            .SessionTranslation.Delegate() {
                @Override
                public void onTranslationStateChange(GeckoSession s,
                        org.mozilla.geckoview.TranslationsController
                                .SessionTranslation.TranslationState state) {
                    try {
                        if (state != null && state.detectedLanguages != null
                                && state.detectedLanguages.docLangTag != null) {
                            tab.langTag = state.detectedLanguages.docLangTag;
                        }
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }

        permissions = new Permissions(this);
        session.setPermissionDelegate(permissions);

        restoreProfile();

        if (lazy) {
            // Rien n'est ouvert ni charge : selectTab() s'en chargera.
            tab.pending = target;
            tab.pendingState = restoredState;
            tab.url = target == null ? "" : target;
        } else {
            session.open(sRuntime);
            if (splitScreen != null && splitScreen.isActive()) {
                splitScreen.selectSession(session);
            } else {
                attachPrimarySession(session);
            }

            // Le compositeur n'existe qu'une fois la session rattachee a la vue :
            // fixer la couleur avant n'avait aucun effet.
            geckoView.setBackgroundColor(ThemeManager.browserBackground(this));
            try {
                session.getCompositorController().setClearColor(ThemeManager.browserBackground(this));
            } catch (Throwable ignored) { }

            if (target != null) {
                session.loadUri(target);
            } else if (searchBase != null) {
                session.loadUri(homeUrl());
            } else {
                // L'extension n'est pas encore prete : sans cette attente, le premier
                // lancement afficherait le moteur de repli au lieu du notre.
                new android.os.Handler(getMainLooper()).postDelayed(() -> {
                    if (!homeLoaded && tab.session != null && tab.session.isOpen()) {
                        homeLoaded = true;
                        tab.session.loadUri(homeUrl());
                        if (tab.session == session) hideSplash();
                    }
                }, 5000);
            }
        }


        tabs.add(tab);
        if (!lazy) {
            active = tabs.size() - 1;
            applyTabActivity();
        }
        updateTabButton();
    }

    // =======================================================================
    //  Onglets
    // =======================================================================

    /**
     * Marque un seul onglet comme actif aux yeux de Gecko.
     *
     * Sans cela, les pages en arriere-plan se croient toujours visibles
     * (document.visibilityState) : les commandes du menu, diffusees par le
     * stockage de l'extension, s'executaient alors dans tous les onglets a
     * la fois. C'est aussi ce qui permet a Gecko de reduire la priorite des
     * onglets non affiches (processeur, batterie).
     */
    private void attachPrimarySession(GeckoSession target) {
        if (target == null || geckoView.getSession() == target) return;
        try {
            if (geckoView.getSession() != null) geckoView.releaseSession();
        } catch (Throwable ignored) { }
        geckoView.setSession(target);
    }

    private void applyTabActivity() {
        for (int i = 0; i < tabs.size(); i++) {
            GeckoSession s = tabs.get(i).session;
            if (s == null || !s.isOpen()) continue;
            try {
                boolean visible = i == active
                        || (splitScreen != null && splitScreen.isVisible(s));
                s.setActive(visible);
                s.setFocused(i == active);
                if (extensionManager != null) extensionManager.setTabActive(s, i == active);
            } catch (Throwable ignored) { }
        }
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        hideFindBar();
        long now = System.currentTimeMillis();
        if (active >= 0 && active < tabs.size() && active != index) {
            tabs.get(active).lastUsed = now;
        }
        active = index;
        Tab t = tabs.get(index);
        if (t.sleeping) {
            if ((t.pendingState == null || t.pendingState.isEmpty())
                    && t.state != null && !t.state.isEmpty()) {
                t.pendingState = t.state;
            }
            if (t.pending == null || t.pending.isEmpty()) t.pending = t.url;
            t.sleeping = false;
        }
        t.lastUsed = now;
        session = t.session;
        privateMode = t.priv;
        DownloadCenter.setPrivateBrowsing(t.priv);
        currentUrl = t.url;
        currentTitle = t.title;

        // Restauration paresseuse : la session d'un onglet jamais consulte
        // n'est ouverte qu'ici, a sa premiere selection.
        boolean firstOpen = !session.isOpen();
        if (firstOpen) session.open(sRuntime);

        if (splitScreen != null && splitScreen.isActive()) {
            splitScreen.selectSession(session);
        } else {
            attachPrimarySession(session);
        }
        applyTabActivity();

        if (firstOpen) {
            try {
                session.getCompositorController().setClearColor(ThemeManager.browserBackground(this));
            } catch (Throwable ignored) { }
        }
        boolean restored = false;
        if (t.pendingState != null && !t.pendingState.isEmpty()) {
            String encoded = t.pendingState;
            t.pendingState = null;
            try {
                GeckoSession.SessionState state = GeckoSession.SessionState.fromString(encoded);
                if (state != null) {
                    session.restoreState(state);
                    restored = true;
                }
            } catch (Throwable ignored) { }
        }
        if (restored) {
            t.pending = null;
        } else if (t.pending != null) {
            String p = t.pending;
            t.pending = null;
            session.loadUri(p);
        }

        urlBar.setText(currentUrl.startsWith("moz-extension://") ? "" : currentUrl);
        applyWebAppChrome(t);
        if (splitScreen != null && splitScreen.isActive()) {
            if (webApps != null) webApps.hideStandaloneBar();
            setBrowserChromeVisible(true);
            splitScreen.refreshLabels();
        }
        updateTabButton();
        scheduleSessionSave();
        autoSleepInactiveTabs();
    }

    /** Retient ce qui vient d'etre ferme, pour pouvoir le rouvrir. */
    private void toTrash(String kind, String title, String url) {
        if (url == null || url.isEmpty() || url.startsWith("moz-extension://")) return;
        try {
            JSONArray arr = new JSONArray(prefs.getString("trash", "[]"));
            JSONObject o = new JSONObject();
            o.put("kind", kind);
            o.put("title", title == null || title.isEmpty() ? url : title);
            o.put("url", url);
            o.put("at", System.currentTimeMillis());

            JSONArray out = new JSONArray();
            out.put(o);
            for (int i = 0; i < arr.length() && out.length() < 25; i++) {
                JSONObject old = arr.optJSONObject(i);
                if (old != null && !url.equals(old.optString("url"))) out.put(old);
            }
            prefs.edit().putString("trash", out.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void showTrash() {
        JSONArray arr;
        try { arr = new JSONArray(prefs.getString("trash", "[]")); }
        catch (Exception e) { arr = new JSONArray(); }

        if (arr.length() == 0) {
            Toast.makeText(this, "La corbeille est vide", Toast.LENGTH_SHORT).show();
            return;
        }

        Menus m = new Menus(this, "Corbeille");
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final String kind = o.optString("kind", "onglet");
            final String url = o.optString("url", "");
            String host = url;
            try {
                String h = Uri.parse(url).getHost();
                if (h != null) host = h.replaceFirst("^www\\.", "");
            } catch (Exception ignored) { }

            m.add("onglet".equals(kind) ? "\u25A5" : "\u2605",
                  o.optString("title", url),
                  ("onglet".equals(kind) ? "onglet ferme" : "favori supprime") +
                      " \u00B7 " + host,
                  () -> restoreFromTrash(kind, o));
        }
        m.add("\u2327", "Vider la corbeille", () -> {
            prefs.edit().remove("trash").apply();
            Toast.makeText(this, "Corbeille videe", Toast.LENGTH_SHORT).show();
        });
        m.back(this::showMenu).show();
    }

    private void restoreFromTrash(String kind, JSONObject o) {
        String url = o.optString("url", "");
        if (url.isEmpty()) return;

        if ("favori".equals(kind)) {
            saveBookmark(url, o.optString("title", url),
                         o.optString("cat", CAT_DEFAULT));
        } else {
            setupSession(false, url);
            selectTab(tabs.size() - 1);
        }

        // L'element restaure quitte la corbeille
        try {
            JSONArray arr = new JSONArray(prefs.getString("trash", "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x != null && !url.equals(x.optString("url"))) out.put(x);
            }
            prefs.edit().putString("trash", out.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab t = tabs.get(index);
        if (splitScreen != null && splitScreen.contains(t.session)) {
            splitScreen.exit();
        }
        if (!t.priv) toTrash("onglet", t.title, t.url);

        // Le dernier onglet n'est pas ferme : on le ramene a l'accueil.
        if (tabs.size() == 1) {
            t.url = "";
            t.title = "";
            t.webAppMode = false;
            t.webAppId = "";
            t.webAppName = "";
            t.webAppScope = "";
            t.webAppTheme = "";
            t.webAppDisplay = "standalone";
            applyWebAppChrome(t);
            TabPreviewStore.delete(this, t.id);
            t.id = java.util.UUID.randomUUID().toString();
            t.group = "";
            t.pinned = false;
            t.sleeping = false;
            t.lastUsed = System.currentTimeMillis();
            session.loadUri(homeUrl());
            scheduleSessionSave();
            return;
        }

        TabPreviewStore.delete(this, t.id);
        try { t.session.close(); } catch (Exception ignored) { }
        tabs.remove(index);
        selectTab(Math.min(index, tabs.size() - 1));
        scheduleSessionSave();
        Toast.makeText(this, tabs.size() + " onglet(s)", Toast.LENGTH_SHORT).show();
    }

    private void updateTabButton() {
        if (tabButton == null) return;
        tabButton.setText(splitScreen != null && splitScreen.isActive()
                ? "2/" + tabs.size() : String.valueOf(tabs.size()));
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
        autoSleepInactiveTabs();
        if (active >= 0 && active < tabs.size()) captureTabPreview(tabs.get(active));

        Menus m = new Menus(this, tabs.size() + " onglet(s)");
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            final Tab t = tabs.get(i);
            String mark = i == active ? "\u25CF"
                    : (splitScreen != null && splitScreen.contains(t.session) ? "\u25C9"
                    : (t.pinned ? "\u2605" : (t.sleeping ? "\u25CC"
                    : (t.priv ? "\u25D1" : "\u25CB"))));
            m.add(mark, tabLabel(t), tabMeta(t), () -> showTabPreview(index));
        }
        m.add("\u002B", "Nouvel onglet", () -> {
            setupSession(false, null);
            selectTab(tabs.size() - 1);
        });
        m.add("\u25D1", "Nouvel onglet prive", () -> {
            setupSession(true, null);
            selectTab(tabs.size() - 1);
        });
        m.sub("\u25A6", "Groupes d'onglets", groupSummary(), this::showTabGroups);
        m.sub("\u23F8", "Mise en veille", sleepSettingName(), this::showSleepMenu);
        m.add("\u263E", "Mettre les onglets inactifs en veille",
              "les onglets epingles restent ouverts", this::sleepAllInactiveTabs);
        m.add("\u2327", "Fermer l'onglet courant", tabLabel(tabs.get(active)),
              () -> closeTab(active));
        if (tabs.size() > 1) {
            m.add("\u2327", "Fermer tous les autres",
                  "les onglets epingles sont conserves", this::closeOthers);
        }
        m.back(this::showMenu).show();
    }

    private void closeOthers() {
        if (splitScreen != null && splitScreen.isActive()) splitScreen.exit();
        Tab keep = tabs.get(active);
        java.util.List<Tab> kept = new java.util.ArrayList<>();
        for (Tab t : tabs) {
            if (t == keep || t.pinned) {
                kept.add(t);
            } else {
                TabPreviewStore.delete(this, t.id);
                try { t.session.close(); } catch (Exception ignored) { }
            }
        }
        tabs.clear();
        tabs.addAll(kept);
        selectTab(tabs.indexOf(keep));
        scheduleSessionSave();
    }



    // -----------------------------------------------------------------------
    //  Espace de travail des onglets : groupes, apercus, epinglage et veille.
    // -----------------------------------------------------------------------
    private int tabDp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String hostForTab(Tab t) {
        if (t == null || t.url == null || t.url.isEmpty()) return "vide";
        if (t.url.startsWith("moz-extension://")) return "accueil";
        try {
            String host = Uri.parse(t.url).getHost();
            if (host != null && !host.isEmpty()) return host.replaceFirst("^www\\.", "");
        } catch (Throwable ignored) { }
        String text = t.url;
        return text.length() > 42 ? text.substring(0, 42) + "…" : text;
    }

    private String groupName(Tab t) {
        return t == null || t.group == null || t.group.trim().isEmpty()
                ? "Sans groupe" : t.group.trim();
    }

    private String tabMeta(Tab t) {
        java.util.List<String> bits = new java.util.ArrayList<>();
        if (t.pinned) bits.add("epingle");
        if (t.sleeping) bits.add("en veille");
        if (t.priv) bits.add("prive");
        if (!"Sans groupe".equals(groupName(t))) bits.add(groupName(t));
        bits.add(hostForTab(t));
        bits.add(lastUsedText(t.lastUsed));
        return android.text.TextUtils.join(" · ", bits);
    }

    private String lastUsedText(long time) {
        long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60000L);
        if (minutes < 1) return "maintenant";
        if (minutes < 60) return "il y a " + minutes + " min";
        long hours = minutes / 60;
        if (hours < 24) return "il y a " + hours + " h";
        return "il y a " + (hours / 24) + " j";
    }

    private void captureTabPreview(Tab tab) {
        if (tab == null || tab.priv || tab.sleeping || tab.session == null
                || tab.session != session || !tab.session.isOpen()) return;
        try {
            geckoView.capturePixels().accept(
                bitmap -> {
                    if (bitmap != null) TabPreviewStore.save(this, tab.id, bitmap);
                },
                error -> { }
            );
        } catch (Throwable ignored) { }
    }

    private void showTabPreview(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab tab = tabs.get(index);
        if (index == active && !tab.priv && !tab.sleeping
                && tab.session != null && tab.session.isOpen()) {
            try {
                geckoView.capturePixels().accept(
                    bitmap -> runOnUiThread(() -> {
                        if (bitmap != null) TabPreviewStore.save(this, tab.id, bitmap);
                        showTabPreviewDialog(index);
                    }),
                    error -> runOnUiThread(() -> showTabPreviewDialog(index))
                );
                return;
            } catch (Throwable ignored) { }
        }
        showTabPreviewDialog(index);
    }

    private void showTabPreviewDialog(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab tab = tabs.get(index);

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = tabDp(14);
        box.setPadding(pad, pad, pad, tabDp(4));

        android.graphics.Bitmap bitmap = tab.priv ? null : TabPreviewStore.load(this, tab.id);
        if (bitmap != null) {
            android.widget.ImageView image = new android.widget.ImageView(this);
            image.setAdjustViewBounds(true);
            image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(bitmap);
            android.widget.LinearLayout.LayoutParams imageParams =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            tabDp(210));
            imageParams.bottomMargin = tabDp(12);
            box.addView(image, imageParams);
        } else {
            TextView empty = new TextView(this);
            empty.setText(tab.priv
                    ? "Aucun apercu n'est enregistre en navigation privee."
                    : (tab.sleeping ? "Onglet en veille · apercu indisponible"
                                    : "L'apercu sera cree apres le prochain affichage."));
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(tabDp(8), tabDp(28), tabDp(8), tabDp(28));
            box.addView(empty, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        TextView details = new TextView(this);
        details.setText(tabMeta(tab) + "\n" + (tab.url == null ? "" : tab.url));
        details.setTextIsSelectable(true);
        details.setTextSize(12f);
        box.addView(details);

        Menus.dialog(this)
            .setTitle(tabLabel(tab))
            .setView(box)
            .setPositiveButton(index == active ? "Afficher" : "Ouvrir",
                    (d, w) -> selectTab(index))
            .setNeutralButton("Actions", (d, w) -> showTabActions(index))
            .setNegativeButton("Retour", (d, w) -> showTabs())
            .show();
    }

    private void showTabActions(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab tab = tabs.get(index);
        Menus m = new Menus(this, tabLabel(tab));
        m.add("\u25B6", "Ouvrir", tabMeta(tab), () -> selectTab(index));
        m.add("\u25A3", "Apercu visuel", () -> showTabPreview(index));
        m.add("\u25A6", "Deplacer dans un groupe", groupName(tab),
              () -> moveTabToGroup(index));
        m.add(tab.pinned ? "\u2606" : "\u2605",
              tab.pinned ? "Desepingler" : "Epingler",
              tab.pinned ? "autoriser la mise en veille automatique"
                         : "garder cet onglet actif et le proteger des fermetures groupées",
              () -> togglePinned(index));
        if (tab.sleeping) {
            m.add("\u263C", "Reveiller", () -> selectTab(index));
        } else {
            m.add("\u263E", "Mettre en veille",
                  "libere le moteur tout en gardant l'historique", () -> sleepTab(index, false));
        }
        m.add("\u2327", "Fermer cet onglet", () -> closeTab(index));
        m.back(this::showTabs).show();
    }

    private void togglePinned(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab tab = tabs.get(index);
        tab.pinned = !tab.pinned;
        if (tab.pinned && tab.sleeping) selectTab(index);
        scheduleSessionSave();
        Toast.makeText(this, tab.pinned ? "Onglet epingle" : "Onglet desepingle",
                Toast.LENGTH_SHORT).show();
        showTabActions(index);
    }

    private java.util.List<String> knownGroups() {
        java.util.LinkedHashSet<String> groups = new java.util.LinkedHashSet<>();
        groups.add("Personnel");
        groups.add("Travail");
        groups.add("Lecture");
        groups.add("Projet");
        try {
            JSONArray saved = new JSONArray(prefs.getString("tabGroups", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                String value = saved.optString(i, "").trim();
                if (!value.isEmpty()) groups.add(value);
            }
        } catch (Throwable ignored) { }
        for (Tab tab : tabs) {
            String value = tab.group == null ? "" : tab.group.trim();
            if (!value.isEmpty()) groups.add(value);
        }
        return new java.util.ArrayList<>(groups);
    }

    private void rememberGroup(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) return;
        java.util.LinkedHashSet<String> groups = new java.util.LinkedHashSet<>(knownGroups());
        groups.add(clean);
        JSONArray out = new JSONArray();
        for (String group : groups) out.put(group);
        prefs.edit().putString("tabGroups", out.toString()).apply();
    }

    private void moveTabToGroup(int index) {
        if (index < 0 || index >= tabs.size()) return;
        java.util.List<String> groups = knownGroups();
        String[] labels = new String[groups.size() + 2];
        labels[0] = "Sans groupe";
        for (int i = 0; i < groups.size(); i++) labels[i + 1] = groups.get(i);
        labels[labels.length - 1] = "Nouveau groupe…";

        Menus.dialog(this)
            .setTitle("Deplacer l'onglet")
            .setItems(labels, (d, which) -> {
                if (which == 0) {
                    tabs.get(index).group = "";
                    scheduleSessionSave();
                    showTabActions(index);
                } else if (which == labels.length - 1) {
                    createGroupForTab(index);
                } else {
                    tabs.get(index).group = groups.get(which - 1);
                    rememberGroup(tabs.get(index).group);
                    scheduleSessionSave();
                    showTabActions(index);
                }
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void createGroupForTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Nom du groupe");
        Menus.dialog(this)
            .setTitle("Nouveau groupe")
            .setView(input)
            .setPositiveButton("Creer", (d, w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) return;
                tabs.get(index).group = name;
                rememberGroup(name);
                scheduleSessionSave();
                showTabActions(index);
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private int groupCount(String group) {
        int count = 0;
        for (Tab tab : tabs) {
            String value = tab.group == null ? "" : tab.group.trim();
            if (group == null || group.isEmpty()) {
                if (value.isEmpty()) count++;
            } else if (group.equals(value)) {
                count++;
            }
        }
        return count;
    }

    private String groupSummary() {
        java.util.LinkedHashSet<String> used = new java.util.LinkedHashSet<>();
        for (Tab tab : tabs) {
            String group = tab.group == null ? "" : tab.group.trim();
            if (!group.isEmpty()) used.add(group);
        }
        return used.isEmpty() ? "aucun groupe" : used.size() + " groupe(s) utilise(s)";
    }

    private void showTabGroups() {
        Menus m = new Menus(this, "Groupes d'onglets");
        int loose = groupCount("");
        if (loose > 0) {
            m.add("\u25CB", "Sans groupe", loose + " onglet(s)",
                  () -> showGroupTabs(""));
        }
        for (String group : knownGroups()) {
            int count = groupCount(group);
            if (count <= 0) continue;
            final String selected = group;
            m.add("\u25A6", group, count + " onglet(s)",
                  () -> showGroupTabs(selected));
        }
        m.add("\u002B", "Nouveau groupe pour l'onglet courant",
              () -> createGroupForTab(active));
        m.back(this::showTabs).show();
    }

    private void showGroupTabs(String group) {
        String title = group == null || group.isEmpty() ? "Sans groupe" : group;
        Menus m = new Menus(this, title);
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            String value = tab.group == null ? "" : tab.group.trim();
            boolean match = group == null || group.isEmpty() ? value.isEmpty() : group.equals(value);
            if (!match) continue;
            final int index = i;
            String mark = i == active ? "\u25CF"
                    : (tab.pinned ? "\u2605" : (tab.sleeping ? "\u25CC" : "\u25CB"));
            m.add(mark, tabLabel(tab), tabMeta(tab), () -> showTabPreview(index));
        }
        m.back(this::showTabGroups).show();
    }

    private boolean sleepTab(int index, boolean quiet) {
        if (index < 0 || index >= tabs.size()) return false;
        Tab tab = tabs.get(index);
        if (index == active) {
            if (!quiet) Toast.makeText(this,
                    "Ouvrez un autre onglet avant de mettre celui-ci en veille",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        if (tab.pinned || tab.priv || tab.sleeping) return false;
        // Un onglet affiche dans le second volet de l'ecran partage est
        // visible : fermer sa session viderait le volet.
        if (splitScreen != null && splitScreen.isVisible(tab.session)) {
            if (!quiet) Toast.makeText(this,
                    "Cet onglet est affiche en ecran partage",
                    Toast.LENGTH_SHORT).show();
            return false;
        }

        try { if (tab.session.isOpen()) tab.session.flushSessionState(); }
        catch (Throwable ignored) { }
        if (tab.state != null && !tab.state.isEmpty()) tab.pendingState = tab.state;
        tab.pending = tab.url;
        try { if (tab.session.isOpen()) tab.session.close(); }
        catch (Throwable ignored) { }
        tab.sleeping = true;
        scheduleSessionSave();
        if (!quiet) {
            Toast.makeText(this, "Onglet mis en veille", Toast.LENGTH_SHORT).show();
            showTabs();
        }
        return true;
    }

    private void sleepAllInactiveTabs() {
        int slept = 0;
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            if (i == active || tab.pinned || tab.priv || tab.sleeping) continue;
            if (sleepTab(i, true)) slept++;
        }
        Toast.makeText(this, slept + " onglet(s) mis en veille", Toast.LENGTH_SHORT).show();
        showTabs();
    }

    private int sleepMinutes() {
        return prefs.getInt("tabSleepMinutes", 30);
    }

    private String sleepSettingName() {
        int minutes = sleepMinutes();
        if (minutes <= 0) return "automatique desactivee";
        if (minutes < 60) return "apres " + minutes + " min";
        return "apres " + (minutes / 60) + " h";
    }

    private void autoSleepInactiveTabs() {
        int minutes = sleepMinutes();
        if (minutes <= 0 || tabs.size() < 2) return;
        long threshold = System.currentTimeMillis() - minutes * 60000L;
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            if (i == active || tab.pinned || tab.priv || tab.sleeping) continue;
            if (tab.lastUsed > 0 && tab.lastUsed < threshold) sleepTab(i, true);
        }
    }

    private void showSleepMenu() {
        Menus m = new Menus(this, "Mise en veille");
        m.add("\u263E", "Mettre les onglets inactifs en veille",
              "action immediate", this::sleepAllInactiveTabs);
        m.add("\u23F1", "Delai automatique", sleepSettingName(), this::showSleepPicker);
        m.add("\u24D8", "Fonctionnement",
              "l'historique est conserve, le moteur est ferme",
              () -> Menus.dialog(this)
                    .setTitle("Mise en veille des onglets")
                    .setMessage("Un onglet en veille libere sa session Gecko et reduit la memoire "
                            + "utilisee. Son adresse, son historique, son defilement, son zoom et "
                            + "ses formulaires restent enregistres. Il se reveille automatiquement "
                            + "lorsque vous l'ouvrez. Les onglets prives et epingles ne sont jamais "
                            + "mis en veille automatiquement.")
                    .setPositiveButton("Compris", null)
                    .show());
        m.back(this::showTabs).show();
    }

    private void showSleepPicker() {
        final int[] values = { 0, 5, 15, 30, 60, 120 };
        final String[] labels = {
            "Desactivee", "Apres 5 minutes", "Apres 15 minutes",
            "Apres 30 minutes", "Apres 1 heure", "Apres 2 heures"
        };
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == sleepMinutes()) checked = i;
        final int initial = checked;
        Menus.dialog(this)
            .setTitle("Veille automatique")
            .setSingleChoiceItems(labels, initial, (d, which) -> {
                prefs.edit().putInt("tabSleepMinutes", values[which]).apply();
                d.dismiss();
                autoSleepInactiveTabs();
                showSleepMenu();
            })
            .setNegativeButton("Annuler", null)
            .show();
    }


    // -----------------------------------------------------------------------
    //  Restauration complete de session
    // -----------------------------------------------------------------------
    private void scheduleSessionSave() {
        if (sessionSaveHandler == null) return;
        sessionSaveHandler.removeCallbacks(sessionSaveRunnable);
        sessionSaveHandler.postDelayed(sessionSaveRunnable, 700);
    }

    private void saveTabs() {
        try {
            JSONArray arr = new JSONArray();
            int savedActive = -1;
            Tab cur = (active >= 0 && active < tabs.size()) ? tabs.get(active) : null;
            for (Tab t : tabs) {
                // Les onglets prives ne laissent aucune trace, par definition.
                if (t.priv || t.url.isEmpty() || t.url.startsWith("moz-extension://")) continue;
                if (t == cur) savedActive = arr.length();
                JSONObject o = new JSONObject();
                o.put("url", t.url);
                o.put("title", t.title);
                o.put("id", t.id);
                if (t.group != null && !t.group.isEmpty()) o.put("group", t.group);
                o.put("pinned", t.pinned);
                o.put("sleeping", t.sleeping);
                o.put("lastUsed", t.lastUsed);
                if (t.webAppMode) {
                    o.put("webAppMode", true);
                    o.put("webAppId", t.webAppId);
                    o.put("webAppName", t.webAppName);
                    o.put("webAppScope", t.webAppScope);
                    o.put("webAppTheme", t.webAppTheme);
                    o.put("webAppDisplay", t.webAppDisplay);
                }
                if (t.state != null && !t.state.isEmpty()) o.put("state", t.state);
                arr.put(o);
            }

            if (SessionStore.write(this, arr, savedActive)) {
                // Supprime l'ancien format seulement apres une ecriture reussie.
                prefs.edit().remove("session").remove("sessionActive").apply();
            }

            java.util.Set<String> previewIds = new java.util.HashSet<>();
            for (Tab tab : tabs) if (!tab.priv) previewIds.add(tab.id);
            TabPreviewStore.cleanup(this, previewIds);
        } catch (Exception ignored) { }
    }

    /** Demande a Gecko un instantane frais, puis enregistre aussi un repli immediat. */
    private void flushAndSaveTabs() {
        for (Tab t : tabs) {
            if (t.priv || t.session == null || !t.session.isOpen()) continue;
            try { t.session.flushSessionState(); } catch (Throwable ignored) { }
        }
        saveTabs();
        if (sessionSaveHandler != null) {
            sessionSaveHandler.removeCallbacks(sessionSaveRunnable);
            sessionSaveHandler.postDelayed(sessionSaveRunnable, 350);
        }
    }

    /** Rouvre les onglets avec historique, defilement, zoom et formulaires. */
    private void restoreTabs() {
        if (!prefs.getBoolean("restoreSession", true)) return;
        try {
            SessionStore.Snapshot snapshot = SessionStore.read(this);
            JSONArray arr;
            int wanted;

            if (snapshot != null) {
                arr = snapshot.tabs;
                wanted = snapshot.active;
            } else {
                // Migration transparente depuis l'ancien format URL + titre.
                arr = new JSONArray(prefs.getString("session", "[]"));
                wanted = prefs.getInt("sessionActive", -1);
            }

            int limit = Math.min(arr.length(), 12);
            int target = -1;
            for (int i = 0; i < limit; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("url", "");
                if (u.isEmpty()) continue;
                String encoded = o.optString("state", "");
                setupSession(false, u, true, encoded);
                Tab restored = tabs.get(tabs.size() - 1);
                restored.title = o.optString("title", "");
                restored.id = o.optString("id", restored.id);
                restored.group = o.optString("group", "");
                restored.pinned = o.optBoolean("pinned", false);
                restored.sleeping = o.optBoolean("sleeping", false);
                restored.lastUsed = o.optLong("lastUsed", System.currentTimeMillis());
                restored.webAppMode = o.optBoolean("webAppMode", false);
                restored.webAppId = o.optString("webAppId", "");
                restored.webAppName = o.optString("webAppName", "");
                restored.webAppScope = o.optString("webAppScope", "");
                restored.webAppTheme = o.optString("webAppTheme", "");
                restored.webAppDisplay = o.optString("webAppDisplay", "standalone");
                if (restored.webAppMode
                        && !WebAppManager.inScope(u, restored.webAppScope)) {
                    restored.webAppMode = false;
                }
                if (i == wanted) target = tabs.size() - 1;
            }

            if (target != -1) selectTab(target);
            else if (tabs.size() > 1) selectTab(0);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onUserLeaveHint() {
        if (mediaHub != null) mediaHub.onUserLeaveHint();
        super.onUserLeaveHint();
    }

    @Override
    protected void onPause() {
        if (active >= 0 && active < tabs.size()) captureTabPreview(tabs.get(active));
        autoSleepInactiveTabs();
        flushAndSaveTabs();
        if (encryptedSync != null) encryptedSync.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        saveTabs();
        super.onStop();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip,
            android.content.res.Configuration configuration) {
        super.onPictureInPictureModeChanged(inPip, configuration);
        if (mediaHub != null) mediaHub.onPipModeChanged(inPip);
        if (inPip) {
            if (webApps != null) webApps.hideStandaloneBar();
            setBrowserChromeVisible(false);
        } else if (!mediaFullscreen) {
            applyWebAppChrome(currentTab());
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (splitScreen != null) splitScreen.onConfigurationChanged(configuration);
    }

    @Override
    protected void onDestroy() {
        if (extensionManager != null) extensionManager.release();
        if (passwordVault != null) passwordVault.onActivityDestroyed(this);
        if (encryptedSync != null) encryptedSync.release();
        if (splitScreen != null) splitScreen.release();
        if (webApps != null) webApps.release();
        if (mediaHub != null) mediaHub.release();
        super.onDestroy();
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

    private void pickExtensionPackage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-xpinstall");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/x-xpinstall", "application/zip",
                "application/octet-stream"
        });
        try {
            startActivityForResult(Intent.createChooser(intent,
                    "Choisir une extension XPI"), REQ_EXTENSION);
        } catch (Exception error) {
            Toast.makeText(this, "Aucun selecteur de fichier disponible",
                    Toast.LENGTH_LONG).show();
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
        if (requestCode == REQ_EXTENSION) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && extensionManager != null) {
                extensionManager.installFromContentUri(data.getData());
            }
            return;
        }
        if (encryptedSync != null
                && encryptedSync.onActivityResult(requestCode, resultCode, data)) {
            return;
        }
        if (passwordVault != null
                && passwordVault.onActivityResult(this, requestCode, resultCode, data)) {
            return;
        }
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
    //  Recherche dans la page
    //  S'appuie sur le moteur de Gecko (SessionFinder) : surlignage et
    //  compteur natifs, aucun script injecte, la page n'est pas modifiee.
    // =======================================================================
    private android.view.View findBar;
    private EditText findInput;
    private TextView findCount;

    private void initFindBar() {
        findBar = findViewById(R.id.find_bar);
        findInput = findViewById(R.id.find_input);
        findCount = findViewById(R.id.find_count);

        findInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                doFind(s.toString(), 0);
            }
        });
        findInput.setOnEditorActionListener((v, actionId, ev) -> {
            doFind(findInput.getText().toString(), 0);
            return true;
        });
        findViewById(R.id.find_prev).setOnClickListener(v ->
                doFind(findInput.getText().toString(), GeckoSession.FINDER_FIND_BACKWARDS));
        findViewById(R.id.find_next).setOnClickListener(v ->
                doFind(findInput.getText().toString(), 0));
        findViewById(R.id.find_close).setOnClickListener(v -> hideFindBar());
    }

    private void showFindBar() {
        findBar.setVisibility(android.view.View.VISIBLE);
        try {
            session.getFinder().setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL);
        } catch (Throwable ignored) { }
        findInput.setText("");
        findCount.setText("");
        findInput.requestFocus();
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideFindBar() {
        if (findBar == null || findBar.getVisibility() != android.view.View.VISIBLE) return;
        findBar.setVisibility(android.view.View.GONE);
        findCount.setText("");
        try { session.getFinder().clear(); } catch (Throwable ignored) { }
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(findInput.getWindowToken(), 0);
    }

    private void doFind(String text, int flags) {
        if (text == null || text.isEmpty()) {
            try { session.getFinder().clear(); } catch (Throwable ignored) { }
            findCount.setText("");
            return;
        }
        try {
            session.getFinder().find(text, flags).accept(r -> runOnUiThread(() -> {
                if (r == null || r.total <= 0) { findCount.setText("aucun"); return; }
                findCount.setText(r.current + "/" + r.total);
            }));
        } catch (Throwable ignored) { }
    }

    // =======================================================================
    //  Traduction
    //  Page entiere : moteur local de Gecko (modeles telecharges chez
    //  Mozilla, texte jamais envoye a un serveur). Selection : facade
    //  Lingva via l'extension (voir translate.js / background.js).
    // =======================================================================
    private static final String[][] TR_LANGS = {
        { "fr", "Francais" }, { "en", "Anglais" }, { "de", "Allemand" },
        { "es", "Espagnol" }, { "it", "Italien" }, { "pt", "Portugais" },
        { "ru", "Russe" }, { "ar", "Arabe" }, { "zh", "Chinois" },
        { "ja", "Japonais" }
    };

    private String trLang() { return prefs.getString("trLang", "fr"); }

    private String trLangName() {
        for (String[] l : TR_LANGS) if (l[0].equals(trLang())) return l[1];
        return trLang();
    }

    private void setTrLang(String tag) {
        prefs.edit().putString("trLang", tag).apply();
        // Les scripts de contenu (selection) lisent la meme langue cible
        if (blockerPort != null) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "setTrLang");
                msg.put("value", tag);
                blockerPort.postMessage(msg);
            } catch (Exception ignored) { }
        }
        showTranslateMenu();
    }

    private void showTranslateMenu() {
        Menus m = new Menus(this, "Traduire");
        m.add("\u6587", "Traduire cette page", "vers " + trLangName() + " \u00b7 local",
              () -> { if (onWebPage()) translatePage(trLang()); });
        m.add("\u270D", "Traduire la selection",
              "vers " + trLangName() + " \u00b7 via Lingva",
              () -> { if (onWebPage()) sendCommand("translateSel"); });
        m.add("\u21BA", "Revenir a la page d'origine", this::restoreOriginalPage);
        for (String[] l : TR_LANGS) {
            final String tag = l[0];
            m.add(tag.equals(trLang()) ? "\u25C9" : "\u25CB",
                  "Langue cible : " + l[1], () -> setTrLang(tag));
        }
        m.back(this::showPageMenu).show();
    }

    private void translatePage(String to) {
        Tab t = tabs.get(active);
        String detectedLang = t.langTag == null ? "" : t.langTag;
        int dash = detectedLang.indexOf('-');
        if (dash > 0) detectedLang = detectedLang.substring(0, dash);
        final String from = detectedLang;

        if (from.isEmpty()) {
            Toast.makeText(this, "Langue de la page non detectee : rechargez la "
                    + "page puis reessayez", Toast.LENGTH_LONG).show();
            return;
        }
        if (from.equals(to)) {
            Toast.makeText(this, "La page semble deja en " + trLangName(),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            session.getSessionTranslation().translate(from, to, null).accept(
                    v -> { },
                    e -> runOnUiThread(() -> Toast.makeText(this,
                            "Traduction impossible (" + from + " \u2192 " + to
                            + ") : modele indisponible ?",
                            Toast.LENGTH_LONG).show()));
            Toast.makeText(this, "Traduction " + from + " \u2192 " + to
                    + " (le modele peut se telecharger au premier usage)",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, "Traduction indisponible sur cette page",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreOriginalPage() {
        try {
            session.getSessionTranslation().restoreOriginalPage();
        } catch (Throwable e) {
            Toast.makeText(this, "Rien a restaurer ici", Toast.LENGTH_SHORT).show();
        }
    }


    // =======================================================================
    //  Multimedia, plein ecran et image dans l'image
    // =======================================================================
    String mediaTitleFor(GeckoSession target) {
        if (target == null) return currentTitle;
        for (Tab tab : tabs) {
            if (tab.session == target) {
                if (tab.title != null && !tab.title.isEmpty()) return tab.title;
                return tabLabel(tab);
            }
        }
        return currentTitle;
    }

    void openMediaSession(GeckoSession target) {
        if (target == null) return;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).session == target) {
                selectTab(i);
                return;
            }
        }
    }

    void onMediaFullscreen(GeckoSession target, boolean enabled) {
        if (target == session) setMediaFullscreen(enabled);
    }

    void prepareMediaPictureInPicture() {
        if (onWebPage()) sendCommand("mediaPip");
    }

    void requestMediaPictureInPicture(long width, long height) {
        if (android.os.Build.VERSION.SDK_INT < 26 || isInPictureInPictureMode()) return;
        if (splitScreen != null && splitScreen.isActive()) splitScreen.exit();
        try {
            long w = width > 0 ? width : 16;
            long h = height > 0 ? height : 9;
            double ratio = w / (double) h;
            if (!Double.isFinite(ratio) || ratio < 0.42 || ratio > 2.39) {
                w = 16;
                h = 9;
            }
            android.app.PictureInPictureParams.Builder builder =
                    new android.app.PictureInPictureParams.Builder()
                            .setAspectRatio(new android.util.Rational((int) Math.min(w, 10000),
                                                                     (int) Math.min(h, 10000)));
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                builder.setSeamlessResizeEnabled(true);
            }
            setBrowserChromeVisible(false);
            enterPictureInPictureMode(builder.build());
        } catch (Throwable e) {
            if (!mediaFullscreen) applyWebAppChrome(currentTab());
            Toast.makeText(this, "Image dans l'image indisponible",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void setMediaFullscreen(boolean enabled) {
        if (enabled && splitScreen != null && splitScreen.isActive()) splitScreen.exit();
        mediaFullscreen = enabled;
        if (enabled) {
            if (webApps != null) webApps.hideStandaloneBar();
            setBrowserChromeVisible(false);
        } else if (!isInPictureInPictureMode()) {
            applyWebAppChrome(currentTab());
        }
        android.view.View decor = getWindow().getDecorView();
        if (enabled) {
            decor.setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                  | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                  | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            decor.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void setBrowserChromeVisible(boolean visible) {
        android.view.View toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) toolbar.setVisibility(
                visible ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!visible && findBar != null) findBar.setVisibility(android.view.View.GONE);
        if (progress != null && !visible) progress.setVisibility(android.view.View.GONE);
    }

    private void exitMediaFullscreen() {
        if (onWebPage()) sendCommand("mediaExitFullscreen");
        setMediaFullscreen(false);
    }



    // =======================================================================
    //  Ecran partage
    // =======================================================================
    private String splitTitleFor(GeckoSession target) {
        if (target == null) return "";
        for (Tab tab : tabs) {
            if (tab.session == target) return tabLabel(tab);
        }
        return "";
    }

    private int splitIndexFor(GeckoSession target) {
        if (target == null) return -1;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).session == target) return i;
        }
        return -1;
    }

    private void focusSplitSession(GeckoSession target) {
        int index = splitIndexFor(target);
        if (index < 0) return;

        hideFindBar();
        active = index;
        Tab tab = tabs.get(index);
        session = tab.session;
        privateMode = tab.priv;
        currentUrl = tab.url == null ? "" : tab.url;
        currentTitle = tab.title == null ? "" : tab.title;

        urlBar.setText(currentUrl.startsWith("moz-extension://") ? "" : currentUrl);
        if (webApps != null) webApps.hideStandaloneBar();
        setBrowserChromeVisible(true);
        applyTabActivity();
        updateTabButton();
        if (splitScreen != null) splitScreen.refreshLabels();
        scheduleSessionSave();
    }

    private void showSplitScreenMenu() {
        if (splitScreen == null) return;
        Menus menu = new Menus(this, "Ecran partage");

        if (!splitScreen.isActive()) {
            menu.add("\u25EB", "Choisir le second onglet",
                    tabs.size() > 1 ? "afficher deux pages" : "aucun autre onglet",
                    () -> chooseSplitTab(false));
            menu.add("\u002B", "Nouveau volet",
                    "ouvrir un nouvel onglet a cote", this::createSplitTab);
            menu.add("\u24D8", "Fonctionnement",
                    "touchez un volet pour lui donner le focus",
                    () -> Toast.makeText(this,
                            "La barre d'adresse, la recherche et les commandes agissent "
                          + "sur le volet entoure. Faites glisser la separation pour "
                          + "redimensionner les deux pages.",
                            Toast.LENGTH_LONG).show());
        } else {
            menu.add("\u21C4", "Permuter les volets", () -> splitScreen.swap());
            menu.add("\u25A3", "Remplacer le volet actif",
                    "par un autre onglet", () -> chooseSplitTab(true));
            menu.sub("\u2194", "Disposition", splitScreen.orientationName(),
                    this::showSplitOrientationMenu);
            menu.sub("\u00BD", "Repartition", splitScreen.ratioName(),
                    this::showSplitRatioMenu);
            menu.add("\u2715", "Quitter l'ecran partage", () -> {
                splitScreen.exit();
                applyTabActivity();
                applyWebAppChrome(currentTab());
                updateTabButton();
            });
        }
        menu.back(this::showMenu).show();
    }

    private void chooseSplitTab(boolean replaceFocused) {
        Menus menu = new Menus(this,
                replaceFocused ? "Remplacer le volet" : "Second volet");
        boolean any = false;
        for (int i = 0; i < tabs.size(); i++) {
            if (!replaceFocused && i == active) continue;
            final int index = i;
            final Tab tab = tabs.get(i);
            any = true;
            String detail = tab.url == null || tab.url.isEmpty() ? "vide" : tab.url;
            if (detail.length() > 48) detail = detail.substring(0, 47) + "\u2026";
            menu.add(tab.priv ? "\u25D1" : "\u25CB", tabLabel(tab), detail, () -> {
                if (replaceFocused && splitScreen.isActive()) selectTab(index);
                else startSplitWith(index);
            });
        }

        if (!any) menu.add("\u002B", "Creer un nouvel onglet", this::createSplitTab);
        menu.back(this::showSplitScreenMenu).show();
    }

    private void createSplitTab() {
        if (splitScreen != null && splitScreen.isActive()) {
            setupSession(false, null);
            selectTab(tabs.size() - 1);
            return;
        }
        int first = active;
        setupSession(false, null);
        int second = tabs.size() - 1;
        selectTab(first);
        startSplitWith(second);
    }

    private void startSplitWith(int secondIndex) {
        if (splitScreen == null || splitScreen.isActive()) return;
        if (active < 0 || active >= tabs.size()
                || secondIndex < 0 || secondIndex >= tabs.size()
                || secondIndex == active) return;
        if (mediaFullscreen || isInPictureInPictureMode()) {
            Toast.makeText(this, "Quittez d'abord le plein ecran",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        int firstIndex = active;
        Tab first = tabs.get(firstIndex);
        Tab second = tabs.get(secondIndex);
        first.webAppMode = false;
        second.webAppMode = false;
        if (webApps != null) webApps.hideStandaloneBar();
        setBrowserChromeVisible(true);

        selectTab(secondIndex);
        selectTab(firstIndex);

        if (splitScreen.start(first.session, second.session)) {
            focusSplitSession(first.session);
            applyTabActivity();
            updateTabButton();
            Toast.makeText(this, "Ecran partage active", Toast.LENGTH_SHORT).show();
        }
    }

    private void showSplitOrientationMenu() {
        new Menus(this, "Disposition")
            .add("A", "Automatique", "cote a cote en paysage, haut / bas en portrait",
                    () -> {
                        splitScreen.setOrientationMode(SplitScreenManager.ORIENTATION_AUTO);
                        showSplitScreenMenu();
                    })
            .add("\u2194", "Cote a cote", () -> {
                splitScreen.setOrientationMode(SplitScreenManager.ORIENTATION_HORIZONTAL);
                showSplitScreenMenu();
            })
            .add("\u2195", "Haut / bas", () -> {
                splitScreen.setOrientationMode(SplitScreenManager.ORIENTATION_VERTICAL);
                showSplitScreenMenu();
            })
            .back(this::showSplitScreenMenu)
            .show();
    }

    private void showSplitRatioMenu() {
        new Menus(this, "Repartition")
            .add("30", "30 / 70", () -> setSplitRatio(0.30f))
            .add("40", "40 / 60", () -> setSplitRatio(0.40f))
            .add("50", "50 / 50", () -> setSplitRatio(0.50f))
            .add("60", "60 / 40", () -> setSplitRatio(0.60f))
            .add("70", "70 / 30", () -> setSplitRatio(0.70f))
            .back(this::showSplitScreenMenu)
            .show();
    }

    private void setSplitRatio(float ratio) {
        splitScreen.setRatio(ratio);
        showSplitScreenMenu();
    }

    // =======================================================================
    //  Applications web installables
    // =======================================================================
    private Tab currentTab() {
        return active >= 0 && active < tabs.size() ? tabs.get(active) : null;
    }

    private boolean isCurrentWebAppMode() {
        Tab tab = currentTab();
        return tab != null && tab.webAppMode;
    }

    private WebAppManager.App webAppFor(Tab tab) {
        if (tab == null) return null;
        WebAppManager.App app = new WebAppManager.App();
        app.id = tab.webAppId;
        app.name = tab.webAppName == null || tab.webAppName.isEmpty()
                ? tabLabel(tab) : tab.webAppName;
        app.startUrl = tab.url;
        app.scope = tab.webAppScope;
        app.themeColor = tab.webAppTheme;
        app.display = tab.webAppDisplay;
        app.manifestBacked = true;
        return app;
    }

    private void openWebApp(WebAppManager.App app, boolean standalone) {
        if (app == null || app.startUrl == null || app.startUrl.isEmpty()) return;
        if (standalone && splitScreen != null && splitScreen.isActive()) {
            splitScreen.exit();
        }
        if (!standalone) {
            setupSession(false, app.startUrl);
            selectTab(tabs.size() - 1);
            return;
        }

        Tab target;
        if (tabs.size() == 1 && active == 0
                && (currentUrl.isEmpty() || currentUrl.startsWith("moz-extension://"))) {
            target = tabs.get(0);
            target.pending = null;
            target.pendingState = null;
            target.session.loadUri(app.startUrl);
        } else {
            setupSession(false, app.startUrl);
            target = tabs.get(tabs.size() - 1);
        }

        target.webAppMode = true;
        target.webAppId = app.id == null ? "" : app.id;
        target.webAppName = app.name == null ? "Application web" : app.name;
        target.webAppScope = app.scope == null || app.scope.isEmpty()
                ? app.startUrl : app.scope;
        target.webAppTheme = app.themeColor == null ? "" : app.themeColor;
        target.webAppDisplay = app.display == null ? "standalone" : app.display;
        selectTab(tabs.indexOf(target));
        scheduleSessionSave();
    }

    private void showWebApps() {
        webApps.show(session, currentUrl, currentTitle, privateMode,
                this::showMenu, this::openWebApp);
    }

    private void applyWebAppChrome(Tab tab) {
        if (webApps == null) return;
        if (mediaFullscreen || isInPictureInPictureMode()) {
            webApps.hideStandaloneBar();
            return;
        }
        if (tab != null && tab.webAppMode) {
            setBrowserChromeVisible(false);
            android.view.ViewGroup root = findViewById(R.id.root_container);
            android.view.View content = findViewById(R.id.browser_column);
            webApps.showStandaloneBar(root, content, webAppFor(tab),
                    () -> exitCurrentWebAppMode(false),
                    () -> exitCurrentWebAppMode(true));
        } else {
            webApps.hideStandaloneBar();
            setBrowserChromeVisible(true);
        }
    }

    private void exitCurrentWebAppMode(boolean close) {
        Tab tab = currentTab();
        if (tab == null) return;
        tab.webAppMode = false;
        tab.webAppId = "";
        applyWebAppChrome(tab);
        scheduleSessionSave();
        if (close && tabs.size() > 1) closeTab(active);
    }

    // =======================================================================
    //  Synchronisation chiffree
    // =======================================================================
    private void showEncryptedSync() {
        encryptedSync.show(this::showMenu,
                () -> session.loadUri(extPage("sync.html")));
    }

    // =======================================================================
    //  Coffre de mots de passe
    // =======================================================================
    private boolean isPrivateSession(GeckoSession target) {
        if (target == null) return false;
        for (Tab tab : tabs) {
            if (tab.session == target) return tab.priv;
        }
        return target == session && privateMode;
    }

    private void showPasswordVault() {
        passwordVault.show(this, this::showMenu, enabled -> {
            try { sRuntime.getSettings().setLoginAutofillEnabled(enabled); }
            catch (Throwable ignored) { }
        });
    }

    // =======================================================================
    //  Menu
    // =======================================================================
    private void showMenu() {
        new Menus(this, "GeckoBrowser")
            .add("\u2302", "Accueil", () -> session.loadUri(homeUrl()))
            .add("\u21BB", "Recharger", () -> session.reload())
            .sub("\u25A5", "Onglets", tabs.size() + " ouvert(s)", this::showTabs)
            .sub("\u25EB", "Ecran partage", splitScreen.summary(),
                 this::showSplitScreenMenu)
            .sub("\u25B6", "Multimedia", mediaHub.summary(),
                 () -> mediaHub.showMenu(this::showMenu))
            // DOWNLOAD_CENTER_V1 — file systeme, progression et historique.
            .sub("\u21E9", "Telechargements", DownloadCenter.summary(this),
                 () -> DownloadCenter.show(this, this::showMenu))
            .sub("\u229E", "Extensions", extensionManager.summary(),
                 () -> extensionManager.show(this::showMenu))
            .sub("\u25A3", "Applications web", webApps.summary(session, currentUrl),
                 this::showWebApps)
            .sub("\u25A4", "Page", pageHost(), this::showPageMenu)
            .sub("\u2315", "Recherche", engineName(), this::showSearchMenu)
            .sub("\u26E8", "Confidentialite",
                 Privacy.levelName(Privacy.level(this))
                   + (TorSupport.isEnabled(this) ? " \u00B7 Tor" : "")
                   + (privateMode ? " \u00B7 prive" : ""),
                 this::showPrivacyMenu)
            .sub("\u2726", "Apparence", ThemeManager.currentName(this),
                 this::showThemePicker)
            .sub("\u2699", "Scripts et styles", null, this::showScriptsMenu)
            .sub("\u2605", "Favoris", bookmarks().length() + " enregistre(s)",
                 this::showBookmarksMenu)
            .sub("\u275D", "Citations",
                 SelectionNotebook.count(this) + " enregistree(s)",
                 () -> SelectionNotebook.show(this))
            .sub("\u25C9", "Mots de passe", passwordVault.summary(),
                 this::showPasswordVault)
            .sub("\u267B", "Corbeille", null, this::showTrash)
            .add("\u26D4", blockerEnabled ? "Desactiver le blocage" : "Activer le blocage",
                 blockerEnabled ? blockedCount + " elements bloques" : "blocage inactif",
                 this::toggleBlocker)
            .sub("\u231A", "Historique", null,
                 () -> session.loadUri(extPage("history.html")))
            .sub("\u25D4", "Bilan de lecture", null,
                 () -> session.loadUri(extPage("report.html")))
            .sub("\u21F5", "Mes flux", null,
                 () -> session.loadUri(extPage("feeds.html")))
            .sub("\u2630", "File de lecture", null,
                 () -> session.loadUri(extPage("queue.html")))
            .sub("\u23F1", "Surveillances", null,
                 () -> session.loadUri(extPage("watch.html")))
            .sub("\u21C6", "Archives de pages", null,
                 () -> session.loadUri(extPage("versions.html")))
            .sub("\u21C4", "Synchronisation chiffree", encryptedSync.summary(),
                 this::showEncryptedSync)
            .add("\u24D8", "Aide et tutoriel", () -> session.loadUri(extPage("help.html")))
            .show();
    }

    /** Selecteur des palettes de l'interface native. */
    private void showThemePicker() {
        ThemeManager.showPicker(this, () -> {
            applyCurrentTheme();
            Toast.makeText(this,
                    "Theme applique : " + ThemeManager.currentName(this),
                    Toast.LENGTH_SHORT).show();
        }, this::showMenu);
    }

    /** Applique la palette sans recharger la page ni perdre les onglets. */
    private void applyCurrentTheme() {
        ThemeManager.apply(this);
        int color = ThemeManager.browserBackground(this);
        geckoView.setBackgroundColor(color);
        if (splitScreen != null) splitScreen.applyTheme(color);
        for (Tab tab : tabs) {
            if (tab.session == null || !tab.session.isOpen()) continue;
            try {
                tab.session.getCompositorController().setClearColor(color);
            } catch (Throwable ignored) { }
        }
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
            .add("\u2316", "Rechercher dans la page",
                 () -> { if (onWebPage()) showFindBar(); })
            .sub("\u6587", "Traduire", "vers " + trLangName(), this::showTranslateMenu)
            .add("\u2315", "Analyser la page", this::inspectPage)
            .add("\u2039", "Code source", this::viewSource)
            .add("\u2194", "Ce sujet vu ailleurs",
                 () -> { if (onWebPage()) sendCommand("elsewhere"); })
            .add("\u26A1", "Procedes trompeurs",
                 () -> { if (onWebPage()) sendCommand("patterns"); })
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
            .add("\u2302", "Ajouter au bureau", this::pinToHome)
            .add("\u2295", "Ajouter aux raccourcis",
                 () -> { if (onWebPage()) sendCommand("addShortcut"); })
            .add("\u21F5", "Creer un flux",
                 () -> { if (onWebPage()) sendCommand("makeFeed"); })
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

    /** Epingle la page courante sur l'ecran d'accueil du telephone. */
    private void pinToHome() {
        if (!onWebPage()) return;
        final EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(currentTitle.isEmpty() ? "" : currentTitle);

        Menus.choice(this, "Ajouter au bureau")
            .setMessage("Nom du raccourci")
            .setView(input)
            .setPositiveButton("Ajouter", (d, w) ->
                Shortcuts.pin(this, currentUrl, input.getText().toString()))
            .setNegativeButton("Annuler", null)
            .show();
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
        Menus.dialog(this)
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

    private void showPrivacyCockpit() {
        PrivacyCockpit.show(this, sRuntime, session, currentUrl, blockedCount,
                blockerEnabled, privateMode, prefs, this::toggleBlocker,
                () -> session.reload());
    }

    private void showPrivacyMenu() {
        new Menus(this, "Confidentialite")
            .sub("\u25C9", "Cockpit de confidentialite", pageHost(),
                 this::showPrivacyCockpit)
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
            .add("\u26A1", "Alerte mouchards",
                 prefs.getBoolean("sentinel", true) ? "actif" : "inactif",
                 this::toggleSentinel)
            .add("\u25CE", "Diagnostic d'empreinte",
                 () -> { if (onWebPage()) sendCommand("fingerprint"); })
            .add("\u24D8", "Ce que ce navigateur revele", this::privacyInfo)
            .back(this::showMenu)
            .show();
    }

    private void showLevelPicker() {
        final String[] names = { "Standard", "Renforce", "Strict" };
        Menus.dialog(this)
            .setTitle("Niveau de protection")
            .setSingleChoiceItems(names, Privacy.level(this), (d, which) -> {
                d.dismiss();
                Menus.dialog(this)
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
        Menus.dialog(this)
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
        Menus.dialog(this)
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

    /**
     * Active ou coupe l'encart signalant les mouchards a l'ouverture d'un site.
     * Le reglage vit cote extension : c'est elle qui affiche l'encart.
     */
    private void toggleSentinel() {
        boolean on = !prefs.getBoolean("sentinel", true);
        prefs.edit().putBoolean("sentinel", on).apply();

        if (blockerPort != null) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "setSentinel");
                msg.put("value", on);
                blockerPort.postMessage(msg);
            } catch (Exception ignored) { }
        }

        Toast.makeText(this, on
                ? "Les mouchards seront signales a l'ouverture des sites"
                : "Alerte desactivee", Toast.LENGTH_SHORT).show();
    }

    private void privacyInfo() {
        Menus.dialog(this)
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
        Menus.dialog(this)
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

        Menus.dialog(this)
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

        Menus.dialog(this)
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
        JSONObject gone = arr.optJSONObject(index);
        if (gone != null) {
            toTrash("favori", gone.optString("title"), gone.optString("url"));
        }
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

        Menus.dialog(this)
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

        Menus.dialog(this)
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
        // L'agent est persiste : DownloadManager telecharge hors de la session
        // Gecko et annoncerait sinon un agent different du profil choisi.
        prefs.edit().putInt("profile", index).putString("profileUA", ua).apply();
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
        Menus.dialog(this)
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
                .javaScriptEnabled(true)
                .webManifest(true)
                .loginAutofillEnabled(
                        prefs.getBoolean("passwordAutofill", true));

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

    /** Une page reelle, par opposition a about:blank ou a une session vide. */
    private static boolean isRealPage(String url) {
        return url != null && !url.isEmpty() && !url.startsWith("about:");
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
        if (mediaFullscreen) {
            exitMediaFullscreen();
            return;
        }
        if (findBar != null && findBar.getVisibility() == android.view.View.VISIBLE) {
            hideFindBar();
        } else if (canGoBack) {
            session.goBack();
        } else if (isCurrentWebAppMode()) {
            exitCurrentWebAppMode(tabs.size() > 1);
        } else if (tabs.size() > 1) {
            // Fermer l'onglet plutot que quitter : c'est l'attente courante.
            closeTab(active);
        } else {
            super.onBackPressed();
        }
    }
}
