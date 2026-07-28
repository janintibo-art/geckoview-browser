package com.example.geckobrowser;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.text.format.DateFormat;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Centre natif des telechargements GeckoBrowser.
 *
 * Les telechargements HTTP ordinaires sont confies au DownloadManager Android :
 * ils continuent en arriere-plan, supportent les coupures de reseau et gardent
 * une notification systeme. Les telechargements via Tor restent traites par
 * Downloads.java afin de conserver le proxy SOCKS d'Orbot.
 */
public final class DownloadCenter {

    private static final String PREFS = "geckobrowser_downloads";
    private static final String KEY_RECORDS = "records";
    private static final String KEY_WIFI_ONLY = "wifiOnly";
    private static final int MAX_RECORDS = 80;
    private static final int STATUS_CANCELLED = 900;
    private static final Object LOCK = new Object();

    private DownloadCenter() { }

    private static final class Record {
        long id;
        String url = "";
        String referer = "";
        String name = "telechargement";
        String mime = "application/octet-stream";
        String localUri = "";
        long created = System.currentTimeMillis();
        long updated = created;
        long bytes = 0;
        long total = -1;
        int status = DownloadManager.STATUS_PENDING;
        int reason = 0;
        String error = "";
        boolean tor = false;

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("url", url);
            o.put("referer", referer);
            o.put("name", name);
            o.put("mime", mime);
            o.put("local", localUri);
            o.put("created", created);
            o.put("updated", updated);
            o.put("bytes", bytes);
            o.put("total", total);
            o.put("status", status);
            o.put("reason", reason);
            o.put("error", error);
            o.put("tor", tor);
            return o;
        }

        static Record from(JSONObject o) {
            Record r = new Record();
            r.id = o.optLong("id", 0);
            r.url = o.optString("url", "");
            r.referer = o.optString("referer", "");
            r.name = o.optString("name", "telechargement");
            r.mime = o.optString("mime", "application/octet-stream");
            r.localUri = o.optString("local", "");
            r.created = o.optLong("created", System.currentTimeMillis());
            r.updated = o.optLong("updated", r.created);
            r.bytes = o.optLong("bytes", 0);
            r.total = o.optLong("total", -1);
            r.status = o.optInt("status", DownloadManager.STATUS_PENDING);
            r.reason = o.optInt("reason", 0);
            r.error = o.optString("error", "");
            r.tor = o.optBoolean("tor", false);
            return r;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean wifiOnly(Context context) {
        return prefs(context).getBoolean(KEY_WIFI_ONLY, false);
    }

    private static void setWifiOnly(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY, enabled).apply();
    }

    /** Ajoute plusieurs URL au gestionnaire systeme. */
    public static int enqueueUrls(Context context, String[] urls, String referer) {
        if (urls == null) return 0;
        int queued = 0;
        for (String url : urls) {
            if (enqueueUrl(context, url, referer, null, null) > 0) queued++;
        }
        return queued;
    }

    /** Ajoute une URL au DownloadManager et l'inscrit dans l'historique local. */
    public static long enqueueUrl(Context context, String url, String referer,
                                  String suggestedName, String mime) {
        if (context == null || url == null
                || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return -1;
        }

        try {
            String name = cleanName(suggestedName);
            if (name.isEmpty()) name = deriveName(url, mime);
            if (mime == null || mime.trim().isEmpty()) mime = guessMime(name);

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(name);
            request.setDescription(host(url));
            request.setMimeType(mime);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverRoaming(false);
            request.setAllowedOverMetered(!wifiOnly(context));
            if (wifiOnly(context)) {
                request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
            }
            request.addRequestHeader("User-Agent",
                    "Mozilla/5.0 (Android 14; Mobile; rv:151.0) Gecko/20100101 Firefox/151.0");
            if (referer != null && !referer.isEmpty()) {
                request.addRequestHeader("Referer", referer);
            }
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "GeckoBrowser/" + name);

            DownloadManager manager = (DownloadManager)
                    context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) return -1;
            long id = manager.enqueue(request);

