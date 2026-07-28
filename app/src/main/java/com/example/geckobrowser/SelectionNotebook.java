package com.example.geckobrowser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Stockage local et actions utilitaires de la barre intelligente. */
public final class SelectionNotebook {

    private static final String PREFS = "geckobrowser";
    private static final String KEY = "smartQuotes";
    private static final int LIMIT = 100;
    private static TextToSpeech speech;

    private SelectionNotebook() { }

    private static JSONArray read(Context context) {
        try {
            return new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void write(Context context, JSONArray array) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, array.toString()).apply();
    }

    public static int count(Context context) {
        return read(context).length();
    }

    public static void save(Context context, String text, String title, String url) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) return;
        if (clean.length() > 12000) clean = clean.substring(0, 12000);

        try {
            JSONObject quote = new JSONObject();
            quote.put("text", clean);
            quote.put("title", title == null ? "" : title);
            quote.put("url", url == null ? "" : url);
            quote.put("at", System.currentTimeMillis());

            JSONArray old = read(context);
            JSONArray out = new JSONArray();
            out.put(quote);
            for (int i = 0; i < old.length() && out.length() < LIMIT; i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null) out.put(item);
            }
            write(context, out);
        } catch (Exception ignored) { }
    }

    public static void show(Activity activity) {
        JSONArray quotes = read(activity);
        if (quotes.length() == 0) {
            Toast.makeText(activity, "Aucune citation enregistree", Toast.LENGTH_SHORT).show();
            return;
        }

        Menus menu = new Menus(activity, quotes.length() + " citation(s)");
        for (int i = 0; i < quotes.length(); i++) {
            final int index = i;
            JSONObject quote = quotes.optJSONObject(i);
            if (quote == null) continue;
            String text = quote.optString("text", "");
            String preview = text.replace('\n', ' ').trim();
            if (preview.length() > 72) preview = preview.substring(0, 72) + "…";
            menu.add("\u275D", preview, sourceLabel(quote),
                    () -> showQuote(activity, index));
        }

        menu.add("\u29C9", "Copier toutes les citations",
                () -> copy(activity, exportText(quotes, false)));
        menu.add("\u21AA", "Partager toutes les citations",
                () -> share(activity, exportText(quotes, false)));
        menu.add("\u2327", "Tout effacer", () -> confirmClear(activity));
        menu.show();
    }

    private static void showQuote(Activity activity, int index) {
        JSONArray quotes = read(activity);
        JSONObject quote = quotes.optJSONObject(index);
        if (quote == null) return;

        String text = quote.optString("text", "");
        String title = quote.optString("title", "");
        String url = quote.optString("url", "");
        StringBuilder messageBuilder = new StringBuilder(text);
        if (!title.isEmpty()) messageBuilder.append("\n\n— ").append(title);
        if (!url.isEmpty()) messageBuilder.append("\n").append(url);
        final String message = messageBuilder.toString();

        Menus.dialog(activity)
                .setTitle("Citation")
                .setMessage(message)
                .setPositiveButton("Copier", (d, w) -> copy(activity, message))
                .setNeutralButton("Partager", (d, w) -> share(activity, message))
                .setNegativeButton("Supprimer", (d, w) -> delete(activity, index))
                .show();
    }

    private static void delete(Activity activity, int index) {
        JSONArray old = read(activity);
        JSONArray out = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            if (i == index) continue;
            JSONObject item = old.optJSONObject(i);
            if (item != null) out.put(item);
        }
        write(activity, out);
        Toast.makeText(activity, "Citation supprimee", Toast.LENGTH_SHORT).show();
    }

    private static void confirmClear(Activity activity) {
        Menus.dialog(activity)
                .setTitle("Effacer toutes les citations ?")
                .setMessage("Cette action est definitive.")
                .setPositiveButton("Effacer", (d, w) -> {
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().remove(KEY).apply();
                    Toast.makeText(activity, "Citations effacees", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private static String sourceLabel(JSONObject quote) {
        String url = quote.optString("url", "");
        String host = "source inconnue";
        try {
            String parsed = Uri.parse(url).getHost();
            if (parsed != null && !parsed.isEmpty()) host = parsed.replaceFirst("^www\\.", "");
        } catch (Exception ignored) { }

        long at = quote.optLong("at", 0L);
        if (at > 0L) {
            String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(at));
            return host + " · " + date;
        }
        return host;
    }

    private static String exportText(JSONArray quotes, boolean markdown) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < quotes.length(); i++) {
            JSONObject q = quotes.optJSONObject(i);
            if (q == null) continue;
            if (out.length() > 0) out.append("\n\n");
            String text = q.optString("text", "");
            String title = q.optString("title", "");
            String url = q.optString("url", "");
            out.append(markdown ? markdown(text, title, url) : plain(text, title, url));
        }
        return out.toString();
    }

    private static String plain(String text, String title, String url) {
        StringBuilder out = new StringBuilder(text == null ? "" : text);
        if (title != null && !title.isEmpty()) out.append("\n— ").append(title);
        if (url != null && !url.isEmpty()) out.append("\n").append(url);
        return out.toString();
    }

    private static String markdown(String text, String title, String url) {
        String clean = text == null ? "" : text.trim();
        StringBuilder out = new StringBuilder();
        for (String line : clean.split("\\R", -1)) {
            out.append("> ").append(line).append('\n');
        }
        if (title != null && !title.isEmpty()) {
            out.append("\n— ");
            if (url != null && !url.isEmpty()) {
                out.append('[').append(title.replace("]", "\\]")).append("](")
                        .append(url).append(')');
            } else {
                out.append(title);
            }
        } else if (url != null && !url.isEmpty()) {
            out.append("\n— ").append(url);
        }
        return out.toString().trim();
    }

    public static void copyMarkdown(Activity activity, String text, String title, String url) {
        copy(activity, markdown(text, title, url));
    }

    private static void copy(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("GeckoBrowser", text));
        }
    }

    public static void share(Activity activity, String text) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        try {
            activity.startActivity(Intent.createChooser(intent, "Partager le texte"));
        } catch (Exception ignored) {
            Toast.makeText(activity, "Aucune application de partage", Toast.LENGTH_SHORT).show();
        }
    }

    public static void speak(Activity activity, String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) return;
        int max = TextToSpeech.getMaxSpeechInputLength();
        if (clean.length() > max) clean = clean.substring(0, max);
        final String spoken = clean;

        if (speech != null) {
            try { speech.stop(); speech.shutdown(); } catch (Throwable ignored) { }
            speech = null;
        }

        final TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(activity.getApplicationContext(), status -> {
            TextToSpeech engine = holder[0];
            if (engine == null || status != TextToSpeech.SUCCESS) {
                Toast.makeText(activity, "Synthese vocale indisponible", Toast.LENGTH_SHORT).show();
                return;
            }
            engine.setLanguage(Locale.getDefault());
            engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "geckobrowser-selection");
        });
        speech = holder[0];
    }
}
