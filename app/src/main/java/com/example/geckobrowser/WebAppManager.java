package com.example.geckobrowser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoSession;

import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Catalogue local des applications web de GeckoBrowser.
 *
 * GeckoView fournit le manifeste valide de la page. GeckoBrowser conserve les
 * informations utiles, cree un raccourci epingle avec une icone dessinee
 * localement et ouvre le site dans un mode application compact. Aucune icone
 * distante n'est recuperee lors de l'installation.
 */
public final class WebAppManager {

    public interface OpenHandler {
        void open(App app, boolean standalone);
    }

    public static final String EXTRA_ID = "geckobrowser.webapp.id";
    public static final String EXTRA_NAME = "geckobrowser.webapp.name";
    public static final String EXTRA_START = "geckobrowser.webapp.start";
    public static final String EXTRA_SCOPE = "geckobrowser.webapp.scope";
    public static final String EXTRA_THEME = "geckobrowser.webapp.theme";
    public static final String EXTRA_DISPLAY = "geckobrowser.webapp.display";

    private static final String PREFS = "geckobrowser_webapps";
    private static final String KEY_APPS = "apps";
    private static final int MAX_APPS = 40;

    public static class App {
        public String id = "";
        public String name = "Application web";
        public String startUrl = "";
        public String scope = "";
        public String display = "browser";
        public String themeColor = "";
        public String backgroundColor = "";
        public boolean manifestBacked;
        public long installedAt;

        JSONObject toJson() throws Exception {
            JSONObject out = new JSONObject();
            out.put("id", id);
            out.put("name", name);
            out.put("start", startUrl);
            out.put("scope", scope);
            out.put("display", display);
            out.put("theme", themeColor);
            out.put("background", backgroundColor);
            out.put("manifest", manifestBacked);
            out.put("installed", installedAt);
            return out;
        }

        static App fromJson(JSONObject in) {
            App app = new App();
            app.id = in.optString("id", "");
            app.name = in.optString("name", "Application web");
            app.startUrl = in.optString("start", "");
            app.scope = in.optString("scope", defaultScope(app.startUrl));
            app.display = in.optString("display", "browser");
            app.themeColor = in.optString("theme", "");
            app.backgroundColor = in.optString("background", "");
            app.manifestBacked = in.optBoolean("manifest", false);
            app.installedAt = in.optLong("installed", 0);
            if (app.id.isEmpty()) app.id = stableId(app.startUrl);
            return app;
        }

        App copy() {
            App out = new App();
            out.id = id;
            out.name = name;
            out.startUrl = startUrl;
            out.scope = scope;
            out.display = display;
            out.themeColor = themeColor;
            out.backgroundColor = backgroundColor;
            out.manifestBacked = manifestBacked;
            out.installedAt = installedAt;
            return out;
        }
    }

    private static final class Candidate extends App {
        String pageUrl = "";
    }

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Map<GeckoSession, Candidate> candidates = new WeakHashMap<>();

    private View standaloneBar;
    private View paddedContent;
    private int oldPaddingLeft;
    private int oldPaddingTop;
    private int oldPaddingRight;
    private int oldPaddingBottom;

