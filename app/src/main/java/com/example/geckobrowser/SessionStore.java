package com.example.geckobrowser;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Stockage prive et atomique de l'etat complet des onglets. */
final class SessionStore {
    private static final String FILE_NAME = "session-v2.json";
    private static final String TEMP_NAME = "session-v2.tmp";

    static final class Snapshot {
        final JSONArray tabs;
        final int active;

        Snapshot(JSONArray tabs, int active) {
            this.tabs = tabs;
            this.active = active;
        }
    }

    private SessionStore() { }

    static boolean write(Context context, JSONArray tabs, int active) {
        File dir = context.getFilesDir();
        File target = new File(dir, FILE_NAME);
        File temp = new File(dir, TEMP_NAME);
        try {
            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("active", active);
            root.put("savedAt", System.currentTimeMillis());
            root.put("tabs", tabs);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);

            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }

            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicMoveUnavailable) {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            temp.delete();
            return false;
        }
    }

    static Snapshot read(Context context) {
        File source = new File(context.getFilesDir(), FILE_NAME);
        if (!source.isFile()) return null;
        try (FileInputStream in = new FileInputStream(source);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            JSONObject root = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            if (root.optInt("version", 0) != 2) return null;
            JSONArray tabs = root.optJSONArray("tabs");
            if (tabs == null) tabs = new JSONArray();
            return new Snapshot(tabs, root.optInt("active", -1));
        } catch (Exception e) {
            // Un fichier corrompu ne doit jamais empecher le navigateur de demarrer.
            source.delete();
            return null;
        }
    }

    static void clear(Context context) {
        new File(context.getFilesDir(), FILE_NAME).delete();
        new File(context.getFilesDir(), TEMP_NAME).delete();
    }
}
