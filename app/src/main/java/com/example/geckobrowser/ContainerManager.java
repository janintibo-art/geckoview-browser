package com.example.geckobrowser;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.StorageController;

/**
 * CONTAINERS_V1 — identites et conteneurs.
 *
 * Chaque identite recoit un `contextId` distinct passe a
 * GeckoSessionSettings. Gecko cloisonne alors cookies, stockage local,
 * IndexedDB, cache et connexions entre identites : etre connecte a un site
 * dans « Travail » n'a aucun effet dans « Personnel ».
 *
 * Ce n'est PAS de l'anonymat : l'adresse IP reste la meme partout, et un
 * site peut toujours reconnaitre l'appareil par d'autres moyens. C'est un
 * cloisonnement du stockage, presente comme tel dans l'aide — meme
 * honnetete que pour le mode Tor et l'identite d'appareil.
 */
public final class ContainerManager {

    /** id, nom affiche, pictogramme, mode prive. */
    private static final String[][] CONTAINERS = {
        { "",       "Aucune",            "\u25CB", "0" },
        { "perso",  "Personnel",         "\u25CF", "0" },
        { "travail","Travail",           "\u25A0", "0" },
        { "banque", "Banque",            "\u25C6", "0" },
        { "social", "Reseaux sociaux",   "\u25B2", "0" },
        { "temp",   "Temporaire",        "\u25CC", "1" },
        { "anon",   "Anonyme",           "\u25D1", "1" }
    };

    private static final String PREFS = "geckobrowser";
    private static final String KEY_DEFAULT = "containerDefault";

    private ContainerManager() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int count() {
        return CONTAINERS.length;
    }

    public static String idAt(int index) {
        return valid(index) ? CONTAINERS[index][0] : "";
    }

    public static String nameAt(int index) {
        return valid(index) ? CONTAINERS[index][1] : "Aucune";
    }

    public static String iconAt(int index) {
        return valid(index) ? CONTAINERS[index][2] : "\u25CB";
    }

    /** Temporaire et Anonyme n'ecrivent rien sur le disque. */
    public static boolean isPrivate(int index) {
        return valid(index) && "1".equals(CONTAINERS[index][3]);
    }

    public static boolean isPrivateId(String id) {
        return isPrivate(indexOf(id));
    }

    private static boolean valid(int index) {
        return index >= 0 && index < CONTAINERS.length;
    }

    public static int indexOf(String id) {
        if (id == null) return 0;
        for (int i = 0; i < CONTAINERS.length; i++) {
            if (CONTAINERS[i][0].equals(id)) return i;
        }
        return 0;
    }

    /** Nom lisible d'une identite, pour la liste d'onglets. */
    public static String nameOf(String id) {
        return nameAt(indexOf(id));
    }

    public static String iconOf(String id) {
        return iconAt(indexOf(id));
    }

    public static String defaultId(Context ctx) {
        return prefs(ctx).getString(KEY_DEFAULT, "");
    }

    public static void setDefaultId(Context ctx, String id) {
        prefs(ctx).edit().putString(KEY_DEFAULT, id == null ? "" : id).apply();
    }

    /** Resume affiche dans le menu principal. */
    public static String summary(Context ctx, String currentId) {
        String current = nameOf(currentId);
        String def = nameOf(defaultId(ctx));
        if (currentId == null || currentId.isEmpty()) {
            return "aucune \u00b7 defaut : " + def;
        }
        return current + " \u00b7 defaut : " + def;
    }

    /**
     * Efface les donnees d'une seule identite.
     *
     * `clearDataForSessionContext` ne touche que le conteneur vise : les
     * autres identites et leurs connexions restent intactes.
     */
    public static void clear(GeckoRuntime runtime, String id, Runnable done) {
        try {
            StorageController storage = runtime.getStorageController();
            storage.clearDataForSessionContext(id == null ? "" : id);
        } catch (Throwable ignored) { }
        if (done != null) done.run();
    }

    /**
     * Menu de choix d'une identite.
     *
     * @param includeNone inclure « Aucune » (utile pour choisir un defaut,
     *                    inutile pour dupliquer une page ailleurs)
     * @param skipId      identite a masquer, ou null
     */
    public static void pick(Activity activity, String title, boolean includeNone,
                            String skipId, Runnable back, Chosen chosen) {
        Menus m = new Menus(activity, title);
        for (int i = 0; i < CONTAINERS.length; i++) {
            final int index = i;
            String id = CONTAINERS[i][0];
            if (!includeNone && id.isEmpty()) continue;
            if (skipId != null && skipId.equals(id)) continue;
            m.add(iconAt(i), nameAt(i),
                  isPrivate(i) ? "sans trace sur le disque" : "stockage cloisonne",
                  () -> chosen.on(idAt(index)));
        }
        if (back != null) m.back(back);
        m.show();
    }

    public interface Chosen {
        void on(String id);
    }
}
