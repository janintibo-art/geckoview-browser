package com.example.geckobrowser;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * MEDIA_QUEUE_V1 — file d'attente de lecture.
 *
 * Permet d'enchainer plusieurs videos, y compris de sites differents et
 * ecran eteint : quand une video se termine (evenement « ended » remonte
 * par player.js), l'application charge automatiquement la suivante dans
 * l'onglet courant.
 *
 * Ce n'est pas un lecteur de fond au sens strict (GeckoView a besoin d'un
 * onglet pour lire), mais l'enchainement + la notification media + le PiP
 * existants donnent l'usage « liste de lecture » attendu.
 *
 * La file est persistee : elle survit a un redemarrage.
 */
public final class MediaQueue {

    private static final String PREFS = "geckobrowser";
    private static final String KEY = "mediaQueue";
    private static final int MAX = 100;

    private MediaQueue() { }

    public static final class Item {
        public final String url;
        public final String title;
        Item(String url, String title) {
            this.url = url;
            this.title = title == null ? "" : title;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Item> all(Context ctx) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("url", "");
                if (!u.isEmpty()) out.add(new Item(u, o.optString("title", "")));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static void save(Context ctx, List<Item> items) {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            try {
                JSONObject o = new JSONObject();
                o.put("url", it.url);
                o.put("title", it.title);
                arr.put(o);
            } catch (Throwable ignored) { }
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply();
    }

    public static int size(Context ctx) {
        return all(ctx).size();
    }

    public static String summary(Context ctx) {
        int n = size(ctx);
        return n == 0 ? "vide" : n + " video(s) en attente";
    }

    /** Ajoute une video en fin de file (sauf doublon d'URL). */
    public static boolean add(Context ctx, String url, String title) {
        if (url == null || url.isEmpty()) return false;
        List<Item> items = all(ctx);
        for (Item it : items) if (it.url.equals(url)) return false;
        if (items.size() >= MAX) return false;
        items.add(new Item(url, title));
        save(ctx, items);
        return true;
    }

    /** Retire et renvoie la premiere video de la file, ou null si vide. */
    public static Item poll(Context ctx) {
        List<Item> items = all(ctx);
        if (items.isEmpty()) return null;
        Item first = items.remove(0);
        save(ctx, items);
        return first;
    }

    public static Item peek(Context ctx) {
        List<Item> items = all(ctx);
        return items.isEmpty() ? null : items.get(0);
    }

    public static void removeAt(Context ctx, int index) {
        List<Item> items = all(ctx);
        if (index >= 0 && index < items.size()) {
            items.remove(index);
            save(ctx, items);
        }
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY).apply();
    }

    // -----------------------------------------------------------------------
    //  Menu
    // -----------------------------------------------------------------------
    public static void show(Activity activity, Runnable back, PlayRequest onPlay) {
        List<Item> items = all(activity);
        Menus m = new Menus(activity, "File d'attente");

        if (items.isEmpty()) {
            m.add("\u25CB", "File vide",
                  "ajoutez des videos depuis « Telecharger la video »", () -> { });
            m.back(back).show();
            return;
        }

        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            final Item it = items.get(i);
            String label = it.title.isEmpty() ? shortUrl(it.url) : it.title;
            m.add(i == 0 ? "\u25B6" : "\u25CB", label,
                  i == 0 ? "suivante" : shortUrl(it.url), () -> {
                      // Jouer maintenant : on retire les elements avant celui-ci.
                      for (int k = 0; k < index; k++) poll(activity);
                      Item next = poll(activity);
                      if (next != null && onPlay != null) onPlay.play(next);
                  });
        }

        m.add("\u2327", "Vider la file", items.size() + " video(s)", () -> {
            clear(activity);
            Toast.makeText(activity, "File videe", Toast.LENGTH_SHORT).show();
            if (back != null) back.run();
        });

        m.back(back).show();
    }

    private static String shortUrl(String url) {
        String u = url.replaceFirst("^https?://(www\\.)?", "");
        return u.length() > 50 ? u.substring(0, 50) + "\u2026" : u;
    }

    public interface PlayRequest {
        void play(Item item);
    }
}