    public WebAppManager(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Recoit le manifeste deja parse et valide par GeckoView. */
    public void onManifest(GeckoSession session, JSONObject manifest,
                           String pageUrl, String pageTitle) {
        if (session == null || manifest == null || !isHttpUrl(pageUrl)) return;
        try {
            Candidate app = new Candidate();
            app.pageUrl = pageUrl;
            app.name = firstNonEmpty(
                    manifest.optString("name", ""),
                    manifest.optString("short_name", ""),
                    pageTitle,
                    host(pageUrl),
                    "Application web");

            String rawStart = manifest.optString("start_url", pageUrl);
            app.startUrl = absolute(pageUrl, rawStart);
            if (!isHttpUrl(app.startUrl)) app.startUrl = pageUrl;

            String rawScope = manifest.optString("scope", "");
            app.scope = rawScope.isEmpty()
                    ? defaultScope(app.startUrl)
                    : absolute(app.startUrl, rawScope);
            if (!sameOrigin(app.startUrl, app.scope)) app.scope = defaultScope(app.startUrl);

            String manifestId = manifest.optString("id", "");
            String idBase = manifestId.isEmpty() ? app.startUrl : absolute(pageUrl, manifestId);
            app.id = stableId(idBase);
            app.display = firstNonEmpty(manifest.optString("display", ""), "browser");
            app.themeColor = normalizeColor(manifest.optString("theme_color", ""));
            app.backgroundColor = normalizeColor(manifest.optString("background_color", ""));
            app.manifestBacked = true;
            candidates.put(session, app);
        } catch (Throwable ignored) { }
    }

    /** Oublie un ancien manifeste lorsque la session change de site. */
    public void onLocation(GeckoSession session, String url) {
        Candidate candidate = candidates.get(session);
        if (candidate == null || !isHttpUrl(url)) return;
        if (!sameOrigin(candidate.pageUrl, url)) candidates.remove(session);
    }

    public String summary(GeckoSession current, String currentUrl) {
        int count = load().size();
        Candidate candidate = candidates.get(current);
        if (candidate != null && sameOrigin(candidate.pageUrl, currentUrl)) {
            return "installable · " + count + " installee(s)";
        }
        return count == 0 ? "aucune installee" : count + " installee(s)";
    }

    public void show(GeckoSession current, String currentUrl, String currentTitle,
                     boolean privateMode, Runnable back, OpenHandler opener) {
        Menus menu = new Menus(activity, "Applications web");
        Candidate candidate = candidateFor(current, currentUrl, currentTitle);

        if (privateMode) {
            menu.add("◐", "Installation indisponible en prive",
                    "ouvrez la page dans un onglet normal", () -> { });
        } else if (candidate != null) {
            App installed = find(candidate.id);
            menu.add(installed == null ? "＋" : "↻",
                    installed == null ? "Installer la page actuelle"
                            : "Mettre a jour l'application",
                    candidate.manifestBacked
                            ? "manifeste web detecte · " + candidate.name
                            : "raccourci web simple · " + candidate.name,
                    () -> confirmInstall(candidate, back, opener));
        }

        List<App> apps = load();
        apps.sort(Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)));
        for (App app : apps) {
            menu.sub(app.manifestBacked ? "◆" : "◇", app.name,
                    host(app.startUrl) + " · " + displayName(app.display),
                    () -> showApp(app, back, opener));
        }

