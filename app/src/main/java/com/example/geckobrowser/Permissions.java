package com.example.geckobrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

/**
 * Permissions demandees par les pages (position, camera, micro, notifications)
 * et permissions Android correspondantes. Sans ce delegue, ces demandes
 * echouent sans que rien ne s'affiche.
 */
public class Permissions implements GeckoSession.PermissionDelegate {

    private static final int REQ_CODE = 4711;

    private final Activity activity;
    private Callback pendingAndroid;

    public Permissions(Activity activity) {
        this.activity = activity;
    }

    private String label(int type) {
        switch (type) {
            case PERMISSION_GEOLOCATION:           return "connaitre votre position";
            case PERMISSION_DESKTOP_NOTIFICATION:  return "vous envoyer des notifications";
            case PERMISSION_PERSISTENT_STORAGE:    return "stocker des donnees durablement";
            case PERMISSION_XR:                    return "acceder a la realite virtuelle";
            case PERMISSION_MEDIA_KEY_SYSTEM_ACCESS:
                                                   return "lire des contenus proteges";
            default:                               return "obtenir une autorisation";
        }
    }

    // -----------------------------------------------------------------------
    //  Permissions demandees par la page
    // -----------------------------------------------------------------------
    @Override
    public GeckoResult<Integer> onContentPermissionRequest(GeckoSession session,
                                                           ContentPermission perm) {
        // Lecture protegee et autoplay silencieux : pas de question inutile.
        if (perm.permission == PERMISSION_MEDIA_KEY_SYSTEM_ACCESS
                || perm.permission == PERMISSION_AUTOPLAY_INAUDIBLE) {
            return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
        }
        if (perm.permission == PERMISSION_AUTOPLAY_AUDIBLE
                || perm.permission == PERMISSION_TRACKING) {
            return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
        }

        final GeckoResult<Integer> res = new GeckoResult<>();
        String host = perm.uri;
        try {
            host = android.net.Uri.parse(perm.uri).getHost();
        } catch (Exception ignored) { }

        new AlertDialog.Builder(activity)
            .setTitle("Autorisation")
            .setMessage(host + "\n\nsouhaite " + label(perm.permission) + ".")
            .setPositiveButton("Autoriser", (d, w) -> res.complete(ContentPermission.VALUE_ALLOW))
            .setNegativeButton("Refuser", (d, w) -> res.complete(ContentPermission.VALUE_DENY))
            .setOnCancelListener(d -> res.complete(ContentPermission.VALUE_DENY))
            .show();
        return res;
    }

    // -----------------------------------------------------------------------
    //  Permissions Android sous-jacentes
    // -----------------------------------------------------------------------
    @Override
    public void onAndroidPermissionsRequest(GeckoSession session, String[] permissions,
                                            Callback callback) {
        if (permissions == null || permissions.length == 0) {
            callback.grant();
            return;
        }

        boolean allGranted = true;
        for (String p : permissions) {
            if (activity.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            callback.grant();
            return;
        }

        pendingAndroid = callback;
        activity.requestPermissions(permissions, REQ_CODE);
    }

    /** A appeler depuis Activity.onRequestPermissionsResult. */
    public void onAndroidResult(int requestCode, int[] grantResults) {
        if (requestCode != REQ_CODE || pendingAndroid == null) return;
        boolean ok = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
        }
        if (ok) pendingAndroid.grant(); else pendingAndroid.reject();
        pendingAndroid = null;
    }

    // -----------------------------------------------------------------------
    //  Camera et micro
    // -----------------------------------------------------------------------
    @Override
    public void onMediaPermissionRequest(GeckoSession session, String uri,
                                         MediaSource[] video, MediaSource[] audio,
                                         MediaCallback callback) {
        String what;
        if (video != null && audio != null) what = "la camera et le micro";
        else if (video != null)             what = "la camera";
        else if (audio != null)             what = "le micro";
        else { callback.reject(); return; }

        String host = uri;
        try { host = android.net.Uri.parse(uri).getHost(); } catch (Exception ignored) { }

        new AlertDialog.Builder(activity)
            .setTitle("Autorisation")
            .setMessage(host + "\n\nsouhaite utiliser " + what + ".")
            .setPositiveButton("Autoriser", (d, w) -> callback.grant(
                    video != null ? video[0] : null,
                    audio != null ? audio[0] : null))
            .setNegativeButton("Refuser", (d, w) -> callback.reject())
            .setOnCancelListener(d -> callback.reject())
            .show();
    }
}
