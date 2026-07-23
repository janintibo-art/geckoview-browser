package com.example.geckobrowser;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;

/**
 * Routage du trafic via Tor, en s'appuyant sur Orbot (proxy SOCKS local).
 *
 * Gecko n'expose pas d'API de proxy : on passe par un fichier de preferences
 * lu au demarrage du moteur. Les preferences ne pouvant changer qu'a la
 * creation du runtime, activer ou desactiver Tor implique de relancer l'app.
 */
public class TorSupport {

    public static final String ORBOT_PACKAGE = "org.torproject.android";
    private static final String CONFIG_NAME = "gecko-prefs.js";

    private static final String SOCKS_HOST = "127.0.0.1";
    private static final int SOCKS_PORT = 9050;

    // -----------------------------------------------------------------------
    //  Etat
    // -----------------------------------------------------------------------
    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean("tor", false);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("geckobrowser", Context.MODE_PRIVATE);
    }

    public static boolean isOrbotInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(ORBOT_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    //  Fichier de preferences Gecko
    // -----------------------------------------------------------------------
    /**
     * Ecrit le fichier de configuration et retourne son chemin, ou null si
     * l'ecriture echoue. A appeler avant GeckoRuntime.create.
     */
    public static String writeConfig(Context ctx) {
        boolean tor = isEnabled(ctx);
        boolean rfp = prefs(ctx).getBoolean("resistFingerprinting", tor);

        StringBuilder sb = new StringBuilder();
        sb.append("// Configuration generee par GeckoBrowser\n");

        if (tor) {
            sb.append("pref(\"network.proxy.type\", 1);\n");
            sb.append("pref(\"network.proxy.socks\", \"").append(SOCKS_HOST).append("\");\n");
            sb.append("pref(\"network.proxy.socks_port\", ").append(SOCKS_PORT).append(");\n");
            sb.append("pref(\"network.proxy.socks_version\", 5);\n");
            // Resolution DNS cote Tor : sans cela, les requetes DNS fuient.
            sb.append("pref(\"network.proxy.socks_remote_dns\", true);\n");
            sb.append("pref(\"network.proxy.no_proxies_on\", \"\");\n");
            sb.append("pref(\"network.proxy.allow_hijacking_localhost\", true);\n");
            // Autorise la resolution des adresses .onion
            sb.append("pref(\"network.dns.blockDotOnion\", false);\n");
            // Coupe les canaux qui contourneraient le proxy
            sb.append("pref(\"media.peerconnection.enabled\", false);\n");
            sb.append("pref(\"network.dns.disablePrefetch\", true);\n");
            sb.append("pref(\"network.predictor.enabled\", false);\n");
            sb.append("pref(\"network.http.speculative-parallel-limit\", 0);\n");
            sb.append("pref(\"browser.send_pings\", false);\n");
        } else {
            sb.append("pref(\"network.proxy.type\", 0);\n");
            sb.append("pref(\"media.peerconnection.enabled\", true);\n");
        }

        if (rfp) {
            sb.append("pref(\"privacy.resistFingerprinting\", true);\n");
            sb.append("pref(\"privacy.firstparty.isolate\", true);\n");
            sb.append("pref(\"webgl.disabled\", true);\n");
        }

        try {
            File f = new File(ctx.getFilesDir(), CONFIG_NAME);
            FileWriter w = new FileWriter(f, false);
            w.write(sb.toString());
            w.close();
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    //  Orbot
    // -----------------------------------------------------------------------
    public static void startOrbot(Context ctx) {
        try {
            Intent i = new Intent("org.torproject.android.intent.action.START");
            i.setPackage(ORBOT_PACKAGE);
            i.putExtra("org.torproject.android.intent.extra.PACKAGE_NAME", ctx.getPackageName());
            ctx.sendBroadcast(i);
        } catch (Exception ignored) { }

        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ORBOT_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
            }
        } catch (Exception ignored) { }
    }

    public static void offerInstall(final android.app.Activity activity) {
        new AlertDialog.Builder(activity)
            .setTitle("Orbot requis")
            .setMessage("Le routage Tor s'appuie sur Orbot, qui fournit un proxy "
                      + "local sur le port " + SOCKS_PORT + ".\n\n"
                      + "Installez Orbot, lancez-le, puis revenez activer l'option.")
            .setPositiveButton("Ouvrir F-Droid", (d, w) -> {
                open(activity, "https://f-droid.org/packages/" + ORBOT_PACKAGE + "/");
            })
            .setNeutralButton("Play Store", (d, w) -> {
                open(activity, "https://play.google.com/store/apps/details?id=" + ORBOT_PACKAGE);
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private static void open(android.app.Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(a, "Impossible d'ouvrir le lien", Toast.LENGTH_SHORT).show();
        }
    }

    // -----------------------------------------------------------------------
    //  Bascule
    // -----------------------------------------------------------------------
    public static void toggle(final android.app.Activity activity) {
        final boolean on = isEnabled(activity);

        if (!on && !isOrbotInstalled(activity)) {
            offerInstall(activity);
            return;
        }

        String title = on ? "Desactiver Tor ?" : "Activer Tor ?";
        String message = on
            ? "Le trafic repassera par votre connexion habituelle.\n\n"
            + "L'application va redemarrer."
            : "Tout le trafic passera par Orbot (SOCKS " + SOCKS_HOST + ":" + SOCKS_PORT + "), "
            + "avec resolution DNS cote Tor et acces aux adresses .onion.\n\n"
            + "Orbot doit etre lance et connecte. L'application va redemarrer.\n\n"
            + "Ceci donne l'acces au reseau Tor, mais pas les protections "
            + "d'anonymat de Tor Browser.";

        new AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(on ? "Desactiver" : "Activer", (d, w) -> {
                prefs(activity).edit().putBoolean("tor", !on).apply();
                if (!on) startOrbot(activity);
                writeConfig(activity);
                restart(activity);
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    /** Relance le processus : les preferences Gecko ne sont lues qu'au demarrage. */
    public static void restart(android.app.Activity activity) {
        try {
            Intent i = activity.getPackageManager()
                    .getLaunchIntentForPackage(activity.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
            }
        } catch (Exception ignored) { }
        activity.finish();
        Runtime.getRuntime().exit(0);
    }
}
