package com.example.geckobrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;
import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Gestionnaire natif des WebExtensions GeckoView.
 *
 * Les extensions externes doivent etre signees par Mozilla. L'extension
 * integree de GeckoBrowser est protegee contre la desactivation et la
 * desinstallation, car elle porte le moteur de recherche et les protections.
 */
public final class ExtensionManager implements WebExtensionController.PromptDelegate {

    public interface PageOpener { void open(String url); }
    public interface PackagePicker { void pick(); }

    private final Activity activity;
    private final GeckoRuntime runtime;
    private final WebExtensionController controller;
    private final String protectedId;
    private final PageOpener pageOpener;
    private final PackagePicker packagePicker;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<WebExtension> extensions = new ArrayList<>();
    private final Map<String, WebExtension.Action> actions = new HashMap<>();
    private final Set<GeckoSession> sessions =
            Collections.newSetFromMap(new WeakHashMap<GeckoSession, Boolean>());

    private boolean loading;
    private AlertDialog popupDialog;
    private GeckoSession popupSession;

    private final WebExtension.ActionDelegate actionDelegate =
            new WebExtension.ActionDelegate() {
        @Override
        public void onBrowserAction(WebExtension extension, GeckoSession session,
                                    WebExtension.Action action) {
            rememberAction(extension, action);
        }

        @Override
        public void onPageAction(WebExtension extension, GeckoSession session,
                                 WebExtension.Action action) {
            rememberAction(extension, action);
        }

        @Override
        public GeckoResult<GeckoSession> onOpenPopup(WebExtension extension,
                                                      WebExtension.Action action) {
            return openPopup(extension, action);
        }

        @Override
        public GeckoResult<GeckoSession> onTogglePopup(WebExtension extension,
                                                        WebExtension.Action action) {
            if (popupDialog != null && popupDialog.isShowing()) {
                popupDialog.dismiss();
                return GeckoResult.fromValue(null);
            }
            return openPopup(extension, action);
        }
    };

    public ExtensionManager(Activity activity, GeckoRuntime runtime,
                            String protectedId, PageOpener pageOpener,
                            PackagePicker packagePicker) {
        this.activity = activity;
        this.runtime = runtime;
        this.controller = runtime.getWebExtensionController();
        this.protectedId = protectedId == null ? "" : protectedId;
        this.pageOpener = pageOpener;
        this.packagePicker = packagePicker;
        controller.setPromptDelegate(this);
        refresh(null);
    }

    public void release() {
        try {
            if (controller.getPromptDelegate() == this) controller.setPromptDelegate(null);
        } catch (Throwable ignored) { }
        closePopup();
    }

    public String summary() {
        if (loading && extensions.isEmpty()) return "chargement…";
        int enabled = 0;
        for (WebExtension extension : extensions) {
            if (extension.metaData != null && extension.metaData.enabled) enabled++;
        }
        return enabled + " active(s) · " + extensions.size() + " installee(s)";
    }

    public void attachSession(GeckoSession session) {
        if (session == null) return;
        sessions.add(session);
        for (WebExtension extension : extensions) bindToSession(session, extension);
    }

    public void setTabActive(GeckoSession session, boolean active) {
        if (session == null) return;
        try { controller.setTabActive(session, active); }
        catch (Throwable ignored) { }
    }

    public void show(Runnable back) {
        refresh(() -> showLoaded(back));
    }

    private void showLoaded(Runnable back) {
        Menus menu = new Menus(activity, "Extensions");
        menu.add("＋", "Installer un fichier XPI", "fichier signe par Mozilla",
                () -> packagePicker.pick());
        menu.add("↧", "Installer depuis une adresse", "lien HTTPS direct vers un .xpi",
                this::askRemoteAddress);
        menu.add("⌘", "Catalogue Mozilla Add-ons", "extensions compatibles Android",
                () -> pageOpener.open("https://addons.mozilla.org/android/"));

        if (!visibleActions().isEmpty()) {
            menu.sub("✦", "Actions d'extensions",
                    visibleActions().size() + " disponible(s)", this::showActions);
        }

        for (WebExtension extension : extensions) {
            WebExtension.MetaData meta = extension.metaData;
            String name = safe(meta == null ? null : meta.name, extension.id);
            String value = status(extension);
            menu.sub(extension.isBuiltIn ? "◆" : (meta != null && meta.enabled ? "●" : "○"),
                    name, value, () -> showExtension(extension));
        }

        menu.add("↻", "Actualiser la liste", () -> show(back));
        menu.add("ⓘ", "Compatibilite et securite", this::showHelp);
        menu.back(back).show();
    }

