package com.example.geckobrowser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.StorageController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Tableau de bord de confidentialite par onglet.
 *
 * Les evenements proviennent directement de GeckoView : ressources bloquees ou
 * autorisees, categories de pistage et etat TLS. Le compteur de l'extension
 * integree reste affiche separement, car il couvre ses propres filtres.
 */
public final class PrivacyCockpit {

    private PrivacyCockpit() { }

    private static final Map<GeckoSession, Report> REPORTS = new WeakHashMap<>();

    private static final class Report {
        String pageUrl = "";
        String pageHost = "";
        long startedAt = System.currentTimeMillis();

        boolean securityKnown;
        boolean secure;
        boolean securityException;
        int securityMode;
        int mixedActive;
        int mixedPassive;
        String tlsHost = "";

        int blocked;
        int loaded;
        int ads;
        int analytics;
        int social;
        int content;
        int fingerprinting;
        int cryptomining;
        int cookies;
        int unsafe;

        final LinkedHashMap<String, Integer> domains = new LinkedHashMap<>();

        void reset(String url) {
            pageUrl = url == null ? "" : url;
            pageHost = host(pageUrl);
            startedAt = System.currentTimeMillis();
            securityKnown = false;
            secure = false;
            securityException = false;
            securityMode = 0;
            mixedActive = 0;
            mixedPassive = 0;
            tlsHost = "";
            blocked = 0;
            loaded = 0;
            ads = 0;
            analytics = 0;
            social = 0;
            content = 0;
            fingerprinting = 0;
            cryptomining = 0;
            cookies = 0;
            unsafe = 0;
            domains.clear();
        }
    }

    private static synchronized Report report(GeckoSession session) {
        Report report = REPORTS.get(session);
        if (report == null) {
            report = new Report();
            REPORTS.put(session, report);
        }
        return report;
    }

    /** Branche les callbacks natifs de blocage sur une session. */
    public static void attach(GeckoSession session) {
        if (session == null) return;
        report(session);
        session.setContentBlockingDelegate(new ContentBlocking.Delegate() {
            @Override
            public void onContentBlocked(GeckoSession s, ContentBlocking.BlockEvent event) {
                record(s, event, true);
            }

            @Override
            public void onContentLoaded(GeckoSession s, ContentBlocking.BlockEvent event) {
                record(s, event, false);
            }
        });
    }

    /** Reinitialise les compteurs quand une nouvelle page commence. */
    public static void onPageStart(GeckoSession session, String url) {
        if (session == null) return;
        synchronized (PrivacyCockpit.class) {
            report(session).reset(url);
        }
    }

    /** Memorise le niveau TLS et la presence eventuelle de contenu mixte. */
    public static void onSecurityChange(
            GeckoSession session,
            GeckoSession.ProgressDelegate.SecurityInformation info) {
        if (session == null || info == null) return;
        synchronized (PrivacyCockpit.class) {
            Report r = report(session);
            r.securityKnown = true;
            r.secure = info.isSecure;
            r.securityException = info.isException;
            r.securityMode = info.securityMode;
            r.mixedActive = info.mixedModeActive;
            r.mixedPassive = info.mixedModePassive;
            r.tlsHost = info.host == null ? "" : info.host;
        }
    }

    private static void record(GeckoSession session,
                               ContentBlocking.BlockEvent event,
                               boolean blocked) {
        if (session == null || event == null) return;
        synchronized (PrivacyCockpit.class) {
            Report r = report(session);
            if (blocked) r.blocked++;
            else r.loaded++;

            int category = event.getAntiTrackingCategory();
            if ((category & ContentBlocking.AntiTracking.AD) != 0) r.ads++;
            if ((category & ContentBlocking.AntiTracking.ANALYTIC) != 0) r.analytics++;
            if ((category & ContentBlocking.AntiTracking.SOCIAL) != 0) r.social++;
            if ((category & ContentBlocking.AntiTracking.CONTENT) != 0) r.content++;
            if ((category & ContentBlocking.AntiTracking.FINGERPRINTING) != 0) {
                r.fingerprinting++;
            }
            if ((category & ContentBlocking.AntiTracking.CRYPTOMINING) != 0) {
                r.cryptomining++;
            }
            if (event.getCookieBehaviorCategory() != 0) r.cookies++;
            if (event.getSafeBrowsingCategory() != 0) r.unsafe++;

            String domain = host(event.uri);
            if (!domain.isEmpty()) {
                Integer old = r.domains.get(domain);
                if (old != null) {
                    r.domains.put(domain, old + 1);
                } else if (r.domains.size() < 30) {
                    r.domains.put(domain, 1);
                }
            }
        }
    }

