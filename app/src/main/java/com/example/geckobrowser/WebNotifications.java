package com.example.geckobrowser;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.WebNotification;
import org.mozilla.geckoview.WebNotificationDelegate;

import java.util.HashSet;
import java.util.Set;

/**
 * WEB_NOTIFICATIONS_V1 — notifications envoyees par les sites.
 *
 * Sans ce delegue, une page qui appelle Notification() ne produit rien :
 * le moteur previent l'application, et c'est a elle d'afficher quelque
 * chose. Les notifications passent donc par le systeme Android, avec leur
 * propre canal, ce qui permet a l'utilisateur de les couper depuis les
 * reglages du telephone comme depuis le navigateur.
 *
 * Choix de conception, coherents avec le reste du projet :
 *   - la permission reste demandee par site (voir Permissions) ;
 *   - un interrupteur general coupe tout d'un coup ;
 *   - un site peut etre mis en sourdine sans lui retirer sa permission ;
 *   - toucher une notification ouvre la page qui l'a produite.
 */
public final class WebNotifications implements WebNotificationDelegate {

    private static final String PREFS = "geckobrowser";
    private static final String KEY_ENABLED = "webNotifications";
    private static final String KEY_MUTED = "webNotificationsMuted";
    private static final String CHANNEL = "sites";
    public static final String EXTRA_URL = "com.example.geckobrowser.NOTIF_URL";

    private final Context app;
    private final NotificationManager manager;
    private int nextId = 7100;

    public WebNotifications(Context context) {
        this.app = context.getApplicationContext();
        this.manager = (NotificationManager)
                app.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    private void createChannel() {
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Notifications des sites",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Messages envoyes par les sites que vous autorisez");
        manager.createNotificationChannel(channel);
    }

    // -----------------------------------------------------------------------
    //  Reglages
    // -----------------------------------------------------------------------
    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean value) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply();
    }

    private static Set<String> muted(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_MUTED, new HashSet<>()));
    }

    public static boolean isMuted(Context ctx, String host) {
        return host != null && muted(ctx).contains(host);
    }

    public static void mute(Context ctx, String host, boolean value) {
        Set<String> set = muted(ctx);
        if (value) set.add(host);
        else set.remove(host);
        prefs(ctx).edit().putStringSet(KEY_MUTED, set).apply();
    }

    public static String summary(Context ctx) {
        if (!enabled(ctx)) return "coupees";
        int n = muted(ctx).size();
        return n == 0 ? "autorisees par site" : n + " site(s) en sourdine";
    }

    static String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h.replaceFirst("^www\\.", "");
        } catch (Throwable e) {
            return "";
        }
    }

    // -----------------------------------------------------------------------
    //  Delegue GeckoView
    // -----------------------------------------------------------------------
    @Override
    public void onShowNotification(WebNotification notification) {
        if (manager == null || notification == null) return;
        if (!enabled(app)) return;

        String host = hostOf(notification.source);
        if (isMuted(app, host)) return;

        String title = notification.title == null || notification.title.isEmpty()
                ? host : notification.title;
        String text = notification.text == null ? "" : notification.text;

        Intent open = new Intent(app, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (notification.source != null) open.putExtra(EXTRA_URL, notification.source);

        PendingIntent pi = PendingIntent.getActivity(app, nextId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification.Builder b =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new android.app.Notification.Builder(app, CHANNEL)
                        : new android.app.Notification.Builder(app);
        b.setContentTitle(title)
         .setContentText(text)
         .setSubText(host)
         .setSmallIcon(android.R.drawable.ic_dialog_info)
         .setAutoCancel(true)
         .setContentIntent(pi);

        try {
            manager.notify(nextId++, b.build());
        } catch (Throwable ignored) { }
    }

    @Override
    public void onCloseNotification(WebNotification notification) {
        // Les notifications sont a effacement automatique : rien a faire.
    }

    // -----------------------------------------------------------------------
    //  Menu
    // -----------------------------------------------------------------------
    public static void show(Activity activity, String currentHost, Runnable back) {
        final boolean on = enabled(activity);
        Menus m = new Menus(activity, "Notifications des sites");

        m.add(on ? "\u25C9" : "\u25CB", "Notifications des sites",
              on ? "autorisees au cas par cas" : "toutes coupees", () -> {
                  setEnabled(activity, !on);
                  show(activity, currentHost, back);
              });

        if (on && currentHost != null && !currentHost.isEmpty()) {
            final boolean m2 = isMuted(activity, currentHost);
            m.add(m2 ? "\u25CB" : "\u25C9", currentHost,
                  m2 ? "en sourdine" : "notifications acceptees", () -> {
                      mute(activity, currentHost, !m2);
                      show(activity, currentHost, back);
                  });
        }

        Set<String> mutedSites = muted(activity);
        if (!mutedSites.isEmpty()) {
            m.add("\u2327", "Reactiver tous les sites en sourdine",
                  mutedSites.size() + " site(s)", () -> {
                      prefs(activity).edit().remove(KEY_MUTED).apply();
                      Toast.makeText(activity, "Sourdines levees",
                              Toast.LENGTH_SHORT).show();
                      show(activity, currentHost, back);
                  });
        }

        m.add("\u24D8", "Fonctionnement", () -> Menus.info(activity,
            "Notifications des sites",
            "Un site doit d'abord vous demander l'autorisation ; elle est "
            + "refusee tant que vous ne l'accordez pas.\n\n"
            + "Les notifications passent par le systeme Android, dans leur "
            + "propre canal : vous pouvez aussi les regler depuis les "
            + "parametres du telephone.\n\n"
            + "« En sourdine » garde l'autorisation du site mais n'affiche "
            + "plus rien. Toucher une notification ouvre la page qui l'a "
            + "produite."));

        m.back(back).show();
    }
}