        if (apps.isEmpty()) {
            menu.add("∅", "Aucune application installee",
                    "ouvrez un site puis revenez dans ce menu", () -> { });
        }
        menu.add("ⓘ", "Fonctionnement et confidentialite", this::showHelp);
        if (back != null) menu.back(back);
        menu.show();
    }

    private Candidate candidateFor(GeckoSession session, String url, String title) {
        if (!isHttpUrl(url)) return null;
        Candidate manifest = candidates.get(session);
        if (manifest != null && sameOrigin(manifest.pageUrl, url)) return manifest;

        Candidate simple = new Candidate();
        simple.pageUrl = url;
        simple.startUrl = url;
        simple.scope = defaultScope(url);
        simple.name = firstNonEmpty(title, host(url), "Application web");
        simple.id = stableId(url);
        simple.display = "browser";
        simple.manifestBacked = false;
        return simple;
    }

    private void confirmInstall(App source, Runnable back, OpenHandler opener) {
        App app = source.copy();
        String details = app.name + "\n" + host(app.startUrl)
                + "\n\nOuverture : " + displayName(app.display)
                + (app.manifestBacked
                    ? "\nManifeste web valide detecte par GeckoView."
                    : "\nAucun manifeste detecte : GeckoBrowser creera un raccourci simple.")
                + "\n\nL'icone est dessinee localement : aucun serveur d'icones n'est contacte.";

        Menus.dialog(activity)
                .setTitle(find(app.id) == null ? "Installer l'application ?"
                        : "Mettre a jour l'application ?")
                .setMessage(details)
                .setPositiveButton("Installer", (d, w) -> {
                    app.installedAt = System.currentTimeMillis();
                    upsert(app);
                    pinShortcut(app);
                    Toast.makeText(activity, "Application web enregistree",
                            Toast.LENGTH_SHORT).show();
                    if (opener != null) opener.open(app, true);
                })
                .setNegativeButton("Annuler", (d, w) -> {
                    if (back != null) back.run();
                })
                .show();
    }

    private void showApp(App app, Runnable parentBack, OpenHandler opener) {
        Menus menu = new Menus(activity, app.name);
        menu.add("▣", "Ouvrir en mode application", app.startUrl,
                () -> opener.open(app, true));
        menu.add("↗", "Ouvrir dans le navigateur", host(app.startUrl),
                () -> opener.open(app, false));
        menu.add("⌂", "Ajouter ou reparer le raccourci",
                "ecran d'accueil Android", () -> pinShortcut(app));
        menu.add("✎", "Renommer", () -> rename(app, parentBack, opener));
        menu.add("⧉", "Copier l'adresse", () -> copy(app.startUrl));
        menu.add("ⓘ", "Details", host(app.startUrl),
                () -> Menus.info(activity, app.name, details(app)));
        menu.add("⌫", "Retirer de GeckoBrowser",
                "le raccourci epingle reste supprimable depuis le lanceur",
                () -> confirmRemove(app, parentBack, opener));
        menu.back(() -> show(null, "", "", false, parentBack, opener)).show();
    }

    private void rename(App app, Runnable parentBack, OpenHandler opener) {
        final android.widget.EditText input = new android.widget.EditText(activity);
        input.setSingleLine(true);
        input.setText(app.name);
        input.setSelectAllOnFocus(true);
        int pad = dp(20);
        FrameLayout box = new FrameLayout(activity);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Menus.dialog(activity)
                .setTitle("Renommer l'application")
                .setView(box)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        app.name = name;
                        upsert(app);
                        pinShortcut(app);
                    }
                    showApp(app, parentBack, opener);
                })
                .setNegativeButton("Annuler", (d, w) -> showApp(app, parentBack, opener))
                .show();
    }

    private void confirmRemove(App app, Runnable parentBack, OpenHandler opener) {
        Menus.dialog(activity)
                .setTitle("Retirer " + app.name + " ?")
                .setMessage("L'application disparaitra du catalogue GeckoBrowser. "
                        + "Android ne permet pas a une application de supprimer de force "
                        + "un raccourci deja epingle : retirez-le depuis l'ecran d'accueil.")
                .setPositiveButton("Retirer", (d, w) -> {
                    remove(app.id);
                    show(null, "", "", false, parentBack, opener);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private String details(App app) {
        return "Adresse : " + app.startUrl
                + "\nPortee : " + app.scope
                + "\nAffichage demande : " + displayName(app.display)
                + "\nSource : " + (app.manifestBacked ? "manifeste web" : "raccourci simple")
                + (app.themeColor.isEmpty() ? "" : "\nCouleur du theme : " + app.themeColor);
    }

    private void showHelp() {
        Menus.info(activity, "Applications web",
                "GeckoBrowser utilise le manifeste web valide fourni par GeckoView. "
              + "Une application installee reste un site web : elle conserve les memes "
              + "protections, extensions, mots de passe et conteneurs que le navigateur.\n\n"
              + "Le mode application masque la barre d'adresse et affiche une petite barre "
              + "locale avec deux actions : revenir au navigateur ou fermer le mode. "
              + "Une navigation hors de la portee declaree repasse automatiquement dans "
              + "l'interface normale.\n\n"
              + "Les icones sont generees sur l'appareil. GeckoBrowser ne telecharge pas "
              + "l'icone distante du manifeste pendant l'installation.");
    }

    /** Cree un raccourci epingle Android 8+ avec une icone locale. */
    public void pinShortcut(App app) {
        if (app == null || !isHttpUrl(app.startUrl)) return;
        final App snapshot = app.copy();
        new Thread(() -> {
            String message;
            try {
                ShortcutManager manager = activity.getSystemService(ShortcutManager.class);
                if (manager == null || !manager.isRequestPinShortcutSupported()) {
                    message = "Le lanceur ne permet pas d'epingler automatiquement ce raccourci";
                } else {
                    Intent intent = new Intent(activity, MainActivity.class)
                            .setAction(Intent.ACTION_VIEW)
                            .setData(Uri.parse(snapshot.startUrl))
                            .putExtra(EXTRA_ID, snapshot.id)
                            .putExtra(EXTRA_NAME, snapshot.name)
                            .putExtra(EXTRA_START, snapshot.startUrl)
                            .putExtra(EXTRA_SCOPE, snapshot.scope)
                            .putExtra(EXTRA_THEME, snapshot.themeColor)
                            .putExtra(EXTRA_DISPLAY, snapshot.display)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);

                    String shortLabel = snapshot.name.length() > 24
                            ? snapshot.name.substring(0, 24) : snapshot.name;
                    ShortcutInfo shortcut = new ShortcutInfo.Builder(
                            activity, "webapp-" + snapshot.id)
                            .setShortLabel(shortLabel)
                            .setLongLabel(snapshot.name)
                            .setIcon(Icon.createWithBitmap(localIcon(snapshot)))
                            .setIntent(intent)
                            .build();
                    boolean requested = manager.requestPinShortcut(shortcut, null);
                    message = requested
                            ? "Raccourci propose a l'ecran d'accueil"
                            : "Le lanceur a refuse le raccourci";
                }
            } catch (Throwable error) {
                message = "Raccourci indisponible";
            }
            final String notice = message;
            activity.runOnUiThread(() -> Toast.makeText(
                    activity, notice, Toast.LENGTH_LONG).show());
        }, "webapp-shortcut").start();
    }

    private Bitmap localIcon(App app) {
        int size = 192;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int background = parseColor(app.themeColor, ThemeManager.current(activity).accent);
        int foreground = contrast(background);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(background);
        canvas.drawRoundRect(0, 0, size, size, 42, 42, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(9f);
        paint.setColor(withAlpha(foreground, 90));
        canvas.drawCircle(size / 2f, size / 2f, 66f, paint);
        paint.setStyle(Paint.Style.FILL);

        String initials = initials(app.name);
        paint.setColor(foreground);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(initials.length() > 1 ? 70f : 88f);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float y = size / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(initials, size / 2f, y, paint);
        return bitmap;
    }

    /** Lit les extras d'un raccourci d'application web. */
    public static App fromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        String id = intent.getStringExtra(EXTRA_ID);
        String start = intent.getStringExtra(EXTRA_START);
        if (id == null || id.isEmpty() || !isHttpUrl(start)) return null;

        App app = new App();
        app.id = id;
        app.name = firstNonEmpty(intent.getStringExtra(EXTRA_NAME), host(start), "Application web");
        app.startUrl = start;
        app.scope = firstNonEmpty(intent.getStringExtra(EXTRA_SCOPE), defaultScope(start));
        app.themeColor = firstNonEmpty(intent.getStringExtra(EXTRA_THEME), "");
        app.display = firstNonEmpty(intent.getStringExtra(EXTRA_DISPLAY), "standalone");
        app.manifestBacked = true;
        return app;
    }

    public static void consumeIntent(Intent intent) {
        if (intent == null) return;
        intent.setData(null);
        intent.removeExtra(EXTRA_ID);
        intent.removeExtra(EXTRA_NAME);
        intent.removeExtra(EXTRA_START);
        intent.removeExtra(EXTRA_SCOPE);
        intent.removeExtra(EXTRA_THEME);
        intent.removeExtra(EXTRA_DISPLAY);
    }

    /** Verifie la portee d'une application web sans retenir les fragments. */
    public static boolean inScope(String url, String scope) {
        if (!isHttpUrl(url) || !isHttpUrl(scope) || !sameOrigin(url, scope)) return false;
        try {
            Uri u = Uri.parse(url);
            Uri s = Uri.parse(scope);
            String up = firstNonEmpty(u.getPath(), "/");
            String sp = firstNonEmpty(s.getPath(), "/");
            if (!sp.endsWith("/")) sp = sp + "/";
            if (!up.endsWith("/")) {
                int slash = up.lastIndexOf('/');
                String parent = slash >= 0 ? up.substring(0, slash + 1) : "/";
                if (up.equals(s.getPath())) return true;
                return parent.startsWith(sp) || up.startsWith(sp);
            }
            return up.startsWith(sp);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Affiche la barre compacte du mode application au-dessus du contenu. */
    public void showStandaloneBar(ViewGroup root, View content, App app,
                                  Runnable openBrowser, Runnable close) {
        hideStandaloneBar();
        if (root == null || content == null || app == null) return;

        ThemeManager.Palette palette = ThemeManager.current(activity);
        int background = parseColor(app.themeColor, palette.surface);
        int text = contrast(background);

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(6), 0);
        bar.setElevation(dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(background);
        bg.setStroke(dp(1), withAlpha(text, 40));
        bar.setBackground(bg);

        TextView mark = button("◆", text, null);
        mark.setTextSize(18);
        bar.addView(mark, new LinearLayout.LayoutParams(dp(40), dp(52)));

        TextView title = new TextView(activity);
        title.setText(app.name);
        title.setTextColor(text);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, titleLp);

        TextView browser = button("↗", text, openBrowser);
        browser.setContentDescription("Ouvrir dans le navigateur");
        bar.addView(browser, new LinearLayout.LayoutParams(dp(48), dp(52)));

        TextView quit = button("×", text, close);
        quit.setContentDescription("Fermer le mode application");
        quit.setTextSize(28);
        bar.addView(quit, new LinearLayout.LayoutParams(dp(48), dp(52)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP);
        root.addView(bar, lp);
        standaloneBar = bar;

        paddedContent = content;
        oldPaddingLeft = content.getPaddingLeft();
        oldPaddingTop = content.getPaddingTop();
        oldPaddingRight = content.getPaddingRight();
        oldPaddingBottom = content.getPaddingBottom();
        content.setPadding(oldPaddingLeft, oldPaddingTop + dp(52),
                oldPaddingRight, oldPaddingBottom);
    }

    public void hideStandaloneBar() {
        if (standaloneBar != null) {
            ViewGroup parent = (ViewGroup) standaloneBar.getParent();
            if (parent != null) parent.removeView(standaloneBar);
            standaloneBar = null;
        }
        if (paddedContent != null) {
            paddedContent.setPadding(oldPaddingLeft, oldPaddingTop,
                    oldPaddingRight, oldPaddingBottom);
            paddedContent = null;
        }
    }

    public void release() {
        hideStandaloneBar();
        candidates.clear();
    }

    private TextView button(String label, int color, Runnable action) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextColor(color);
        view.setTextSize(21);
        view.setGravity(Gravity.CENTER);
        view.setClickable(action != null);
        view.setFocusable(action != null);
        if (action != null) view.setOnClickListener(v -> action.run());
        return view;
    }

    private List<App> load() {
        List<App> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_APPS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                App app = App.fromJson(item);
                if (isHttpUrl(app.startUrl)) out.add(app);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private void save(List<App> apps) {
        try {
            JSONArray out = new JSONArray();
            int n = Math.min(apps.size(), MAX_APPS);
            for (int i = 0; i < n; i++) out.put(apps.get(i).toJson());
            prefs.edit().putString(KEY_APPS, out.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private App find(String id) {
        if (id == null) return null;
        for (App app : load()) if (id.equals(app.id)) return app;
        return null;
    }

    private void upsert(App app) {
        List<App> apps = load();
        apps.removeIf(old -> old.id.equals(app.id));
        apps.add(0, app.copy());
        save(apps);
    }

    private void remove(String id) {
        List<App> apps = load();
        apps.removeIf(app -> app.id.equals(id));
        save(apps);
    }

    private void copy(String text) {
        ClipboardManager clipboard = (ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Adresse", text));
            Toast.makeText(activity, "Adresse copiee", Toast.LENGTH_SHORT).show();
        }
    }

    private static String absolute(String base, String value) {
        try { return new URL(new URL(base), value).toString(); }
        catch (Throwable ignored) { return value == null ? "" : value; }
    }

    private static String defaultScope(String start) {
        if (!isHttpUrl(start)) return start == null ? "" : start;
        try {
            Uri uri = Uri.parse(start);
            String path = firstNonEmpty(uri.getPath(), "/");
            if (!path.endsWith("/")) {
                int slash = path.lastIndexOf('/');
                path = slash >= 0 ? path.substring(0, slash + 1) : "/";
            }
            return new Uri.Builder()
                    .scheme(uri.getScheme())
                    .encodedAuthority(uri.getEncodedAuthority())
                    .encodedPath(path)
                    .build().toString();
        } catch (Throwable ignored) {
            return start;
        }
    }

    private static boolean sameOrigin(String a, String b) {
        if (!isHttpUrl(a) || !isHttpUrl(b)) return false;
        try {
            Uri x = Uri.parse(a);
            Uri y = Uri.parse(b);
            return safe(x.getScheme()).equalsIgnoreCase(safe(y.getScheme()))
                    && safe(x.getHost()).equalsIgnoreCase(safe(y.getHost()))
                    && effectivePort(x) == effectivePort(y);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int effectivePort(Uri uri) {
        int p = uri.getPort();
        if (p >= 0) return p;
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }

    private static boolean isHttpUrl(String value) {
        return value != null
                && (value.startsWith("https://") || value.startsWith("http://"));
    }

    private static String host(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? "site web" : host.replaceFirst("^www\\.", "");
        } catch (Throwable ignored) {
            return "site web";
        }
    }

    private static String stableId(String value) {
        String input = safe(value);
        int a = input.hashCode();
        int b = new StringBuilder(input).reverse().toString().hashCode();
        return Integer.toHexString(a) + Integer.toHexString(b);
    }

    private static String displayName(String display) {
        String value = safe(display).toLowerCase(Locale.ROOT);
        switch (value) {
            case "fullscreen": return "plein ecran";
            case "standalone": return "application autonome";
            case "minimal-ui": return "interface minimale";
            case "window-controls-overlay": return "fenetre integree";
            default: return "navigateur";
        }
    }

    private static String initials(String name) {
        String clean = firstNonEmpty(name, "W").trim();
        String[] words = clean.split("\\s+");
        String first = words[0].substring(0, 1);
        if (words.length > 1) first += words[words.length - 1].substring(0, 1);
        return first.toUpperCase(Locale.ROOT);
    }

    private static String normalizeColor(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (!v.startsWith("#")) return "";
        try {
            Color.parseColor(v);
            return v;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int parseColor(String value, int fallback) {
        try { return Color.parseColor(value); }
        catch (Throwable ignored) { return fallback; }
    }

    private static int contrast(int color) {
        double luminance = (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.58 ? Color.rgb(20, 22, 24) : Color.WHITE;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }
}
