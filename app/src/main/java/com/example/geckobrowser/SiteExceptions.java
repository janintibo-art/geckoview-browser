package com.example.geckobrowser;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoSession;

import java.util.HashSet;
import java.util.Set;

/**
 * SITE_EXCEPTIONS_V1 — assouplir la protection pour un seul site.
 *
 * Quand un site casse sous protection stricte, la reaction habituelle est
 * de baisser le niveau general, ce qui affaiblit la protection partout et
 * n'est presque jamais remonte ensuite. Une exception ne vaut que pour le
 * domaine vise ; tous les autres sites gardent le niveau choisi.
 *
 * L'exception agit sur `setUseTrackingProtection` de la session, qui est
 * un reglage par session : elle est donc reappliquee a chaque changement
 * de page, et n'a aucun effet persistant sur le moteur.
 */
public final class SiteExceptions {

    private static final String PREFS = "geckobrowser";
    private static final String KEY = "siteExceptions";

    private SiteExceptions() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h.replaceFirst("^www\\.", "");
        } catch (Throwable e) {
            return "";
        }
    }

    private static Set<String> all(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY, new HashSet<>()));
    }

    public static int count(Context ctx) {
        return all(ctx).size();
    }

    /** Vrai si la protection est assouplie pour ce domaine ou un parent. */
    public static boolean isRelaxed(Context ctx, String host) {
        if (host == null || host.isEmpty()) return false;
        Set<String> set = all(ctx);
        if (set.contains(host)) return true;
        // Une exception sur example.com couvre aussi app.example.com.
        int dot = host.indexOf('.');
        while (dot != -1) {
            String parent = host.substring(dot + 1);
            if (parent.indexOf('.') == -1) break;
            if (set.contains(parent)) return true;
            dot = host.indexOf('.', dot + 1);
        }
        return false;
    }

    public static void set(Context ctx, String host, boolean relaxed) {
        if (host == null || host.isEmpty()) return;
        Set<String> set = all(ctx);
        if (relaxed) set.add(host);
        else set.remove(host);
        prefs(ctx).edit().putStringSet(KEY, set).apply();
    }

    public static void clearAll(Context ctx) {
        prefs(ctx).edit().remove(KEY).apply();
    }

    /**
     * Applique l'etat correspondant a l'adresse courante.
     *
     * A appeler a chaque changement de page : le reglage vit dans la
     * session, pas dans le site.
     */
    public static void apply(Context ctx, GeckoSession session, String url) {
        if (session == null) return;
        boolean relaxed = isRelaxed(ctx, hostOf(url));
        try {
            session.getSettings().setUseTrackingProtection(!relaxed);
        } catch (Throwable ignored) { }
    }

    public static String summary(Context ctx, String currentUrl) {
        String host = hostOf(currentUrl);
        if (!host.isEmpty() && isRelaxed(ctx, host)) return "assouplie ici";
        int n = count(ctx);
        return n == 0 ? "aucune exception" : n + " site(s) assouplis";
    }

    // -----------------------------------------------------------------------
    //  Menu
    // -----------------------------------------------------------------------
    public static void show(Activity activity, String currentUrl,
                            Runnable back, Runnable reload) {
        final String host = hostOf(currentUrl);
        Menus m = new Menus(activity, "Exceptions par site");

        if (!host.isEmpty()) {
            final boolean relaxed = isRelaxed(activity, host);
            m.add(relaxed ? "\u25C9" : "\u25CB", host,
                  relaxed ? "protection assouplie" : "protection normale", () -> {
                      set(activity, host, !relaxed);
                      Toast.makeText(activity, !relaxed
                              ? "Protection assouplie pour " + host
                              : "Protection retablie pour " + host,
                              Toast.LENGTH_SHORT).show();
                      if (reload != null) reload.run();
                      show(activity, currentUrl, back, reload);
                  });
        } else {
            m.add("\u24D8", "Aucune page ouverte",
                  "ouvrez le site a assouplir", () -> { });
        }

        Set<String> set = all(activity);
        if (!set.isEmpty()) {
            java.util.List<String> sorted = new java.util.ArrayList<>(set);
            java.util.Collections.sort(sorted);
            for (String s : sorted) {
                if (s.equals(host)) continue;
                m.add("\u25C9", s, "protection assouplie", () -> {
                    set(activity, s, false);
                    Toast.makeText(activity, "Protection retablie pour " + s,
                            Toast.LENGTH_SHORT).show();
                    show(activity, currentUrl, back, reload);
                });
            }
            m.add("\u2327", "Tout retablir", set.size() + " site(s)", () -> {
                clearAll(activity);
                Toast.makeText(activity, "Toutes les exceptions levees",
                        Toast.LENGTH_SHORT).show();
                if (reload != null) reload.run();
                show(activity, currentUrl, back, reload);
            });
        }

        m.add("\u24D8", "Fonctionnement", () -> Menus.info(activity,
            "Exceptions par site",
            "Quand un site fonctionne mal sous protection stricte, assouplir "
            + "ici vaut mieux que baisser le niveau general : les autres "
            + "sites gardent la protection choisie.\n\n"
            + "Une exception desactive la protection contre le pistage pour "
            + "ce domaine et ses sous-domaines. Le bloqueur de publicites, "
            + "les filtres editoriaux et le refus des cookies tiers "
            + "continuent de s'appliquer.\n\n"
            + "Rechargez la page apres avoir change ce reglage."));

        m.back(back).show();
    }
}
