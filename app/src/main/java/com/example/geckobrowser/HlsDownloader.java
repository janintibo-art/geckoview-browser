package com.example.geckobrowser;

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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HLS_DOWNLOAD_V1 — telechargement des flux segmentes (HLS / .m3u8).
 *
 * La plupart des videos en ligne ne sont pas un fichier unique mais une
 * playlist .m3u8 pointant vers des centaines de petits segments .ts. Le
 * telechargeur classique et l'extracteur audio les refusaient.
 *
 * Ici : on lit la playlist, on choisit une qualite si elle est « maitre »
 * (plusieurs debits), on telecharge les segments dans l'ordre, on les
 * concatene, puis MediaMuxer les remuxe en MP4 sans reencodage. Rien ne
 * sort de l'appareil, aucun outil externe (pas de ffmpeg).
 *
 * Limites assumees, annoncees a l'utilisateur :
 *   - segments chiffres (#EXT-X-KEY, DRM) non pris en charge ;
 *   - un flux DASH .mpd n'est pas gere ici (structure differente).
 */
public final class HlsDownloader {

    private static final int MAX_SEGMENTS = 4000;
    private static final long MAX_TOTAL = 3L * 1024 * 1024 * 1024;   // 3 Go
    private static final String UA =
            "Mozilla/5.0 (Android 14; Mobile; rv:151.0) Gecko/20100101 Firefox/151.0";

    private static ExecutorService pool;

    private HlsDownloader() { }

    private static synchronized ExecutorService pool() {
        if (pool == null) pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "geckobrowser-hls");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        return pool;
    }

    public static boolean isHls(String url) {
        return url != null && url.toLowerCase().contains(".m3u8");
    }

    /** Point d'entree : telecharge le flux en tache de fond. */
    public static void download(final Context ctx, final String playlistUrl,
                                final String referer, final String suggestedName) {
        final boolean tor = TorSupport.isEnabled(ctx);
        final android.os.Handler ui = new android.os.Handler(ctx.getMainLooper());

        ui.post(() -> Toast.makeText(ctx,
                "Analyse du flux video\u2026" + (tor ? " (via Tor)" : ""),
                Toast.LENGTH_SHORT).show());

        pool().execute(() -> {
            try {
                run(ctx, ui, playlistUrl, referer, tor, suggestedName);
            } catch (Throwable e) {
                final String msg = e.getMessage() == null ? "erreur" : e.getMessage();
                ui.post(() -> Toast.makeText(ctx,
                        "Flux non telecharge : " + msg, Toast.LENGTH_LONG).show());
            }
        });
    }

    private static void run(Context ctx, android.os.Handler ui, String playlistUrl,
                            String referer, boolean tor, String suggestedName)
            throws Exception {
        String text = fetchText(playlistUrl, referer, tor);
        if (text == null || text.indexOf("#EXTM3U") == -1) {
            throw new Exception("playlist illisible");
        }

        // Playlist « maitre » : choisir le meilleur debit dont on saura le lire.
        if (text.indexOf("#EXT-X-STREAM-INF") != -1) {
            String best = pickVariant(playlistUrl, text);
            if (best == null) throw new Exception("aucune qualite exploitable");
            text = fetchText(best, referer, tor);
            playlistUrl = best;
            if (text == null) throw new Exception("sous-playlist illisible");
        }

        if (text.indexOf("#EXT-X-KEY") != -1
                && text.indexOf("METHOD=NONE") == -1) {
            throw new Exception("flux chiffre (protege) non pris en charge");
        }

        List<String> segments = segmentUrls(playlistUrl, text);
        if (segments.isEmpty()) throw new Exception("aucun segment trouve");
        if (segments.size() > MAX_SEGMENTS) {
            throw new Exception("flux trop long (" + segments.size() + " segments)");
        }

        File merged = File.createTempFile("hls", ".ts", ctx.getCacheDir());
        long total = 0;
        try (OutputStream out = new FileOutputStream(merged)) {
            byte[] buf = new byte[64 * 1024];
            for (int i = 0; i < segments.size(); i++) {
                final int done = i + 1;
                final int count = segments.size();
                // Un point d'etape de temps en temps, sans noyer l'utilisateur.
                if (done == 1 || done % 25 == 0 || done == count) {
                    ui.post(() -> Toast.makeText(ctx,
                            "Video : segment " + done + "/" + count,
                            Toast.LENGTH_SHORT).show());
                }
                HttpURLConnection c = open(segments.get(i), referer, tor);
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) {
                    c.disconnect();
                    throw new Exception("segment " + done + " : HTTP " + code);
                }
                try (InputStream in = c.getInputStream()) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        total += n;
                        if (total > MAX_TOTAL) throw new Exception("flux trop volumineux");
                    }
                } finally {
                    c.disconnect();
                }
            }
        } catch (Exception e) {
            merged.delete();
            throw e;
        }

        try {
            String saved = remux(ctx, merged, cleanName(suggestedName));
            ui.post(() -> Toast.makeText(ctx, "Video enregistree : " + saved,
                    Toast.LENGTH_LONG).show());
            DownloadCenter.recordDirectCompleted(ctx, saved, "video/mp4",
                    merged.length(), "", playlistUrl, tor);
        } finally {
            merged.delete();
        }
    }

    // -----------------------------------------------------------------------
    //  Analyse de la playlist
    // -----------------------------------------------------------------------
    /** Choisit la variante de plus haute resolution dans une playlist maitre. */
    private static String pickVariant(String base, String text) {
        String[] lines = text.split("\\r?\\n");
        String bestUri = null;
        long bestScore = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue;
            long score = 0;
            int r = line.indexOf("RESOLUTION=");
            if (r != -1) {
                String res = line.substring(r + 11).split("[,\\s]")[0];
                String[] wh = res.split("x");
                try { score = Long.parseLong(wh[0]) * Long.parseLong(wh[1]); }
                catch (Exception ignored) { }
            }
            if (score == 0) {
                int b = line.indexOf("BANDWIDTH=");
                if (b != -1) {
                    try { score = Long.parseLong(line.substring(b + 10).split("[,\\s]")[0]); }
                    catch (Exception ignored) { }
                }
            }
            // L'URI est sur la ligne suivante non commentee.
            for (int j = i + 1; j < lines.length; j++) {
                String u = lines[j].trim();
                if (u.isEmpty() || u.startsWith("#")) continue;
                if (score >= bestScore) { bestScore = score; bestUri = u; }
                break;
            }
        }
        return bestUri == null ? null : absolute(base, bestUri);
    }

    private static List<String> segmentUrls(String base, String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(absolute(base, line));
        }
        return out;
    }

    /** Resout une URI relative de segment contre l'URL de la playlist. */
    private static String absolute(String base, String ref) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    // -----------------------------------------------------------------------
    //  Remux : le .ts concatene devient un .mp4 sans reencodage
    // -----------------------------------------------------------------------
    private static String remux(Context ctx, File source, String base) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(source.getAbsolutePath());

        int n = ex.getTrackCount();
        if (n == 0) { ex.release(); throw new Exception("flux illisible apres assemblage"); }

        String name = base + ".mp4";
        Uri dest = null;
        File legacy = null;
        ParcelFileDescriptor pfd = null;
        MediaMuxer mux;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_MOVIES + "/GeckoBrowser");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            dest = ctx.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (dest == null) { ex.release(); throw new Exception("fichier de sortie refuse"); }
            pfd = ctx.getContentResolver().openFileDescriptor(dest, "rw");
            mux = new MediaMuxer(pfd.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } else {
            File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MOVIES), "GeckoBrowser");
            dir.mkdirs();
            legacy = new File(dir, name);
            mux = new MediaMuxer(legacy.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        // On recopie chaque piste (video + audio) telle quelle.
        int[] outTracks = new int[n];
        long maxBuffer = 1024 * 1024;
        for (int i = 0; i < n; i++) {
            MediaFormat f = ex.getTrackFormat(i);
            outTracks[i] = mux.addTrack(f);
            long m = f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
            if (m > maxBuffer) maxBuffer = m;
        }

        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(maxBuffer, 4 * 1024 * 1024));
        try {
            mux.start();
            for (int i = 0; i < n; i++) {
                ex.selectTrack(i);
                ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                copyTrack(ex, mux, outTracks[i], buffer);
                ex.unselectTrack(i);
            }
            mux.stop();
        } finally {
            try { mux.release(); } catch (Exception ignored) { }
            ex.release();
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) { }
        }

        if (dest != null) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            ctx.getContentResolver().update(dest, done, null, null);
        }
        return name;
    }

    private static void copyTrack(MediaExtractor ex, MediaMuxer mux, int outTrack,
                                  ByteBuffer buffer) throws Exception {
        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        while (true) {
            int size = ex.readSampleData(buffer, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = ex.getSampleFlags();
            mux.writeSampleData(outTrack, buffer, info);
            ex.advance();
        }
    }

    // -----------------------------------------------------------------------
    //  Reseau
    // -----------------------------------------------------------------------
    private static String fetchText(String url, String referer, boolean tor) throws Exception {
        HttpURLConnection c = open(url, referer, tor);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { c.disconnect(); throw new Exception("HTTP " + code); }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
        } finally {
            c.disconnect();
        }
        return sb.toString();
    }

    private static HttpURLConnection open(String url, String referer, boolean tor)
            throws Exception {
        Proxy proxy = Proxy.NO_PROXY;
        if (tor) {
            proxy = new Proxy(Proxy.Type.SOCKS,
                    InetSocketAddress.createUnresolved("127.0.0.1", 9050));
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", UA);
        if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
        return c;
    }

    private static String cleanName(String suggested) {
        String base = suggested == null ? "" : suggested;
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.isEmpty()) base = "video_" + System.currentTimeMillis();
        if (base.length() > 80) base = base.substring(0, 80);
        return base;
    }
}
