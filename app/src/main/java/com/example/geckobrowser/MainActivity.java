package com.example.geckobrowser;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

public class MainActivity extends Activity {

    private static GeckoRuntime sRuntime;

    private GeckoSession session;
    private EditText urlBar;
    private TextView shield;
    private boolean canGoBack = false;

    private WebExtension.Port blockerPort;
    private boolean blockerEnabled = true;
    private int blockedCount = 0;

    private static String searchBase = null;   // moz-extension://<uuid>/search.html
    private static final String FALLBACK_HOME = "https://html.duckduckgo.com/html/";
    private static final String EXT_ID = "adblock@geckobrowser";
    private static final String EXT_URL = "resource://android/assets/adblock/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        GeckoView geckoView = findViewById(R.id.geckoview);
        urlBar = findViewById(R.id.url_bar);
        shield = findViewById(R.id.shield);
        ImageButton goButton = findViewById(R.id.go_button);
        ImageButton menuButton = findViewById(R.id.menu_button);

        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(this, buildSettings());
            installBlocker();
        } else {
            attachBlockerDelegate();
        }

        session = new GeckoSession();

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession s, String url,
                                         java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         Boolean hasUserGesture) {
                if (url != null) {
                    urlBar.setText(url);
                }
            }

            @Override
            public void onCanGoBack(GeckoSession s, boolean value) {
                canGoBack = value;
            }
        });

        session.open(sRuntime);
        geckoView.setSession(session);
        session.loadUri(homeUrl());

        goButton.setOnClickListener(v -> loadFromBar());
        menuButton.setOnClickListener(v -> showMenu());

        shield.setOnClickListener(v -> toggleBlocker());
        shield.setOnLongClickListener(v -> {
            Toast.makeText(this,
                    blockedCount + " element(s) bloque(s) sur cette session",
                    Toast.LENGTH_SHORT).show();
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
    }

    // -----------------------------------------------------------------------
    // Protection anti-pistage native de Gecko
    // -----------------------------------------------------------------------
    private GeckoRuntimeSettings buildSettings() {
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

        return new GeckoRuntimeSettings.Builder()
                .contentBlocking(blocking)
                .javaScriptEnabled(true)
                .build();
    }

    // -----------------------------------------------------------------------
    // Extension de blocage embarquee dans les assets
    // -----------------------------------------------------------------------
    private void installBlocker() {
        sRuntime.getWebExtensionController()
                .ensureBuiltIn(EXT_URL, EXT_ID)
                .accept(
                    ext -> bindPort(ext),
                    e -> runOnUiThread(() -> Toast.makeText(this,
                            "Bloqueur indisponible : " + e.getMessage(),
                            Toast.LENGTH_LONG).show())
                );
    }

    private void attachBlockerDelegate() {
        sRuntime.getWebExtensionController()
                .ensureBuiltIn(EXT_URL, EXT_ID)
                .accept(ext -> bindPort(ext), e -> { });
    }

    private void showMenu() {
        final String[] items = {
            "Accueil / recherche",
            "Filtres et categories",
            "Mes scripts",
            "Recharger la page",
            blockerEnabled ? "Desactiver le blocage" : "Activer le blocage"
        };

        new android.app.AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0:
                        session.loadUri(homeUrl());
                        break;
                    case 1:
                        session.loadUri(extPage("search.html") + "#filtres");
                        break;
                    case 2:
                        session.loadUri(extPage("scripts.html"));
                        break;
                    case 3:
                        session.reload();
                        break;
                    case 4:
                        toggleBlocker();
                        break;
                }
            })
            .show();
    }

    private String extPage(String file) {
        if (searchBase != null) {
            return searchBase.replace("search.html", file);
        }
        return FALLBACK_HOME;
    }

    private String homeUrl() {
        return searchBase != null ? searchBase : FALLBACK_HOME;
    }

    private String searchUrl(String query) {
        if (searchBase != null) {
            return searchBase + "?q=" + android.net.Uri.encode(query);
        }
        return "https://html.duckduckgo.com/html/?q=" + android.net.Uri.encode(query);
    }

    private void bindPort(WebExtension ext) {
        if (ext == null) return;

        // Recupere l'URL interne de l'extension pour y servir le moteur.
        try {
            if (ext.metaData != null && ext.metaData.baseUrl != null) {
                searchBase = ext.metaData.baseUrl + "search.html";
                runOnUiThread(() -> {
                    if (session != null && urlBar.getText().length() == 0) {
                        session.loadUri(homeUrl());
                    }
                });
            }
        } catch (Throwable ignored) { }

        ext.setMessageDelegate(new WebExtension.MessageDelegate() {
            @Override
            public void onConnect(WebExtension.Port port) {
                blockerPort = port;
                port.setDelegate(new WebExtension.PortDelegate() {
                    @Override
                    public void onPortMessage(Object message, WebExtension.Port p) {
                        if (!(message instanceof JSONObject)) return;
                        JSONObject json = (JSONObject) message;
                        if (!"state".equals(json.optString("type"))) return;
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

    private void toggleBlocker() {
        blockerEnabled = !blockerEnabled;
        updateShield();
        if (blockerPort != null) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "setEnabled");
                msg.put("value", blockerEnabled);
                blockerPort.postMessage(msg);
            } catch (Exception ignored) { }
        }
        Toast.makeText(this,
                blockerEnabled ? "Blocage active" : "Blocage desactive",
                Toast.LENGTH_SHORT).show();
        session.reload();
    }

    private void updateShield() {
        if (!blockerEnabled) {
            shield.setText("OFF");
            shield.setTextColor(0xFF9E9E9E);
        } else {
            shield.setText(blockedCount > 999 ? "999+" : String.valueOf(blockedCount));
            shield.setTextColor(0xFF4CAF50);
        }
    }

    // -----------------------------------------------------------------------
    // Navigation
    // -----------------------------------------------------------------------
    private void loadFromBar() {
        String input = urlBar.getText().toString().trim();
        if (input.isEmpty()) return;

        String url;
        if (input.startsWith("http://") || input.startsWith("https://")) {
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
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        }
        urlBar.clearFocus();
    }

    @Override
    public void onBackPressed() {
        if (canGoBack) {
            session.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
