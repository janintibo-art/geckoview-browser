package com.example.geckobrowser;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileWriter;

/**
 * Genere le fichier de preferences Gecko lu au demarrage du moteur.
 * Regroupe le routage Tor et trois niveaux de durcissement.
 *
 * Rappel : Gecko ne relit ce fichier qu'a la creation du runtime, donc tout
 * changement impose de relancer l'application.
 */
public class Privacy {

    public static final int LEVEL_STANDARD = 0;
    public static final int LEVEL_RENFORCE = 1;
    public static final int LEVEL_STRICT   = 2;

    private static final String CONFIG_NAME = "gecko-prefs.js";

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("geckobrowser", Context.MODE_PRIVATE);
    }

    public static int level(Context ctx) {
        return prefs(ctx).getInt("privacyLevel", LEVEL_RENFORCE);
    }

    public static String levelName(int lvl) {
        switch (lvl) {
            case LEVEL_STRICT:   return "Strict";
            case LEVEL_STANDARD: return "Standard";
            default:             return "Renforce";
        }
    }

    // -----------------------------------------------------------------------
    public static String writeConfig(Context ctx) {
        SharedPreferences p = prefs(ctx);
        boolean tor = p.getBoolean("tor", false);
        int lvl = level(ctx);
        boolean doh = p.getBoolean("doh", false);

        StringBuilder s = new StringBuilder();
        s.append("// Genere par GeckoBrowser — ne pas editer a la main\n");

        // ------------------------------------------------------------------
        //  Fuites reseau : coupees a tous les niveaux
        // ------------------------------------------------------------------
        s.append("pref(\"media.peerconnection.enabled\", ").append(tor || lvl > LEVEL_STANDARD ? "false" : "true").append(");\n");
        s.append("pref(\"network.dns.disablePrefetch\", true);\n");
        s.append("pref(\"network.prefetch-next\", false);\n");
        s.append("pref(\"network.predictor.enabled\", false);\n");
        s.append("pref(\"network.http.speculative-parallel-limit\", 0);\n");
        s.append("pref(\"browser.send_pings\", false);\n");
        s.append("pref(\"beacon.enabled\", false);\n");
        s.append("pref(\"network.IDN_show_punycode\", true);\n");

        // Referent : jamais l'URL complete vers un autre site
        s.append("pref(\"network.http.referer.XOriginPolicy\", 2);\n");
        s.append("pref(\"network.http.referer.XOriginTrimmingPolicy\", 2);\n");

        // Cloisonnement des cookies et du cache par site
        s.append("pref(\"network.cookie.cookieBehavior\", 5);\n");
        s.append("pref(\"privacy.partition.network_state\", true);\n");
        s.append("pref(\"privacy.partition.serviceWorkers\", true);\n");
        s.append("pref(\"privacy.query_stripping.enabled\", true);\n");
        s.append("pref(\"privacy.trackingprotection.enabled\", true);\n");
        s.append("pref(\"privacy.trackingprotection.socialtracking.enabled\", true);\n");

        // Capteurs et API materielles rarement utiles, tres identifiantes
        s.append("pref(\"dom.battery.enabled\", false);\n");
        s.append("pref(\"dom.vibrator.enabled\", false);\n");
        s.append("pref(\"dom.gamepad.enabled\", false);\n");
        s.append("pref(\"device.sensors.enabled\", false);\n");
        s.append("pref(\"geo.enabled\", ").append(lvl == LEVEL_STRICT ? "false" : "true").append(");\n");

        // TLS
        s.append("pref(\"security.tls.version.min\", 3);\n");
        s.append("pref(\"security.ssl.require_safe_negotiation\", true);\n");

        // ------------------------------------------------------------------
        //  Niveau renforce et au-dela
        // ------------------------------------------------------------------
        if (lvl >= LEVEL_RENFORCE) {
            // Uniformise l'empreinte : agent, langue, fuseau, canvas,
            // precision des minuteurs, taille d'ecran.
            s.append("pref(\"privacy.resistFingerprinting\", true);\n");
            s.append("pref(\"privacy.resistFingerprinting.autoDeclineNoUserInputCanvasPrompts\", true);\n");
            s.append("pref(\"privacy.reduceTimerPrecision\", true);\n");
            s.append("pref(\"dom.enable_performance\", false);\n");
            s.append("pref(\"dom.enable_resource_timing\", false);\n");
            s.append("pref(\"media.navigator.enabled\", false);\n");
            s.append("pref(\"media.eme.enabled\", false);\n");
            s.append("pref(\"browser.cache.disk.enable\", false);\n");
            s.append("pref(\"browser.safebrowsing.downloads.remote.enabled\", false);\n");
            s.append("pref(\"extensions.pocket.enabled\", false);\n");
            s.append("pref(\"toolkit.telemetry.enabled\", false);\n");
            s.append("pref(\"datareporting.healthreport.uploadEnabled\", false);\n");
        }

        // ------------------------------------------------------------------
        //  Niveau strict : casse davantage de sites, assume-le
        // ------------------------------------------------------------------
        if (lvl >= LEVEL_STRICT) {
            // Bordures grises autour du contenu pour normaliser la taille
            s.append("pref(\"privacy.resistFingerprinting.letterboxing\", true);\n");
            s.append("pref(\"webgl.disabled\", true);\n");
            s.append("pref(\"dom.webaudio.enabled\", false);\n");
            s.append("pref(\"media.webspeech.synth.enabled\", false);\n");
            s.append("pref(\"gfx.downloadable_fonts.enabled\", false);\n");
            s.append("pref(\"browser.display.use_document_fonts\", 0);\n");
            s.append("pref(\"javascript.options.asmjs\", false);\n");
            s.append("pref(\"javascript.options.wasm\", false);\n");
            s.append("pref(\"dom.w3c_touch_events.enabled\", 0);\n");
            s.append("pref(\"network.http.sendRefererHeader\", 0);\n");
        }

        // ------------------------------------------------------------------
        //  DNS chiffre (sans objet sous Tor, qui resout deja a distance)
        // ------------------------------------------------------------------
        if (doh && !tor) {
            s.append("pref(\"network.trr.mode\", 3);\n");
            s.append("pref(\"network.trr.uri\", \"")
             .append(p.getString("dohUri", "https://dns.quad9.net/dns-query"))
             .append("\");\n");
            s.append("pref(\"network.trr.bootstrapAddress\", \"9.9.9.9\");\n");
        } else if (!tor) {
            s.append("pref(\"network.trr.mode\", 0);\n");
        }

        // ------------------------------------------------------------------
        //  Tor
        // ------------------------------------------------------------------
        if (tor) {
            s.append("pref(\"network.proxy.type\", 1);\n");
            s.append("pref(\"network.proxy.socks\", \"127.0.0.1\");\n");
            s.append("pref(\"network.proxy.socks_port\", 9050);\n");
            s.append("pref(\"network.proxy.socks_version\", 5);\n");
            s.append("pref(\"network.proxy.socks_remote_dns\", true);\n");
            s.append("pref(\"network.proxy.no_proxies_on\", \"\");\n");
            s.append("pref(\"network.proxy.allow_hijacking_localhost\", true);\n");
            s.append("pref(\"network.dns.blockDotOnion\", false);\n");
            s.append("pref(\"network.trr.mode\", 0);\n");
        } else {
            s.append("pref(\"network.proxy.type\", 0);\n");
        }

        try {
            File f = new File(ctx.getFilesDir(), CONFIG_NAME);
            FileWriter w = new FileWriter(f, false);
            w.write(s.toString());
            w.close();
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    /** Ce que le niveau choisi casse en pratique. */
    public static String sideEffects(int lvl) {
        if (lvl == LEVEL_STANDARD) {
            return "Aucune casse attendue. Protection contre le pistage, "
                 + "mais empreinte de navigateur distinctive.";
        }
        if (lvl == LEVEL_RENFORCE) {
            return "Bon compromis. Effets possibles : fuseau horaire affiche en UTC, "
                 + "langue signalee en anglais, canvas et micro/camera restreints, "
                 + "contenus proteges (Netflix…) indisponibles, cache disque desactive.";
        }
        return "Protection maximale, casse assumee. En plus du niveau renforce : "
             + "bandes grises autour des pages, WebGL et WebAudio coupes, "
             + "polices du site ignorees, WebAssembly desactive, aucun referent envoye. "
             + "Certaines applications web ne fonctionneront pas.";
    }
}
