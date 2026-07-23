package com.example.geckobrowser;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extrait la piste audio de fichiers video, sans reencodage.
 *
 * Android fournit des decodeurs MP3 mais pas d'encodeur : produire un vrai
 * MP3 imposerait d'embarquer un encodeur natif. On recopie donc la piste
 * telle quelle dans un conteneur audio, ce qui est plus rapide et sans perte.
 *   AAC  -> .m4a   (lisible partout, y compris par les lecteurs "MP3")
 *   Opus -> .ogg   (Android 10 et superieur)
 *
 * Le telechargement passe par le proxy Tor quand le mode est actif.
 */
public class AudioExtractor {

    private static ExecutorService pool;

    private static synchronized ExecutorService pool() {
        if (pool == null || pool.isShutdown()) pool = Executors.newFixedThreadPool(2);
        return pool;
    }

    // -----------------------------------------------------------------------
    public static void extract(final Context ctx, final String[] urls, final String referer) {
        if (urls == null || urls.length == 0) return;

        final boolean tor = TorSupport.isEnabled(ctx);
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger ko = new AtomicInteger();
        final AtomicInteger left = new AtomicInteger(urls.length);
        final android.os.Handler ui = new android.os.Handler(ctx.getMainLooper());
        final StringBuilder errors = new StringBuilder();

        ui.post(() -> Toast.makeText(ctx,
                "Extraction audio : " + urls.length + " fichier(s)"
                        + (tor ? " via Tor" : ""),
                Toast.LENGTH_LONG).show());

        for (final String url : urls) {
            pool().execute(() -> {
                File tmp = null;
                try {
                    tmp = download(ctx, url, referer, tor);
                    String out = extractTrack(ctx, tmp, baseName(url));
                    ok.incrementAndGet();
                    ui.post(() -> Toast.makeText(ctx, "Audio : " + out,
                            Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    ko.incrementAndGet();
                    synchronized (errors) {
                        if (errors.length() < 200) {
                            errors.append(e.getMessage()).append(" ");
                        }
                    }
                } finally {
                    if (tmp != null) { try { tmp.delete(); } catch (Exception ignored) { } }
                }

                if (left.decrementAndGet() == 0) {
                    String msg = ok.get() + " piste(s) extraite(s)"
                            + (ko.get() > 0 ? ", " + ko.get() + " echec(s) : " + errors : "");
                    ui.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    // -----------------------------------------------------------------------
    //  Telechargement dans le cache (jamais expose a l'utilisateur)
    // -----------------------------------------------------------------------
    private static File download(Context ctx, String url, String referer,
                                 boolean tor) throws Exception {
        if (url.contains(".m3u8") || url.contains(".mpd")) {
            throw new Exception("flux segmente non pris en charge");
        }

        Proxy proxy = Proxy.NO_PROXY;
        if (tor) {
            proxy = new Proxy(Proxy.Type.SOCKS,
                    InetSocketAddress.createUnresolved("127.0.0.1", 9050));
        }

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/20100101 Firefox/128.0");
        if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

        File tmp = File.createTempFile("media", ".bin", ctx.getCacheDir());
        InputStream in = c.getInputStream();
        OutputStream out = new FileOutputStream(tmp);
        byte[] buf = new byte[64 * 1024];
        int n;
        try {
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            try { out.close(); } catch (Exception ignored) { }
            try { in.close(); } catch (Exception ignored) { }
            c.disconnect();
        }
        return tmp;
    }

    // -----------------------------------------------------------------------
    //  Recopie de la piste audio dans un conteneur audio
    // -----------------------------------------------------------------------
    private static String extractTrack(Context ctx, File source, String base) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(source.getAbsolutePath());

        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
        }
        if (track < 0) { ex.release(); throw new Exception("aucune piste audio"); }

        String mime = fmt.getString(MediaFormat.KEY_MIME);
        int container;
        String ext;

        if ("audio/mp4a-latm".equals(mime)) {
            container = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
            ext = ".m4a";
        } else if ("audio/opus".equals(mime) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            container = MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG;
            ext = ".ogg";
        } else {
            ex.release();
            throw new Exception("format audio " + mime + " non recopiable");
        }

        String name = base + ext;
        ex.selectTrack(track);

        // Fichier de sortie dans Musique (ou Telechargements avant Android 10)
        Uri dest = null;
        File legacy = null;
        ParcelFileDescriptor pfd = null;
        MediaMuxer mux;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Audio.Media.MIME_TYPE, ext.equals(".ogg") ? "audio/ogg" : "audio/mp4");
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MUSIC);
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);

            ContentResolver cr = ctx.getContentResolver();
            dest = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (dest == null) { ex.release(); throw new Exception("dossier Musique inaccessible"); }

            pfd = cr.openFileDescriptor(dest, "rw");
            mux = new MediaMuxer(pfd.getFileDescriptor(), container);
        } else {
            File dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MUSIC);
            if (!dir.exists()) dir.mkdirs();
            legacy = new File(dir, name);
            mux = new MediaMuxer(legacy.getAbsolutePath(), container);
        }

        int outTrack = mux.addTrack(fmt);
        mux.start();

        int cap = fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 1 << 20;
        if (cap < 65536) cap = 1 << 20;

        ByteBuffer buf = ByteBuffer.allocate(cap);
        MediaCodecBufferInfo info = new MediaCodecBufferInfo();

        try {
            while (true) {
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = ex.getSampleTime();
                info.flags = ex.getSampleFlags();
                mux.writeSampleData(outTrack, buf, info.toAndroid());
                ex.advance();
            }
        } finally {
            try { mux.stop(); } catch (Exception ignored) { }
            try { mux.release(); } catch (Exception ignored) { }
            try { ex.release(); } catch (Exception ignored) { }
            if (pfd != null) { try { pfd.close(); } catch (Exception ignored) { } }
        }

        if (dest != null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.IS_PENDING, 0);
            ctx.getContentResolver().update(dest, v, null, null);
        }
        return name;
    }

    // -----------------------------------------------------------------------
    private static String baseName(String url) {
        String n = "audio";
        try {
            String seg = Uri.parse(url).getLastPathSegment();
            if (seg != null && !seg.isEmpty()) {
                n = seg.replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
            }
        } catch (Exception ignored) { }
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.isEmpty()) n = "audio";
        if (n.length() > 80) n = n.substring(0, 80);
        return n;
    }

    /** Petit relais : MediaCodec.BufferInfo n'est pas reutilisable directement. */
    private static class MediaCodecBufferInfo {
        int offset, size, flags;
        long presentationTimeUs;
        android.media.MediaCodec.BufferInfo toAndroid() {
            android.media.MediaCodec.BufferInfo b = new android.media.MediaCodec.BufferInfo();
            b.set(offset, size, presentationTimeUs, flags);
            return b;
        }
    }
}
