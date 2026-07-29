package com.example.geckobrowser;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

/**
 * DEVELOPER_MODE_V1 — reglages de developpement.
 *
 * Regroupe les leviers de GeckoView utiles au diagnostic et normalement
 * absents d'un navigateur grand public : debogage distant, about:config,
 * sortie console, coupure de JavaScript, polices web.
 *
 * Deux principes :
 *   - tout est desactive par defaut et survit au redemarrage ;
 *   - chaque entree annonce son cout reel (le debogage distant ouvre un
 *     port local, about:config permet de casser le navigateur), parce que
 *     ces reglages affaiblissent des protections mises en place ailleurs
 *     dans l'application.
 */
public final class DeveloperMode {

    private static final String PREFS = "geckobrowser";
    private static final String KEY_ON = "devMode";
    private static final String KEY_REMOTE = "devRemoteDebug";
    private static final String KEY_ABOUT_CONFIG = "devAboutConfig";
    private static final String KEY_CONSOLE = "devConsole";
    private static final String KEY_NO_JS = "devNoJs";
    private static final String KEY_NO_WEBFONTS = "devNoWebFonts";

    private DeveloperMode() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ON, false);
    }

    private static boolean on(Context ctx, String key) {
        return prefs(ctx).getBoolean(key, false);
    }

    private static void set(Context ctx, String key, boolean value) {
        prefs(ctx).edit().putBoolean(key, value).apply();
    }

    /** Resume affiche dans le menu principal. */
    public static String summary(Context ctx) {
        if (!enabled(ctx)) return "desactive";
        int n = 0;
        if (on(ctx, KEY_REMOTE)) n++;
        if (on(ctx, KEY_ABOUT_CONFIG)) n++;
        if (on(ctx, KEY_CONSOLE)) n++;
        if (on(ctx, KEY_NO_JS)) n++;
        if (on(ctx, KEY_NO_WEBFONTS)) n++;
        return n == 0 ? "actif" : "actif \u00b7 " + n + " reglage(s)";
    }

    /**
     * Reapplique les reglages memorises au demarrage.
     *
     * Appele apres la creation du runtime : ces proprietes sont modifiables
     * a chaud, contrairement a celles fixees dans le constructeur.
     */
    public static void apply(Context ctx, GeckoRuntime runtime) {
        if (runtime == null) return;
        GeckoRuntimeSettings s = runtime.getSettings();
        boolean dev = enabled(ctx);
        try { s.setRemoteDebuggingEnabled(dev && on(ctx, KEY_REMOTE)); } catch (Throwable ignored) { }
        try { s.setAboutConfigEnabled(dev && on(ctx, KEY_ABOUT_CONFIG)); } catch (Throwable ignored) { }
        try { s.setConsoleOutputEnabled(dev && on(ctx, KEY_CONSOLE)); } catch (Throwable ignored) { }
        try { s.setJavaScriptEnabled(!(dev && on(ctx, KEY_NO_JS))); } catch (Throwable ignored) { }
        try { s.setWebFontsEnabled(!(dev && on(ctx, KEY_NO_WEBFONTS))); } catch (Throwable ignored) { }
    }

    public static void show(Activity activity, GeckoRuntime runtime, Runnable back) {
        final boolean dev = enabled(activity);
        Menus m = new Menus(activity, "Mode developpeur");

        m.add(dev ? "\u25C9" : "\u25CB", "Mode developpeur",
              dev ? "actif" : "desactive", () -> {
                  set(activity, KEY_ON, !dev);
                  apply(activity, runtime);
                  show(activity, runtime, back);
              });

        if (!dev) {
            m.add("\u24D8", "A quoi ca sert",
                  "diagnostic, pas navigation quotidienne", () ->
                  Menus.info(activity, "Mode developpeur",
                      "Active des leviers du moteur utiles au diagnostic : "
                      + "debogage distant depuis Firefox sur ordinateur, "
                      + "about:config, sortie console, coupure de JavaScript.\n\n"
                      + "Ces reglages affaiblissent des protections. Laissez ce "
                      + "mode desactive pour la navigation courante."));
            m.back(back).show();
            return;
        }

        toggle(activity, runtime, back, m, KEY_REMOTE, "Debogage distant",
               "about:debugging depuis un ordinateur \u00b7 ouvre un port local");
        toggle(activity, runtime, back, m, KEY_ABOUT_CONFIG, "about:config",
               "acces brut aux preferences du moteur");
        toggle(activity, runtime, back, m, KEY_CONSOLE, "Sortie console",
               "messages du moteur vers logcat");
        toggle(activity, runtime, back, m, KEY_NO_JS, "Couper JavaScript",
               "beaucoup de sites cesseront de fonctionner");
        toggle(activity, runtime, back, m, KEY_NO_WEBFONTS, "Bloquer les polices web",
               "affichage avec les polices du systeme");

        m.add("\u24D8", "Debogage distant : mode d'emploi", () ->
              Menus.info(activity, "Debogage distant",
                  "1. Activez le debogage USB dans les options pour "
                  + "developpeurs d'Android.\n"
                  + "2. Branchez le telephone a un ordinateur.\n"
                  + "3. Ouvrez about:debugging dans Firefox sur l'ordinateur.\n"
                  + "4. Le navigateur apparait dans la liste des appareils.\n\n"
                  + "Le port n'est ouvert que sur l'appareil lui-meme, mais "
                  + "toute application locale peut s'y connecter : ne laissez "
                  + "pas ce reglage actif en permanence."));

        // PROFILER_V1 — mesurer ce qu'une page coute reellement.
        m.add(profiling ? "\u25C9" : "\u25CB", "Profileur du moteur",
              profiling ? "enregistrement en cours \u00b7 toucher pour arreter"
                        : "mesure le travail du moteur", () ->
              toggleProfiler(activity, runtime, back));

        m.add("\u2327", "Tout remettre par defaut", () -> {
            prefs(activity).edit()
                    .remove(KEY_REMOTE).remove(KEY_ABOUT_CONFIG)
                    .remove(KEY_CONSOLE).remove(KEY_NO_JS)
                    .remove(KEY_NO_WEBFONTS).apply();
            apply(activity, runtime);
            Toast.makeText(activity, "Reglages de developpement remis a zero",
                    Toast.LENGTH_SHORT).show();
            show(activity, runtime, back);
        });

        m.back(back).show();
    }

    // -----------------------------------------------------------------------
    //  Profileur
    //  Demarre l'enregistrement du moteur puis ecrit le resultat dans un
    //  fichier ouvrable sur profiler.firefox.com. L'API n'existe pas sur
    //  toutes les versions de GeckoView : l'appel est donc protege et
    //  annonce clairement son absence plutot que d'echouer en silence.
    // -----------------------------------------------------------------------
    private static boolean profiling = false;

    private static void toggleProfiler(Activity activity, GeckoRuntime runtime,
                                       Runnable back) {
        Object controller;
        try {
            controller = GeckoRuntime.class
                    .getMethod("getProfilerController").invoke(runtime);
        } catch (Throwable e) {
            Menus.info(activity, "Profileur",
                "Le profileur n'est pas disponible dans cette version du "
                + "moteur.\n\nLe debogage distant reste utilisable : il donne "
                + "acces au profileur complet de Firefox depuis un ordinateur.");
            return;
        }
        if (controller == null) return;

        try {
            if (!profiling) {
                controller.getClass()
                        .getMethod("startProfiler", String[].class, String[].class)
                        .invoke(controller,
                                new String[] { "stackwalk", "js", "leaf" },
                                new String[] { "GeckoMain", "Compositor" });
                profiling = true;
                Toast.makeText(activity,
                        "Enregistrement demarre \u00b7 naviguez puis revenez l'arreter",
                        Toast.LENGTH_LONG).show();
                show(activity, runtime, back);
                return;
            }

            Object result = controller.getClass()
                    .getMethod("stopProfilerAndGetProfileUrlAsync")
                    .invoke(controller);
            profiling = false;
            if (result instanceof org.mozilla.geckoview.GeckoResult) {
                ((org.mozilla.geckoview.GeckoResult<?>) result).accept(
                    url -> activity.runOnUiThread(() -> Menus.info(activity,
                        "Profil enregistre",
                        "Le profil a ete produit.\n\n" + String.valueOf(url)
                        + "\n\nOuvrez profiler.firefox.com sur un ordinateur "
                        + "pour l'analyser.")),
                    err -> activity.runOnUiThread(() -> Toast.makeText(activity,
                        "Profil non recupere", Toast.LENGTH_SHORT).show()));
            } else {
                Toast.makeText(activity, "Enregistrement arrete",
                        Toast.LENGTH_SHORT).show();
            }
            show(activity, runtime, back);
        } catch (Throwable e) {
            profiling = false;
            Menus.info(activity, "Profileur",
                "Le profileur n'a pas pu demarrer dans cette version du "
                + "moteur.\n\nUtilisez le debogage distant depuis un "
                + "ordinateur pour obtenir la meme mesure.");
        }
    }

    private static void toggle(Activity activity, GeckoRuntime runtime, Runnable back,
                               Menus m, String key, String title, String note) {
        final boolean value = on(activity, key);
        m.add(value ? "\u25C9" : "\u25CB", title,
              (value ? "actif \u00b7 " : "") + note, () -> {
                  set(activity, key, !value);
                  apply(activity, runtime);
                  show(activity, runtime, back);
              });
    }
}