    /** Affiche le cockpit pour l'onglet actif. */
    public static void show(Activity activity,
                            GeckoRuntime runtime,
                            GeckoSession session,
                            String currentUrl,
                            int extensionBlocked,
                            boolean blockerEnabled,
                            boolean privateMode,
                            SharedPreferences prefs,
                            Runnable toggleBlocker,
                            Runnable reload) {
        if (activity == null || session == null) return;
        if (!isWebPage(currentUrl)) {
            Toast.makeText(activity, "Ouvrez d'abord une page web",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final String reportText = buildReport(activity, session, currentUrl,
                extensionBlocked, blockerEnabled, privateMode, prefs);
        String titleHost = host(currentUrl);
        if (titleHost.isEmpty()) titleHost = "Page courante";

        Menus.dialog(activity)
                .setTitle("Cockpit — " + titleHost)
                .setMessage(reportText)
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Actions", (d, w) -> showActions(
                        activity, runtime, session, currentUrl, extensionBlocked,
                        blockerEnabled, privateMode, prefs, toggleBlocker, reload))
                .show();
    }

    private static void showActions(Activity activity,
                                    GeckoRuntime runtime,
                                    GeckoSession session,
                                    String currentUrl,
                                    int extensionBlocked,
                                    boolean blockerEnabled,
                                    boolean privateMode,
                                    SharedPreferences prefs,
                                    Runnable toggleBlocker,
                                    Runnable reload) {
        Menus menu = new Menus(activity, "Actions de confidentialite");
        menu.add(blockerEnabled ? "⛔" : "✓",
                blockerEnabled ? "Desactiver le bloqueur" : "Activer le bloqueur",
                blockerEnabled ? "peut aider un site casse" : "retablir la protection",
                toggleBlocker);
        menu.add("↻", "Recharger et remettre les compteurs a zero", () -> {
            onPageStart(session, currentUrl);
            reload.run();
        });
        menu.add("⌫", "Effacer les donnees de cet hote",
                "cookies, stockage, cache et permissions", () ->
                        confirmClearHost(activity, runtime, currentUrl, reload));
        menu.add("⧉", "Copier le rapport", () -> copyReport(activity,
                buildReport(activity, session, currentUrl, extensionBlocked,
                        blockerEnabled, privateMode, prefs)));
        menu.back(() -> show(activity, runtime, session, currentUrl,
                extensionBlocked, blockerEnabled, privateMode, prefs,
                toggleBlocker, reload)).show();
    }