            Record record = new Record();
            record.id = id;
            record.url = url;
            record.referer = referer == null ? "" : referer;
            record.name = name;
            record.mime = mime;
            record.status = DownloadManager.STATUS_PENDING;
            addRecord(context, record);
            return id;
        } catch (Throwable e) {
            recordDirectFailed(context, cleanName(suggestedName), url,
                    e.getMessage() == null ? "erreur" : e.getMessage(), false);
            return -1;
        }
    }

    /** Inscrit un fichier ecrit directement par Gecko ou par le chemin Tor. */
    public static void recordDirectCompleted(Context context, String name, String mime,
                                             long bytes, String localUri,
                                             String sourceUrl, boolean tor) {
        Record r = new Record();
        r.id = -Math.max(1, System.nanoTime());
        r.name = cleanName(name);
        if (r.name.isEmpty()) r.name = deriveName(sourceUrl, mime);
        r.mime = mime == null || mime.isEmpty() ? guessMime(r.name) : mime;
        r.bytes = Math.max(0, bytes);
        r.total = bytes;
        r.localUri = localUri == null ? "" : localUri;
        r.url = sourceUrl == null ? "" : sourceUrl;
        r.tor = tor;
        r.status = DownloadManager.STATUS_SUCCESSFUL;
        addRecord(context, r);
    }

    public static void recordDirectFailed(Context context, String name, String sourceUrl,
                                          String reason, boolean tor) {
        Record r = new Record();
        r.id = -Math.max(1, System.nanoTime());
        r.name = cleanName(name);
        if (r.name.isEmpty()) r.name = deriveName(sourceUrl, null);
        r.url = sourceUrl == null ? "" : sourceUrl;
        r.tor = tor;
        r.status = DownloadManager.STATUS_FAILED;
        r.error = reason == null ? "erreur inconnue" : reason;
        addRecord(context, r);
    }

    /** Resume compact affiche dans le menu principal. */
    public static String summary(Context context) {
        List<Record> records = loadAndRefresh(context);
        int active = 0;
        int done = 0;
        for (Record r : records) {
            if (isActive(r.status)) active++;
            else if (r.status == DownloadManager.STATUS_SUCCESSFUL) done++;
        }
        if (active > 0) return active + " en cours";
        return done > 0 ? done + " termine(s)" : "aucun";
    }

    /** Ouvre l'interface du centre de telechargements. */
    public static void show(Activity activity, Runnable back) {
        List<Record> records = loadAndRefresh(activity);
        Menus menu = new Menus(activity, "Telechargements");

        menu.add("\u21E9", "Ouvrir le dossier Telechargements",
                "fichiers enregistres par Android",
                () -> openDownloads(activity));
        menu.add("\u2311", "Wi-Fi uniquement",
                wifiOnly(activity) ? "active" : "desactive",
                () -> {
                    setWifiOnly(activity, !wifiOnly(activity));
                    Toast.makeText(activity,
                            wifiOnly(activity)
                                    ? "Les prochains telechargements attendront le Wi-Fi"
                                    : "Les prochains telechargements peuvent utiliser le reseau mobile",
                            Toast.LENGTH_LONG).show();
                    show(activity, back);
                });
        menu.add("\u21BB", "Actualiser", summary(activity),
                () -> show(activity, back));

        int shown = 0;
        for (Record record : records) {
            if (shown++ >= 35) break;
            final long id = record.id;
            menu.add(statusIcon(record.status), record.name,
                    statusLine(record), () -> showItem(activity, id, back));
        }

        if (records.isEmpty()) {
            menu.add("\u2205", "Aucun telechargement", "la liste est vide", () -> { });
        } else {
            menu.add("\u2327", "Effacer l'historique termine",
                    "les fichiers restent dans Telechargements",
                    () -> {
                        clearFinished(activity);
                        show(activity, back);
                    });
        }

        if (back != null) menu.back(back);
        menu.show();
    }

    private static void showItem(Activity activity, long id, Runnable parentBack) {
        Record found = find(loadAndRefresh(activity), id);
        if (found == null) {
            Toast.makeText(activity, "Telechargement introuvable", Toast.LENGTH_SHORT).show();
            show(activity, parentBack);
            return;
        }
        final Record item = found;
        Menus menu = new Menus(activity, item.name);
        menu.add(statusIcon(item.status), statusName(item.status),
                itemDetails(item), () -> { });

        if (item.status == DownloadManager.STATUS_SUCCESSFUL) {
            menu.add("\u2197", "Ouvrir", item.mime,
                    () -> openRecord(activity, item));
            menu.add("\u21AA", "Partager", () -> shareRecord(activity, item));
        }
        if (isActive(item.status) && item.id > 0) {
            menu.add("\u2715", "Annuler le telechargement", () -> {
                cancel(activity, item.id);
                show(activity, parentBack);
            });
        }
        if ((item.status == DownloadManager.STATUS_FAILED
                || item.status == STATUS_CANCELLED) && !item.url.isEmpty() && !item.tor) {
            menu.add("\u21BB", "Reessayer", host(item.url), () -> {
                long newId = enqueueUrl(activity, item.url, item.referer,
                        item.name, item.mime);
                Toast.makeText(activity,
                        newId > 0 ? "Telechargement relance" : "Relance impossible",
                        Toast.LENGTH_SHORT).show();
                show(activity, parentBack);
            });
        }
        if (!item.url.isEmpty()) {
            menu.add("\u29C9", "Copier l'adresse source", () -> copy(activity, item.url));
        }
        menu.add("\u2327", "Retirer de l'historique",
                "ne supprime pas le fichier",
                () -> {
                    removeHistory(activity, item.id);
                    show(activity, parentBack);
                });
        menu.back(() -> show(activity, parentBack)).show();
    }

    public static void onSystemDownloadComplete(Context context, long id) {
        if (id <= 0) return;
        synchronized (LOCK) {
            List<Record> records = load(context);
            Record r = find(records, id);
            if (r == null) return;
            refresh(context, r);
            save(context, records);
        }
    }

    private static void cancel(Context context, long id) {
        try {
            DownloadManager manager = (DownloadManager)
                    context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager != null) manager.remove(id);
        } catch (Throwable ignored) { }
        synchronized (LOCK) {
            List<Record> records = load(context);
            Record r = find(records, id);
            if (r != null) {
                r.status = STATUS_CANCELLED;
                r.updated = System.currentTimeMillis();
                save(context, records);
            }
        }
    }

    private static void openDownloads(Activity activity) {
        try {
            activity.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (Throwable e) {
            Toast.makeText(activity, "Gestionnaire de fichiers indisponible",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static void openRecord(Activity activity, Record record) {
        Uri uri = recordUri(activity, record);
        if (uri == null) {
            openDownloads(activity);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, record.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, "Ouvrir avec"));
        } catch (Throwable e) {
            openDownloads(activity);
        }
    }

    private static void shareRecord(Activity activity, Record record) {
        Uri uri = recordUri(activity, record);
        if (uri == null) {
            Toast.makeText(activity, "Ouvrez le dossier Telechargements pour partager ce fichier",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SEND)
                    .setType(record.mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, "Partager le fichier"));
        } catch (Throwable e) {
            Toast.makeText(activity, "Partage indisponible", Toast.LENGTH_SHORT).show();
        }
    }

    private static Uri recordUri(Context context, Record record) {
        try {
            if (record.id > 0) {
                DownloadManager manager = (DownloadManager)
                        context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager != null) {
                    Uri uri = manager.getUriForDownloadedFile(record.id);
                    if (uri != null) return uri;
                }
            }
            if (record.localUri != null && record.localUri.startsWith("content://")) {
                return Uri.parse(record.localUri);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void copy(Context context, String text) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("adresse", text));
            Toast.makeText(context, "Adresse copiee", Toast.LENGTH_SHORT).show();
        }
    }

    private static void addRecord(Context context, Record record) {
        synchronized (LOCK) {
            List<Record> records = load(context);
            records.add(0, record);
            while (records.size() > MAX_RECORDS) records.remove(records.size() - 1);
            save(context, records);
        }
    }

    private static List<Record> loadAndRefresh(Context context) {
        synchronized (LOCK) {
            List<Record> records = load(context);
            boolean changed = false;
            for (Record record : records) {
                int oldStatus = record.status;
                long oldBytes = record.bytes;
                if (record.id > 0) refresh(context, record);
                changed |= oldStatus != record.status || oldBytes != record.bytes;
            }
            if (changed) save(context, records);
            return records;
        }
    }

    private static List<Record> load(Context context) {
        List<Record> records = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_RECORDS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o != null) records.add(Record.from(o));
            }
        } catch (Throwable ignored) { }
        return records;
    }

    private static void save(Context context, List<Record> records) {
        try {
            JSONArray array = new JSONArray();
            for (Record record : records) array.put(record.json());
            prefs(context).edit().putString(KEY_RECORDS, array.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private static void refresh(Context context, Record record) {
        if (record.id <= 0) return;
        DownloadManager manager = (DownloadManager)
                context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return;
        Cursor cursor = null;
        try {
            cursor = manager.query(new DownloadManager.Query().setFilterById(record.id));
            if (cursor == null || !cursor.moveToFirst()) return;
            record.status = intColumn(cursor, DownloadManager.COLUMN_STATUS, record.status);
            record.reason = intColumn(cursor, DownloadManager.COLUMN_REASON, record.reason);
            record.bytes = longColumn(cursor,
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR, record.bytes);
            record.total = longColumn(cursor,
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES, record.total);
            String local = stringColumn(cursor, DownloadManager.COLUMN_LOCAL_URI);
            if (local != null && !local.isEmpty()) record.localUri = local;
            String media = stringColumn(cursor, DownloadManager.COLUMN_MEDIA_TYPE);
            if (media != null && !media.isEmpty()) record.mime = media;
            record.updated = System.currentTimeMillis();
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static int intColumn(Cursor cursor, String name, int fallback) {
        int index = cursor.getColumnIndex(name);
        return index >= 0 ? cursor.getInt(index) : fallback;
    }

    private static long longColumn(Cursor cursor, String name, long fallback) {
        int index = cursor.getColumnIndex(name);
        return index >= 0 ? cursor.getLong(index) : fallback;
    }

    private static String stringColumn(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index >= 0 ? cursor.getString(index) : null;
    }

    private static Record find(List<Record> records, long id) {
        for (Record record : records) if (record.id == id) return record;
        return null;
    }

    private static void removeHistory(Context context, long id) {
        synchronized (LOCK) {
            List<Record> records = load(context);
            for (int i = records.size() - 1; i >= 0; i--) {
                if (records.get(i).id == id) records.remove(i);
            }
            save(context, records);
        }
    }

    private static void clearFinished(Context context) {
        synchronized (LOCK) {
            List<Record> records = loadAndRefresh(context);
            for (int i = records.size() - 1; i >= 0; i--) {
                if (!isActive(records.get(i).status)) records.remove(i);
            }
            save(context, records);
        }
    }

    private static boolean isActive(int status) {
        return status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PAUSED;
    }

    private static String statusIcon(int status) {
        if (status == DownloadManager.STATUS_SUCCESSFUL) return "\u2713";
        if (status == DownloadManager.STATUS_RUNNING) return "\u21E9";
        if (status == DownloadManager.STATUS_PAUSED) return "\u2016";
        if (status == DownloadManager.STATUS_FAILED) return "!";
        if (status == STATUS_CANCELLED) return "\u2715";
        return "\u25F7";
    }

    private static String statusName(int status) {
        if (status == DownloadManager.STATUS_SUCCESSFUL) return "Termine";
        if (status == DownloadManager.STATUS_RUNNING) return "En cours";
        if (status == DownloadManager.STATUS_PAUSED) return "En attente";
        if (status == DownloadManager.STATUS_FAILED) return "Echec";
        if (status == STATUS_CANCELLED) return "Annule";
        return "Dans la file";
    }

    private static String statusLine(Record record) {
        StringBuilder line = new StringBuilder(statusName(record.status));
        if (record.total > 0) {
            int percent = (int) Math.min(100, record.bytes * 100 / record.total);
            line.append(" · ").append(percent).append(" % · ")
                    .append(human(record.bytes)).append(" / ").append(human(record.total));
        } else if (record.bytes > 0) {
            line.append(" · ").append(human(record.bytes));
        }
        if (record.tor) line.append(" · Tor");
        return line.toString();
    }

    private static String itemDetails(Record record) {
        StringBuilder details = new StringBuilder(statusLine(record));
        details.append(" · ").append(DateFormat.format("dd/MM HH:mm", record.created));
        if (!record.url.isEmpty()) details.append(" · ").append(host(record.url));
        if (record.status == DownloadManager.STATUS_FAILED) {
            if (record.error != null && !record.error.isEmpty()) {
                details.append(" · ").append(record.error);
            } else if (record.reason != 0) {
                details.append(" · code ").append(record.reason);
            }
        }
        return details.toString();
    }

    private static String deriveName(String url, String mime) {
        String name = "";
        try {
            String segment = Uri.parse(url == null ? "" : url).getLastPathSegment();
            if (segment != null) name = URLDecoder.decode(segment, "UTF-8");
        } catch (Throwable ignored) { }
        name = cleanName(name);
        if (name.isEmpty()) name = "telechargement";
        if (!name.contains(".") && mime != null) {
            String ext = MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mime.split(";")[0].trim());
            if (ext != null && !ext.isEmpty()) name += "." + ext;
        }
        return name;
    }

    private static String cleanName(String name) {
        if (name == null) return "";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120);
        return cleaned;
    }

    private static String guessMime(String name) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(name == null ? "" : name);
        String mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext == null ? "" : ext.toLowerCase(Locale.ROOT));
        return mime == null ? "application/octet-stream" : mime;
    }

    private static String host(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? "source web" : host.replaceFirst("^www\\.", "");
        } catch (Throwable ignored) {
            return "source web";
        }
    }

    private static String human(long bytes) {
        if (bytes < 0) return "taille inconnue";
        if (bytes < 1024) return bytes + " o";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.FRANCE, "%.1f Ko", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.FRANCE, "%.1f Mo", mb);
        return String.format(Locale.FRANCE, "%.2f Go", mb / 1024.0);
    }
}
