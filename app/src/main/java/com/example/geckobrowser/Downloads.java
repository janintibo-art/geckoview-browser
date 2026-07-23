package com.example.geckobrowser;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import org.mozilla.geckoview.WebResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enregistre dans le dossier Telechargements les reponses que Gecko ne peut
 * pas afficher (PDF, archives, fichiers binaires). Sans cela, appuyer sur un
 * lien de telechargement ne produit rien du tout.
 */
public class Downloads {

    public static void save(final Context ctx, final WebResponse response) {
        final String name = fileName(response);
        final String mime = headerOf(response, "content-type");

        Toast.makeText(ctx, "Telechargement : " + name, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            String message;
            try {
                long written = write(ctx, response, name, mime);
                message = "Enregistre : " + name + " (" + human(written) + ")";
            } catch (Exception e) {
                message = "Echec du telechargement : " + e.getMessage();
            }
            final String msg = message;
            new android.os.Handler(ctx.getMainLooper()).post(
                () -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
        }, "download").start();
    }

    // =======================================================================
    //  Telechargement par URL (ressources reperees par l'analyse de page)
    // =======================================================================
    private static ExecutorService pool;

    private static synchronized ExecutorService pool() {
        if (pool == null || pool.isShutdown()) pool = Executors.newFixedThreadPool(3);
        return pool;
    }

    /**
     * Telecharge une liste d'URL vers le dossier Telechargements.
     * Si Tor est actif, le trafic passe par le proxy SOCKS d'Orbot : sans cela,
     * le telechargement revelerait l'adresse IP reelle.
     */
    public static void saveUrls(final Context ctx, final String[] urls, final String referer) {
        if (urls == null || urls.length == 0) return;

        final boolean tor = TorSupport.isEnabled(ctx);
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger ko = new AtomicInteger();
        final AtomicInteger left = new AtomicInteger(urls.length);
        final android.os.Handler ui = new android.os.Handler(ctx.getMainLooper());

        toast(ctx, ui, urls.length + " fichier(s) en telechargement"
                + (tor ? " via Tor" : ""));

        for (final String url : urls) {
            pool().execute(() -> {
                try {
                    fetchToDownloads(ctx, url, referer, tor);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    ko.incrementAndGet();
                }
                if (left.decrementAndGet() == 0) {
                    String msg = ok.get() + " fichier(s) enregistre(s)"
                               + (ko.get() > 0 ? ", " + ko.get() + " echec(s)" : "");
                    toast(ctx, ui, msg);
                }
            });
        }
    }

    /** Enregistre un contenu texte fourni par la page (liste d'URL, code...). */
    public static void saveText(final Context ctx, final String name, final String text) {
        new Thread(() -> {
            String msg;
            try {
                byte[] data = text.getBytes("UTF-8");
                writeStream(ctx, new ByteArrayInputStream(data),
                        sanitize(name), "text/plain");
                msg = "Enregistre : " + name;
            } catch (Exception e) {
                msg = "Echec : " + e.getMessage();
            }
            final String m = msg;
            new android.os.Handler(ctx.getMainLooper()).post(
                () -> Toast.makeText(ctx, m, Toast.LENGTH_LONG).show());
        }, "save-text").start();
    }

    private static void toast(Context ctx, android.os.Handler ui, String msg) {
        ui.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }

    private static void fetchToDownloads(Context ctx, String url, String referer,
                                         boolean tor) throws Exception {
        Proxy proxy = Proxy.NO_PROXY;
        if (tor) {
            // createUnresolved : c'est Tor qui resout le nom, pas le telephone.
            proxy = new Proxy(Proxy.Type.SOCKS,
                    InetSocketAddress.createUnresolved("127.0.0.1", 9050));
        }

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/20100101 Firefox/128.0");
        if (referer != null && !referer.isEmpty()) {
            c.setRequestProperty("Referer", referer);
        }
        c.setRequestProperty("Accept", "*/*");

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

        String mime = c.getContentType();
        String name = nameFrom(url, c.getHeaderField("Content-Disposition"), mime);

        try {
            writeStream(ctx, c.getInputStream(), name, mime);
        } finally {
            c.disconnect();
        }
    }