    private static void confirmClearHost(Activity activity,
                                         GeckoRuntime runtime,
                                         String currentUrl,
                                         Runnable reload) {
        final String host = host(currentUrl);
        if (host.isEmpty() || runtime == null) {
            Toast.makeText(activity, "Hote introuvable", Toast.LENGTH_SHORT).show();
            return;
        }

        Menus.dialog(activity)
                .setTitle("Effacer les donnees de " + host + " ?")
                .setMessage("Cette action supprime les cookies, les stockages locaux, "
                        + "les caches et les permissions de cet hote. Vous pourrez etre "
                        + "deconnecte du site.")
                .setPositiveButton("Effacer", (d, w) -> {
                    try {
                        runtime.getStorageController()
                                .clearDataFromHost(host, StorageController.ClearFlags.SITE_DATA)
                                .accept(
                                        value -> activity.runOnUiThread(() -> {
                                            Toast.makeText(activity,
                                                    "Donnees de " + host + " effacees",
                                                    Toast.LENGTH_SHORT).show();
                                            reload.run();
                                        }),
                                        error -> activity.runOnUiThread(() ->
                                                Toast.makeText(activity,
                                                        "Effacement impossible : "
                                                                + error.getMessage(),
                                                        Toast.LENGTH_LONG).show()));
                    } catch (Throwable error) {
                        Toast.makeText(activity,
                                "Effacement impossible : " + error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private static void copyReport(Activity activity, String text) {
        ClipboardManager clipboard = (ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Rapport GeckoBrowser", text));
        Toast.makeText(activity, "Rapport copie", Toast.LENGTH_SHORT).show();
    }

    private static String buildReport(Activity activity,
                                      GeckoSession session,
                                      String currentUrl,
                                      int extensionBlocked,
                                      boolean blockerEnabled,
                                      boolean privateMode,
                                      SharedPreferences prefs) {
        Report snapshot;
        synchronized (PrivacyCockpit.class) {
            Report source = report(session);
            snapshot = copy(source);
        }

        String host = host(currentUrl);
        if (host.isEmpty()) host = snapshot.pageHost;
        StringBuilder out = new StringBuilder(900);

        out.append("INDICE LOCAL : ")
                .append(grade(snapshot, blockerEnabled, prefs))
                .append("\n")
                .append("Indication locale, pas un audit de securite.\n\n");

        out.append("CONNEXION\n");
        out.append("• Site : ").append(host.isEmpty() ? currentUrl : host).append('\n');
        out.append("• Transport : ").append(connectionLabel(snapshot, currentUrl)).append('\n');
        if (snapshot.securityException) {
            out.append("• Exception de certificat acceptee\n");
        }
        out.append("• Contenu mixte actif : ")
                .append(mixedLabel(snapshot.mixedActive)).append('\n');
        out.append("• Contenu mixte passif : ")
                .append(mixedLabel(snapshot.mixedPassive)).append("\n\n");

        out.append("BLOCAGE SUR CET ONGLET\n");
        out.append("• Evenements bloques par Gecko : ").append(snapshot.blocked).append('\n');
        out.append("• Evenements autorises signales : ").append(snapshot.loaded).append('\n');
        out.append("• Compteur de l'extension : ").append(extensionBlocked)
                .append(" (compteur affiche par l'extension)\n");
        out.append("• Bloqueur de l'application : ")
                .append(blockerEnabled ? "actif" : "desactive").append("\n\n");

        out.append("CATEGORIES DETECTEES\n");
        appendCount(out, "Publicite", snapshot.ads);
        appendCount(out, "Mesure d'audience", snapshot.analytics);
        appendCount(out, "Reseaux sociaux", snapshot.social);
        appendCount(out, "Traqueurs de contenu", snapshot.content);
        appendCount(out, "Empreinte numerique", snapshot.fingerprinting);
        appendCount(out, "Cryptominage", snapshot.cryptomining);
        appendCount(out, "Cookies ou stockage restreints", snapshot.cookies);
        appendCount(out, "Contenu dangereux", snapshot.unsafe);
        if (allCategoriesZero(snapshot)) out.append("• Aucun signal natif pour l'instant\n");

        out.append("\nDOMAINES CONCERNES\n");
        if (snapshot.domains.isEmpty()) {
            out.append("• Aucun domaine signale\n");
        } else {
            int shown = 0;
            for (Map.Entry<String, Integer> entry : snapshot.domains.entrySet()) {
                if (shown++ >= 10) break;
                out.append("• ").append(entry.getKey())
                        .append(" × ").append(entry.getValue()).append('\n');
            }
            if (snapshot.domains.size() > 10) {
                out.append("• + ").append(snapshot.domains.size() - 10)
                        .append(" autre(s) domaine(s)\n");
            }
        }

        out.append("\nREGLAGES ACTIFS\n");
        out.append("• Profil : ")
                .append(Privacy.levelName(Privacy.level(activity))).append('\n');
        out.append("• Anti-pistage Gecko : listes strictes\n");
        out.append("• Cookies : tiers traqueurs refuses\n");
        out.append("• Navigation privee : ")
                .append(privateMode ? "oui" : "non").append('\n');
        out.append("• DNS chiffre : ")
                .append(prefs.getBoolean("doh", false) ? "actif" : "inactif").append('\n');
        out.append("• Tor : ")
                .append(TorSupport.isEnabled(activity) ? "actif" : "inactif").append('\n');
        out.append("• Alerte mouchards : ")
                .append(prefs.getBoolean("sentinel", true) ? "active" : "inactive").append('\n');

        long seconds = Math.max(0L, (System.currentTimeMillis() - snapshot.startedAt) / 1000L);
        out.append("• Observation : ").append(seconds).append(" s");
        return out.toString();
    }

    private static Report copy(Report source) {
        Report r = new Report();
        r.pageUrl = source.pageUrl;
        r.pageHost = source.pageHost;
        r.startedAt = source.startedAt;
        r.securityKnown = source.securityKnown;
        r.secure = source.secure;
        r.securityException = source.securityException;
        r.securityMode = source.securityMode;
        r.mixedActive = source.mixedActive;
        r.mixedPassive = source.mixedPassive;
        r.tlsHost = source.tlsHost;
        r.blocked = source.blocked;
        r.loaded = source.loaded;
        r.ads = source.ads;
        r.analytics = source.analytics;
        r.social = source.social;
        r.content = source.content;
        r.fingerprinting = source.fingerprinting;
        r.cryptomining = source.cryptomining;
        r.cookies = source.cookies;
        r.unsafe = source.unsafe;
        r.domains.putAll(source.domains);
        return r;
    }

    private static String connectionLabel(Report r, String currentUrl) {
        if (r.securityKnown) {
            if (r.secure) {
                return r.securityMode
                        == GeckoSession.ProgressDelegate.SecurityInformation.SECURITY_MODE_VERIFIED
                        ? "HTTPS verifie" : "HTTPS securise";
            }
            return "non securisee";
        }
        String scheme = Uri.parse(currentUrl == null ? "" : currentUrl).getScheme();
        return "https".equalsIgnoreCase(scheme)
                ? "HTTPS, verification en attente" : "non securisee ou locale";
    }

    private static String mixedLabel(int state) {
        if (state == GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_LOADED) {
            return "charge";
        }
        if (state == GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_BLOCKED) {
            return "bloque";
        }
        return "aucun signal";
    }

    private static String grade(Report r, boolean blockerEnabled, SharedPreferences prefs) {
        int score = 100;
        if (!blockerEnabled) score -= 25;
        if (r.securityKnown && !r.secure) score -= 35;
        if (r.securityException) score -= 20;
        if (r.mixedActive == GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_LOADED) {
            score -= 30;
        }
        if (r.mixedPassive == GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_LOADED) {
            score -= 10;
        }
        if (!prefs.getBoolean("doh", false)) score -= 5;
        if (score >= 90) return "A";
        if (score >= 75) return "B";
        if (score >= 55) return "C";
        if (score >= 35) return "D";
        return "E";
    }

    private static boolean allCategoriesZero(Report r) {
        return r.ads == 0 && r.analytics == 0 && r.social == 0
                && r.content == 0 && r.fingerprinting == 0
                && r.cryptomining == 0 && r.cookies == 0 && r.unsafe == 0;
    }

    private static void appendCount(StringBuilder out, String name, int count) {
        if (count > 0) out.append("• ").append(name).append(" : ").append(count).append('\n');
    }

    private static boolean isWebPage(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static String host(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return "";
            return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Throwable ignored) {
            return "";
        }
    }
}