    private void refresh(Runnable done) {
        if (loading) {
            if (done != null) ui.postDelayed(() -> refresh(done), 180);
            return;
        }
        loading = true;
        controller.list().accept(list -> ui.post(() -> {
            loading = false;
            extensions.clear();
            if (list != null) extensions.addAll(list);
            extensions.sort(Comparator
                    .comparing((WebExtension e) -> !e.isBuiltIn)
                    .thenComparing(e -> safe(e.metaData == null ? null : e.metaData.name, e.id),
                                   String.CASE_INSENSITIVE_ORDER));
            rebindActions();
            if (done != null) done.run();
        }), error -> ui.post(() -> {
            loading = false;
            Toast.makeText(activity, "Liste des extensions indisponible : " + errorText(error),
                    Toast.LENGTH_LONG).show();
            if (done != null) done.run();
        }));
    }

    private void rebindActions() {
        for (WebExtension extension : extensions) {
            try { extension.setActionDelegate(actionDelegate); }
            catch (Throwable ignored) { }
            for (GeckoSession session : sessions) bindToSession(session, extension);
        }
        actions.keySet().removeIf(id -> findById(id) == null);
    }

    private void bindToSession(GeckoSession session, WebExtension extension) {
        try {
            session.getWebExtensionController().setActionDelegate(extension, actionDelegate);
        } catch (Throwable ignored) { }
    }

    private WebExtension findById(String id) {
        for (WebExtension extension : extensions) {
            if (extension.id.equals(id)) return extension;
        }
        return null;
    }

    private void rememberAction(WebExtension extension, WebExtension.Action action) {
        if (extension == null || action == null) return;
        ui.post(() -> {
            if (Boolean.FALSE.equals(action.enabled)) actions.remove(extension.id);
            else actions.put(extension.id, action);
        });
    }

