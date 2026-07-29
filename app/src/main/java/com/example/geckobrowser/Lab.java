package com.example.geckobrowser;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * LAB_V1 — laboratoire : fonctions experimentales du moteur.
 *
 * Chaque entree est une preference Gecko ecrite dans le fichier de
 * configuration (voir Privacy.writeConfig). Elles ne prennent effet
 * qu'au redemarrage du moteur, comme toutes les preferences de ce
 * fichier.
 *
 * Regles :
 *   - tout est desactive par defaut ;
 *   - chaque entree annonce honnetement ce qu'elle risque de casser ;
 *   - rien ici n'est garanti : ce sont des fonctions que Mozilla n'a pas
 *     encore stabilisees, et une mise a jour du moteur peut les retirer
 *     sans preavis. Une preference inconnue est simplement ignoree par
 *     Gecko, donc une entree devenue obsolete est sans danger.
 */
public final class Lab {

    /** cle interne, preference Gecko, type, nom affiche, effet annonce. */
    private static final String[][] FEATURES = {
        { "webgpu", "dom.webgpu.enabled", "bool", "WebGPU",
          "calcul et rendu 3D nouvelle generation \u00b7 rarement utilise, peut planter" },

        { "http3", "network.http.http3.enable", "bool", "HTTP/3 (QUIC)",
          "connexions plus rapides \u00b7 quelques reseaux d'entreprise le bloquent" },

        { "avif", "image.avif.enabled", "bool", "Images AVIF",
          "images plus legeres \u00b7 sans effet si le site n'en sert pas" },

        { "jxl", "image.jxl.enabled", "bool", "Images JPEG XL",
          "format non finalise \u00b7 tres peu de sites l'utilisent" },

        { "prio", "dom.enable_web_task_scheduling", "bool", "Ordonnanceur de taches",
          "pages complexes plus reactives \u00b7 encore experimental" },

        { "reader", "reader.parse-on-load.enabled", "bool", "Analyse lecture anticipee",
          "mode lecture instantane \u00b7 analyse chaque page au chargement" }
    };

    private static final String PREFS = "geckobrowser";
    private static final String KEY_PREFIX = "lab_";

    private Lab() { }

    /** Canal GeckoView reellement compile (voir app/build.gradle). */
    public static String channel() {
        try {
            return BuildConfig.GECKO_CHANNEL;
        } catch (Throwable e) {
            return "stable";
        }
    }

    private static String channelLabel() {
        String c = channel();
        if ("nightly".equals(c)) return "nightly \u00b7 instable par nature";
        if ("beta".equals(c)) return "beta \u00b7 en preparation";
        return "stable \u00b7 recommande";
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean on(Context ctx, String key) {
        return prefs(ctx).getBoolean(KEY_PREFIX + key, false);
    }

    public static int activeCount(Context ctx) {
        int n = 0;
        for (String[] f : FEATURES) if (on(ctx, f[0])) n++;
        return n;
    }

    public static String summary(Context ctx) {
        int n = activeCount(ctx);
        return n == 0 ? "aucune fonction activee" : n + " fonction(s) activee(s)";
    }

    /**
     * Lignes a ajouter au fichier de configuration du moteur.
     *
     * Seules les fonctions activees sont ecrites : une fonction laissee au
     * repos ne touche pas au reglage par defaut de Gecko.
     */
    public static String prefLines(Context ctx) {
        StringBuilder s = new StringBuilder();
        for (String[] f : FEATURES) {
            if (!on(ctx, f[0])) continue;
            s.append("pref(\"").append(f[1]).append("\", true);\n");
        }
        return s.toString();
    }

    public static void show(Activity activity, Runnable back, Runnable restart) {
        Menus m = new Menus(activity, "Laboratoire");

        for (String[] f : FEATURES) {
            final String key = f[0];
            final boolean value = on(activity, key);
            m.add(value ? "\u25C9" : "\u25CB", f[3],
                  (value ? "active \u00b7 " : "") + f[4], () -> {
                      prefs(activity).edit()
                              .putBoolean(KEY_PREFIX + key, !value).apply();
                      show(activity, back, restart);
                  });
        }

        if (activeCount(activity) > 0) {
            m.add("\u2327", "Tout desactiver", () -> {
                SharedPreferences.Editor e = prefs(activity).edit();
                for (String[] f : FEATURES) e.remove(KEY_PREFIX + f[0]);
                e.apply();
                show(activity, back, restart);
            });
        }

        m.add("\u21BB", "Redemarrer pour appliquer",
              "les preferences du moteur se lisent au demarrage", restart);

        m.add("\u26A1", "Moteur", channelLabel(), () ->
            Menus.info(activity, "Canal du moteur",
                "Cette version est compilee avec le canal \u00ab " + channel()
                + " \u00bb de GeckoView.\n\n"
                + "Le canal se choisit a la compilation, pas dans "
                + "l'application :\n\n"
                + "  gradle assembleDebug -PgeckoChannel=nightly\n\n"
                + "« stable » est le seul canal recommande pour un usage "
                + "quotidien. « beta » et « nightly » changent tous les jours "
                + "et peuvent casser l'application sans preavis."));

        m.add("\u24D8", "Fonctionnement", () -> Menus.info(activity, "Laboratoire",
            "Ces fonctions sont des reglages que Mozilla n'a pas encore "
            + "stabilises. Elles ne prennent effet qu'au redemarrage du "
            + "moteur.\n\n"
            + "Rien n'est garanti : une mise a jour du moteur peut retirer "
            + "l'une d'elles sans preavis. Une preference devenue inconnue "
            + "est simplement ignoree, donc sans danger.\n\n"
            + "En cas de comportement etrange, « Tout desactiver » puis "
            + "redemarrer revient a un moteur standard."));

        m.back(back).show();
    }
}
