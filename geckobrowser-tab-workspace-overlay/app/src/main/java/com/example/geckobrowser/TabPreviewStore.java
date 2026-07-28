package com.example.geckobrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Petit cache prive des apercus d'onglets.
 *
 * Les images restent dans le dossier interne de l'application. Les onglets
 * prives ne doivent jamais appeler save().
 */
public final class TabPreviewStore {

    private static final String DIR = "tab-previews";
    private static final int MAX_WIDTH = 720;
    private static final int MAX_HEIGHT = 480;

    private TabPreviewStore() { }

    private static File dir(Context context) {
        File out = new File(context.getFilesDir(), DIR);
        if (!out.exists()) out.mkdirs();
        return out;
    }

    private static File file(Context context, String id) {
        String safe = id == null ? "unknown" : id.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(dir(context), safe + ".jpg");
    }

    public static void save(Context context, String id, Bitmap source) {
        if (context == null || id == null || id.isEmpty() || source == null) return;

        Bitmap output = source;
        try {
            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= 0 || height <= 0) return;

            float scale = Math.min(1f, Math.min(
                    MAX_WIDTH / (float) width,
                    MAX_HEIGHT / (float) height));
            if (scale < 1f) {
                output = Bitmap.createScaledBitmap(
                        source,
                        Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)),
                        true);
            }

            File target = file(context, id);
            File temp = new File(target.getParentFile(), target.getName() + ".tmp");
            try (FileOutputStream stream = new FileOutputStream(temp)) {
                output.compress(Bitmap.CompressFormat.JPEG, 82, stream);
                stream.flush();
            }
            if (!temp.renameTo(target)) {
                try (FileOutputStream stream = new FileOutputStream(target)) {
                    output.compress(Bitmap.CompressFormat.JPEG, 82, stream);
                }
                temp.delete();
            }
        } catch (Throwable ignored) {
        } finally {
            if (output != source && output != null && !output.isRecycled()) {
                output.recycle();
            }
        }
    }

    public static Bitmap load(Context context, String id) {
        try {
            File target = file(context, id);
            if (!target.isFile()) return null;
            return BitmapFactory.decodeFile(target.getAbsolutePath());
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void delete(Context context, String id) {
        try { file(context, id).delete(); }
        catch (Throwable ignored) { }
    }

    /** Efface les anciens apercus qui ne correspondent plus a aucun onglet. */
    public static void cleanup(Context context, Set<String> validIds) {
        try {
            Set<String> valid = validIds == null ? new HashSet<>() : validIds;
            File[] files = dir(context).listFiles();
            if (files == null) return;
            for (File f : files) {
                String name = f.getName();
                if (!name.endsWith(".jpg")) continue;
                String id = name.substring(0, name.length() - 4);
                if (!valid.contains(id)) f.delete();
            }
        } catch (Throwable ignored) { }
    }
}