    private static String nameFrom(String url, String disposition, String mime) {
        if (disposition != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?",
                         java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(disposition);
            if (m.find()) {
                String n = sanitize(m.group(1).trim());
                if (!n.isEmpty()) return n;
            }
        }
        String name = "";
        try {
            String seg = Uri.parse(url).getLastPathSegment();
            if (seg != null) name = sanitize(seg);
        } catch (Exception ignored) { }
        if (name.isEmpty()) name = "fichier";
        if (!name.contains(".") && mime != null) {
            String ext = MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mime.split(";")[0].trim());
            if (ext != null) name = name + "." + ext;
        }
        return name;
    }

    /** Ecriture commune, quelle que soit la provenance du flux. */
    private static long writeStream(Context ctx, InputStream in, String name,
                                    String mime) throws Exception {
        OutputStream out;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            if (mime != null && !mime.isEmpty()) {
                values.put(MediaStore.Downloads.MIME_TYPE, mime.split(";")[0].trim());
            }
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            ContentResolver cr = ctx.getContentResolver();
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("dossier inaccessible");

            out = cr.openOutputStream(uri);
            long n = copy(in, out);

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, values, null, null);
            return n;
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("dossier inaccessible");
            out = new FileOutputStream(unique(dir, name));
            return copy(in, out);
        }
    }

    // -----------------------------------------------------------------------
    private static long write(Context ctx, WebResponse response,
                              String name, String mime) throws Exception {
        InputStream in = response.body;
        if (in == null) throw new Exception("reponse vide");

        OutputStream out;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            if (mime != null && !mime.isEmpty()) {
                values.put(MediaStore.Downloads.MIME_TYPE, mime.split(";")[0].trim());
            }
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            ContentResolver cr = ctx.getContentResolver();
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("dossier inaccessible");

            out = cr.openOutputStream(uri);
            long n = copy(in, out);

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, values, null, null);
            return n;
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("dossier inaccessible");
            File target = unique(dir, name);
            out = new FileOutputStream(target);
            return copy(in, out);
        }
    }

    private static long copy(InputStream in, OutputStream out) throws Exception {
        if (out == null) throw new Exception("ecriture impossible");
        long total = 0;
        byte[] buf = new byte[32 * 1024];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
        } finally {
            try { out.close(); } catch (Exception ignored) { }
            try { in.close(); } catch (Exception ignored) { }
        }
        return total;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 1; i < 500; i++) {
            File c = new File(dir, base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return f;
    }

    // -----------------------------------------------------------------------
    private static String headerOf(WebResponse r, String key) {
        if (r.headers == null) return null;
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static String fileName(WebResponse r) {
        // 1. En-tete Content-Disposition
        String cd = headerOf(r, "content-disposition");
        if (cd != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(cd);
            if (m.find()) {
                String n = m.group(1).trim();
                try { n = java.net.URLDecoder.decode(n, "UTF-8"); } catch (Exception ignored) { }
                n = sanitize(n);
                if (!n.isEmpty()) return n;
            }
        }

        // 2. Dernier segment de l'URL
        String name = "";
        try {
            String path = Uri.parse(r.uri).getLastPathSegment();
            if (path != null) name = sanitize(path);
        } catch (Exception ignored) { }

        if (name.isEmpty()) name = "telechargement";

        // 3. Extension deduite du type MIME si absente
        if (!name.contains(".")) {
            String mime = headerOf(r, "content-type");
            if (mime != null) {
                String ext = MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(mime.split(";")[0].trim());
                if (ext != null) name = name + "." + ext;
            }
        }
        return name;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " o";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " Ko";
        return String.format(java.util.Locale.FRANCE, "%.1f Mo", bytes / 1048576.0);
    }
}