    private Map<String, WebExtension.Action> visibleActions() {
        Map<String, WebExtension.Action> out = new HashMap<>();
        for (Map.Entry<String, WebExtension.Action> entry : actions.entrySet()) {
            WebExtension extension = findById(entry.getKey());
            if (extension == null || extension.metaData == null || !extension.metaData.enabled) continue;
            if (Boolean.FALSE.equals(entry.getValue().enabled)) continue;
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private void showActions() {
        Map<String, WebExtension.Action> current = visibleActions();
        if (current.isEmpty()) {
            Toast.makeText(activity, "Aucune action d'extension sur cette page",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Menus menu = new Menus(activity, "Actions d'extensions");
        for (Map.Entry<String, WebExtension.Action> entry : current.entrySet()) {
            WebExtension extension = findById(entry.getKey());
            WebExtension.Action action = entry.getValue();
            String extName = extension == null ? entry.getKey()
                    : safe(extension.metaData == null ? null : extension.metaData.name,
                           extension.id);
            String title = safe(action.title, extName);
            String badge = safe(action.badgeText, "");
            menu.add("✦", title, badge.isEmpty() ? extName : extName + " · " + badge,
                    action::click);
        }
        menu.back(() -> show(null)).show();
    }

    private void showExtension(WebExtension extension) {
        WebExtension.MetaData meta = extension.metaData;
        String name = safe(meta == null ? null : meta.name, extension.id);
        Menus menu = new Menus(activity, name);

        if (!isProtected(extension)) {
            if (meta != null && meta.enabled) {
                menu.add("○", "Desactiver", "l'extension reste installee",
                        () -> setEnabled(extension, false));
            } else {
                menu.add("●", "Activer", () -> setEnabled(extension, true));
            }
        }

        if (meta != null) {
            menu.add(meta.allowedInPrivateBrowsing ? "◐" : "◑",
                    meta.allowedInPrivateBrowsing
                            ? "Interdire en navigation privee"
                            : "Autoriser en navigation privee",
                    () -> setPrivate(extension, !meta.allowedInPrivateBrowsing));
        }

        WebExtension.Action action = actions.get(extension.id);
        if (action != null && !Boolean.FALSE.equals(action.enabled)) {
            menu.add("✦", safe(action.title, "Executer l'action"), action::click);
        }

        if (meta != null && notEmpty(meta.optionsPageUrl)) {
            menu.add("⚙", "Options de l'extension", () -> pageOpener.open(meta.optionsPageUrl));
        }
        if (meta != null && notEmpty(meta.homepageUrl)) {
            menu.add("⌂", "Page d'accueil de l'extension",
                    () -> pageOpener.open(meta.homepageUrl));
        }
        if (meta != null && notEmpty(meta.amoListingUrl)) {
            menu.add("↗", "Fiche Mozilla Add-ons", () -> pageOpener.open(meta.amoListingUrl));
        }
        if (!extension.isBuiltIn) {
            menu.add("↻", "Rechercher une mise a jour", () -> update(extension));
        }

        menu.add("☷", "Permissions", permissionSummary(meta),
                () -> showPermissions(extension));
        menu.add("⧉", "Copier l'identifiant", extension.id,
                () -> copy(extension.id));

        if (!extension.isBuiltIn && !isProtected(extension)) {
            menu.add("⌫", "Desinstaller", "supprime aussi les donnees de l'extension",
                    () -> confirmUninstall(extension));
        }
        menu.back(() -> show(null)).show();
    }

    private String status(WebExtension extension) {
        WebExtension.MetaData meta = extension.metaData;
        StringBuilder out = new StringBuilder();
        if (extension.isBuiltIn) out.append("integree · ");
        out.append(meta != null && meta.enabled ? "active" : "desactivee");
        if (meta != null && notEmpty(meta.version)) out.append(" · v").append(meta.version);
        if (meta != null && meta.allowedInPrivateBrowsing) out.append(" · prive");
        return out.toString();
    }

    private boolean isProtected(WebExtension extension) {
        return extension != null && protectedId.equals(extension.id);
    }

    private void setEnabled(WebExtension extension, boolean enabled) {
        GeckoResult<WebExtension> result = enabled
                ? controller.enable(extension, WebExtensionController.EnableSource.USER)
                : controller.disable(extension, WebExtensionController.EnableSource.USER);
        result.accept(updated -> ui.post(() -> {
            Toast.makeText(activity, enabled ? "Extension activee" : "Extension desactivee",
                    Toast.LENGTH_SHORT).show();
            refresh(() -> show(null));
        }), error -> showError("Modification impossible", error));
    }

    private void setPrivate(WebExtension extension, boolean allowed) {
        controller.setAllowedInPrivateBrowsing(extension, allowed).accept(updated -> ui.post(() -> {
            Toast.makeText(activity,
                    allowed ? "Autorisee en navigation privee" : "Interdite en navigation privee",
                    Toast.LENGTH_SHORT).show();
            refresh(() -> showExtension(updated == null ? extension : updated));
        }), error -> showError("Reglage impossible", error));
    }

    private void update(WebExtension extension) {
        Toast.makeText(activity, "Recherche de mise a jour…", Toast.LENGTH_SHORT).show();
        controller.update(extension).accept(updated -> ui.post(() -> {
            Toast.makeText(activity,
                    updated == null ? "Extension deja a jour" : "Extension mise a jour",
                    Toast.LENGTH_SHORT).show();
            refresh(() -> showExtension(updated == null ? extension : updated));
        }), error -> showError("Mise a jour impossible", error));
    }

    private void confirmUninstall(WebExtension extension) {
        String name = safe(extension.metaData == null ? null : extension.metaData.name,
                           extension.id);
        Menus.dialog(activity)
                .setTitle("Desinstaller " + name + " ?")
                .setMessage("L'extension et toutes ses donnees locales seront supprimees.")
                .setPositiveButton("Desinstaller", (d, w) -> uninstall(extension))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void uninstall(WebExtension extension) {
        controller.uninstall(extension).accept(nothing -> ui.post(() -> {
            actions.remove(extension.id);
            Toast.makeText(activity, "Extension desinstallee", Toast.LENGTH_SHORT).show();
            refresh(() -> show(null));
        }), error -> showError("Desinstallation impossible", error));
    }

    public void installFromContentUri(Uri uri) {
        if (uri == null) return;
        Toast.makeText(activity, "Preparation de l'extension…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File cached = null;
            try {
                cached = cacheFile("extension-" + System.currentTimeMillis() + ".xpi");
                try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new Exception("fichier illisible");
                    copy(input, cached);
                }
                File ready = cached;
                ui.post(() -> installUri(ready.toURI().toString(),
                        WebExtensionController.INSTALLATION_METHOD_FROM_FILE, ready));
            } catch (Throwable error) {
                if (cached != null) cached.delete();
                showError("Import impossible", error);
            }
        }, "extension-import").start();
    }

    /** Intercepte un .xpi livre par Gecko, notamment depuis addons.mozilla.org. */
    public boolean handleExternalResponse(WebResponse response) {
        if (response == null || response.body == null) return false;
        String uri = safe(response.uri, "").toLowerCase(java.util.Locale.ROOT);
        String type = header(response, "content-type").toLowerCase(java.util.Locale.ROOT);
        boolean xpi = uri.contains(".xpi") || type.contains("application/x-xpinstall");
        if (!xpi) return false;

        Toast.makeText(activity, "Extension detectee : verification…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File cached = null;
            try {
                cached = cacheFile("extension-web-" + System.currentTimeMillis() + ".xpi");
                copy(response.body, cached);
                File ready = cached;
                ui.post(() -> installUri(ready.toURI().toString(),
                        WebExtensionController.INSTALLATION_METHOD_FROM_FILE, ready));
            } catch (Throwable error) {
                if (cached != null) cached.delete();
                showError("Extension illisible", error);
            }
        }, "extension-response").start();
        return true;
    }

    private void askRemoteAddress() {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("https://…/extension.xpi");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        Menus.dialog(activity)
                .setTitle("Installer depuis une adresse")
                .setMessage("Collez le lien HTTPS direct vers le paquet XPI signe.")
                .setView(input)
                .setPositiveButton("Installer", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (!value.startsWith("https://")) {
                        Toast.makeText(activity, "Une adresse HTTPS est obligatoire",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    installUri(value, WebExtensionController.INSTALLATION_METHOD_MANAGER, null);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void installUri(String uri, String method, File temporary) {
        Toast.makeText(activity, "Verification de la signature et des permissions…",
                Toast.LENGTH_LONG).show();
        controller.install(uri, method).accept(extension -> ui.post(() -> {
            if (temporary != null) temporary.delete();
            Toast.makeText(activity,
                    "Extension installee : " + safe(extension.metaData == null
                            ? null : extension.metaData.name, extension.id),
                    Toast.LENGTH_LONG).show();
            refresh(() -> showExtension(extension));
        }), error -> {
            if (temporary != null) temporary.delete();
            showError("Installation impossible", error);
        });
    }

    @Override
    public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
            WebExtension extension, String[] permissions, String[] origins,
            String[] dataCollectionPermissions) {
        GeckoResult<WebExtension.PermissionPromptResponse> result = new GeckoResult<>();
        ui.post(() -> showInstallPrompt(result, extension, permissions, origins,
                                        dataCollectionPermissions));
        return result;
    }

    private void showInstallPrompt(GeckoResult<WebExtension.PermissionPromptResponse> result,
                                   WebExtension extension, String[] permissions,
                                   String[] origins, String[] dataCollectionPermissions) {
        final boolean[] completed = { false };
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, dp(8), pad, dp(4));

        TextView details = new TextView(activity);
        details.setText(permissionText(extension, permissions, origins,
                                       dataCollectionPermissions));
        details.setTextSize(14f);
        box.addView(details, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        CheckBox privateBox = new CheckBox(activity);
        privateBox.setText("Autoriser en navigation privee");
        box.addView(privateBox);

        CheckBox dataBox = new CheckBox(activity);
        dataBox.setText("Autoriser la collecte technique et d'interaction demandee");
        dataBox.setVisibility(dataCollectionPermissions != null
                && dataCollectionPermissions.length > 0
                ? android.view.View.VISIBLE : android.view.View.GONE);
        box.addView(dataBox);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);

        AlertDialog dialog = Menus.dialog(activity)
                .setTitle("Installer l'extension ?")
                .setView(scroll)
                .setPositiveButton("Installer", (d, w) -> completeInstall(result, completed,
                        new WebExtension.PermissionPromptResponse(
                                true, privateBox.isChecked(), dataBox.isChecked())))
                .setNegativeButton("Refuser", (d, w) -> completeInstall(result, completed,
                        new WebExtension.PermissionPromptResponse(false, false, false)))
                .create();
        dialog.setOnCancelListener(d -> completeInstall(result, completed,
                new WebExtension.PermissionPromptResponse(false, false, false)));
        ThemeManager.show(dialog, activity);
    }

    private static void completeInstall(
            GeckoResult<WebExtension.PermissionPromptResponse> result,
            boolean[] completed, WebExtension.PermissionPromptResponse response) {
        if (completed[0]) return;
        completed[0] = true;
        result.complete(response);
    }

    @Override
    public GeckoResult<AllowOrDeny> onUpdatePrompt(WebExtension extension,
            String[] permissions, String[] origins, String[] dataCollectionPermissions) {
        return permissionChangePrompt("Nouvelles permissions pour la mise a jour",
                extension, permissions, origins, dataCollectionPermissions);
    }

    @Override
    public GeckoResult<AllowOrDeny> onOptionalPrompt(WebExtension extension,
            String[] permissions, String[] origins, String[] dataCollectionPermissions) {
        return permissionChangePrompt("L'extension demande des permissions",
                extension, permissions, origins, dataCollectionPermissions);
    }

    private GeckoResult<AllowOrDeny> permissionChangePrompt(String title,
            WebExtension extension, String[] permissions, String[] origins,
            String[] dataCollectionPermissions) {
        GeckoResult<AllowOrDeny> result = new GeckoResult<>();
        ui.post(() -> {
            final boolean[] completed = { false };
            AlertDialog dialog = Menus.dialog(activity)
                    .setTitle(title)
                    .setMessage(permissionText(extension, permissions, origins,
                                               dataCollectionPermissions))
                    .setPositiveButton("Autoriser", (d, w) -> completeDecision(
                            result, completed, AllowOrDeny.ALLOW))
                    .setNegativeButton("Refuser", (d, w) -> completeDecision(
                            result, completed, AllowOrDeny.DENY))
                    .create();
            dialog.setOnCancelListener(d -> completeDecision(
                    result, completed, AllowOrDeny.DENY));
            ThemeManager.show(dialog, activity);
        });
        return result;
    }

    private static void completeDecision(GeckoResult<AllowOrDeny> result,
                                         boolean[] completed, AllowOrDeny value) {
        if (completed[0]) return;
        completed[0] = true;
        result.complete(value);
    }

    private String permissionText(WebExtension extension, String[] permissions,
                                  String[] origins, String[] data) {
        String name = safe(extension == null || extension.metaData == null
                ? null : extension.metaData.name,
                extension == null ? "Extension" : extension.id);
        String version = extension != null && extension.metaData != null
                ? safe(extension.metaData.version, "") : "";
        StringBuilder out = new StringBuilder(name);
        if (!version.isEmpty()) out.append(" · version ").append(version);
        out.append("\n\n");
        appendArray(out, "Permissions", permissions);
        appendArray(out, "Sites accessibles", origins);
        appendArray(out, "Collecte de donnees declaree", data);
        out.append("\nUne extension peut lire ou modifier les pages correspondant aux sites ")
           .append("indiques. N'installez que des extensions dont vous connaissez l'origine.");
        return out.toString();
    }

    private void showPermissions(WebExtension extension) {
        WebExtension.MetaData meta = extension.metaData;
        StringBuilder out = new StringBuilder();
        if (meta == null) {
            out.append("Metadonnees indisponibles.");
        } else {
            appendArray(out, "Permissions obligatoires", meta.requiredPermissions);
            appendArray(out, "Sites obligatoires", meta.requiredOrigins);
            appendArray(out, "Collecte obligatoire", meta.requiredDataCollectionPermissions);
            appendArray(out, "Permissions facultatives accordees",
                        meta.grantedOptionalPermissions);
            appendArray(out, "Sites facultatifs accordes", meta.grantedOptionalOrigins);
            appendArray(out, "Collecte facultative accordee",
                        meta.grantedOptionalDataCollectionPermissions);
        }
        Menus.info(activity, "Permissions · " + safe(meta == null ? null : meta.name,
                extension.id), out.toString());
    }

    private String permissionSummary(WebExtension.MetaData meta) {
        if (meta == null) return "inconnues";
        int count = length(meta.requiredPermissions) + length(meta.requiredOrigins)
                + length(meta.requiredDataCollectionPermissions)
                + length(meta.grantedOptionalPermissions)
                + length(meta.grantedOptionalOrigins)
                + length(meta.grantedOptionalDataCollectionPermissions);
        return count + " declaration(s)";
    }

    private GeckoResult<GeckoSession> openPopup(WebExtension extension,
                                                 WebExtension.Action action) {
        GeckoResult<GeckoSession> result = new GeckoResult<>();
        ui.post(() -> {
            try {
                closePopup();
                GeckoSession popup = new GeckoSession();
                popup.open(runtime);
                GeckoView view = new GeckoView(activity);
                view.setBackgroundColor(ThemeManager.browserBackground(activity));
                view.setSession(popup);

                FrameLayout holder = new FrameLayout(activity);
                holder.setMinimumHeight(dp(420));
                holder.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));

                String extName = safe(extension == null || extension.metaData == null
                        ? null : extension.metaData.name, "Extension");
                popupDialog = Menus.dialog(activity)
                        .setTitle(safe(action == null ? null : action.title, extName))
                        .setView(holder)
                        .setNegativeButton("Fermer", null)
                        .show();
                popupSession = popup;
                popupDialog.setOnDismissListener(d -> closePopupSessionOnly());
                result.complete(popup);
            } catch (Throwable error) {
                closePopup();
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private void closePopup() {
        if (popupDialog != null) {
            try { popupDialog.setOnDismissListener(null); popupDialog.dismiss(); }
            catch (Throwable ignored) { }
            popupDialog = null;
        }
        closePopupSessionOnly();
    }

    private void closePopupSessionOnly() {
        if (popupSession != null) {
            try { popupSession.close(); } catch (Throwable ignored) { }
            popupSession = null;
        }
        popupDialog = null;
    }

    private void showHelp() {
        Menus.info(activity, "Extensions GeckoView",
                "GeckoBrowser peut installer des extensions WebExtension signees par Mozilla, "
              + "depuis un fichier XPI ou une adresse HTTPS. Les extensions integrees a l'APK "
              + "n'ont pas besoin de signature.\n\n"
              + "Avant chaque installation ou nouvelle permission, GeckoBrowser affiche les "
              + "droits demandes. L'acces en navigation privee reste desactive tant que vous "
              + "ne l'autorisez pas.\n\n"
              + "Toutes les extensions Firefox de bureau ne sont pas adaptees a Android ou a "
              + "GeckoView. Une extension incompatible peut s'installer mais ne pas proposer "
              + "toutes ses fonctions.");
    }

    private File cacheFile(String name) throws Exception {
        File dir = new File(activity.getCacheDir(), "extensions");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache inaccessible");
        return new File(dir, name);
    }

    private static void copy(InputStream input, File target) throws Exception {
        try (InputStream in = input; FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            long total = 0;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                total += count;
                if (total > 128L * 1024L * 1024L) {
                    throw new Exception("paquet trop volumineux");
                }
            }
            out.flush();
        }
    }

    private static String header(WebResponse response, String key) {
        if (response.headers == null) return "";
        for (Map.Entry<String, String> entry : response.headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return safe(entry.getValue(), "");
            }
        }
        return "";
    }

    private void copy(String text) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("extension", text));
            Toast.makeText(activity, "Identifiant copie", Toast.LENGTH_SHORT).show();
        }
    }

    private void showError(String title, Throwable error) {
        ui.post(() -> Menus.info(activity, title, errorText(error)));
    }

    private static String errorText(Throwable error) {
        if (error == null) return "Erreur inconnue";
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        return message;
    }

    private static void appendArray(StringBuilder out, String title, String[] values) {
        if (values == null || values.length == 0) return;
        out.append(title).append(" :\n");
        int limit = Math.min(values.length, 30);
        for (int i = 0; i < limit; i++) out.append("  • ").append(values[i]).append('\n');
        if (values.length > limit) out.append("  • … et ")
                .append(values.length - limit).append(" autre(s)\n");
        out.append('\n');
    }

    private static int length(String[] values) { return values == null ? 0 : values.length; }
    private static boolean notEmpty(String value) { return value != null && !value.trim().isEmpty(); }
    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
