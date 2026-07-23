package com.example.geckobrowser;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.widget.Toast;

/**
 * Epingle un site sur l'ecran d'accueil du telephone.
 *
 * L'icone est dessinee localement — pastille coloree et initiale — plutot que
 * recuperee sur le site. Aller chercher une favicon signalerait la creation du
 * raccourci au serveur concerne, ce qui n'a pas lieu d'etre.
 */
public class Shortcuts {

    private static final int[] PALETTE = {
        0xFF6FAE5F, 0xFF8AB4F8, 0xFFD9C07C, 0xFFD97757, 0xFFA78BD0,
        0xFF5FB0AE, 0xFFC98FB0, 0xFF8FB36F, 0xFF7F9EDE, 0xFFD0A05F
    };

    private static int colorFor(String seed) {
        int h = 0;
        for (int i = 0; i < seed.length(); i++) {
            h = h * 31 + seed.charAt(i);
        }
        return PALETTE[Math.abs(h) % PALETTE.length];
    }

    private static String letterFor(String label, String host) {
        String source = (label == null || label.trim().isEmpty()) ? host : label.trim();
        if (source == null || source.isEmpty()) return "?";
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return source.substring(0, 1);
    }

    // -----------------------------------------------------------------------
    /** Pastille arrondie avec l'initiale, au format attendu par les lanceurs. */
    private static Bitmap drawIcon(String letter, int color) {
        final int size = 192;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        // Le systeme rogne les icones adaptatives : le dessin reste au centre
        float inset = size * 0.17f;
        RectF box = new RectF(inset, inset, size - inset, size - inset);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(color);
        c.drawRoundRect(box, size * 0.22f, size * 0.22f, bg);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.parseColor("#10130F"));
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setTextSize(size * 0.38f);

        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = box.centerY() - (fm.ascent + fm.descent) / 2f;
        c.drawText(letter, box.centerX(), baseline, text);

        return bmp;
    }

    // -----------------------------------------------------------------------
    public static void pin(Context ctx, String url, String label) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(ctx, "Aucune adresse a epingler", Toast.LENGTH_SHORT).show();
            return;
        }

        String host = url;
        try {
            String h = Uri.parse(url).getHost();
            if (h != null) host = h.replaceFirst("^www\\.", "");
        } catch (Exception ignored) { }

        String name = (label == null || label.trim().isEmpty()) ? host : label.trim();
        if (name.length() > 24) name = name.substring(0, 24);

        ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(ctx,
                    "Votre lanceur n'accepte pas l'ajout automatique de raccourcis",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.setAction(Intent.ACTION_VIEW);
        open.setData(Uri.parse(url));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        Bitmap bmp = drawIcon(letterFor(label, host), colorFor(url));

        ShortcutInfo info = new ShortcutInfo.Builder(ctx, "site_" + url.hashCode())
                .setShortLabel(name)
                .setLongLabel(name)
                .setIcon(Icon.createWithAdaptiveBitmap(bmp))
                .setIntent(open)
                .build();

        try {
            // Le systeme demande confirmation : rien n'est ajoute sans accord.
            PendingIntent back = PendingIntent.getBroadcast(
                    ctx, 0, new Intent("com.example.geckobrowser.PINNED"),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            sm.requestPinShortcut(info, back.getIntentSender());
        } catch (Exception e) {
            Toast.makeText(ctx, "Ajout refuse : " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }
}
