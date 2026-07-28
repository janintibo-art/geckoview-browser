package com.example.geckobrowser;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.mozilla.geckoview.GeckoSession;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Centre multimedia natif de GeckoBrowser.
 *
 * Il relie MediaSession de GeckoView aux commandes Android, a la notification
 * de lecture, au menu de l'application et au mode image-dans-l'image.
 */
public final class MediaHub implements org.mozilla.geckoview.MediaSession.Delegate {

    private static final String CHANNEL = "geckobrowser_media";
    private static final int NOTIFICATION_ID = 646;

    static final String ACTION_PLAY_PAUSE =
            "com.example.geckobrowser.media.PLAY_PAUSE";
    static final String ACTION_PREVIOUS =
            "com.example.geckobrowser.media.PREVIOUS";
    static final String ACTION_NEXT =
            "com.example.geckobrowser.media.NEXT";
    static final String ACTION_REWIND =
            "com.example.geckobrowser.media.REWIND";
    static final String ACTION_FORWARD =
            "com.example.geckobrowser.media.FORWARD";
    static final String ACTION_STOP =
            "com.example.geckobrowser.media.STOP";

    private static WeakReference<MediaHub> current = new WeakReference<>(null);

    private static final class Slot {
        org.mozilla.geckoview.MediaSession gecko;
        boolean active;
        boolean playing;
        boolean muted;
        boolean fullscreen;
        long features;
        String title = "";
        String artist = "";
        String album = "";
        double duration;
        double position;
        double rate = 1.0;
        long videoWidth;
        long videoHeight;
        long touched;
    }

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<GeckoSession, Slot> slots = new WeakHashMap<>();
    private final android.media.session.MediaSession platformSession;
    private final NotificationManager notifications;

    private GeckoSession owner;
    private Slot currentSlot;
    private long lastNotificationAt;

    public MediaHub(MainActivity activity) {
        this.activity = activity;
        current = new WeakReference<>(this);
        notifications = (NotificationManager)
                activity.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();

        platformSession = new android.media.session.MediaSession(activity,
                "GeckoBrowserMedia");
        platformSession.setFlags(
                android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
              | android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        platformSession.setCallback(new android.media.session.MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { stop(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onFastForward() { forward(); }
            @Override public void onRewind() { rewind(); }
            @Override public void onSeekTo(long pos) {
                Slot slot = currentSlot;
                if (slot != null && slot.gecko != null) {
                    try { slot.gecko.seekTo(pos / 1000.0, false); }
                    catch (Throwable ignored) { }
                }
            }
        });
    }

    public void attach(GeckoSession session) {
        if (session == null) return;
        slots.put(session, new Slot());
        try { session.setMediaSessionDelegate(this); }
        catch (Throwable ignored) { }
    }

    public void detach(GeckoSession session) {
        if (session == null) return;
        Slot removed = slots.remove(session);
        if (session == owner || removed == currentSlot) chooseCurrent();
        updatePlatform(true);
    }

    public boolean isPlaying(GeckoSession session) {
        Slot slot = slots.get(session);
        return slot != null && slot.active && slot.playing;
    }

    public boolean hasActiveMedia() {
        return currentSlot != null && currentSlot.active;
    }

    public String summary() {
        Slot slot = currentSlot;
        if (slot == null || !slot.active) return "aucun media actif";
        String state = slot.playing ? "lecture" : "pause";
        return state + " · " + displayTitle(slot, owner);
    }

    public void showMenu(Runnable back) {
        Menus menu = new Menus(activity, "Multimedia");
        Slot slot = currentSlot;
        GeckoSession session = owner;

        if (slot == null || session == null || !slot.active) {
            menu.add("\u25B6", "Aucun media actif",
                    "Lancez une video ou un morceau dans une page", () -> { });
        } else {
            menu.add("\u25A3", displayTitle(slot, session), detail(slot),
                    () -> activity.openMediaSession(session));

            if (slot.playing) {
                menu.add("\u23F8", "Mettre en pause", this::pause);
            } else {
                menu.add("\u25B6", "Reprendre la lecture", this::play);
            }
            if (supports(slot, org.mozilla.geckoview.MediaSession.Feature.SEEK_BACKWARD)) {
                menu.add("\u21A4", "Reculer", "saut adapte par le site", this::rewind);
            }
            if (supports(slot, org.mozilla.geckoview.MediaSession.Feature.SEEK_FORWARD)) {
                menu.add("\u21A6", "Avancer", "saut adapte par le site", this::forward);
            }
            if (supports(slot, org.mozilla.geckoview.MediaSession.Feature.PREVIOUS_TRACK)) {
                menu.add("\u23EE", "Piste precedente", this::previous);
            }
            if (supports(slot, org.mozilla.geckoview.MediaSession.Feature.NEXT_TRACK)) {
                menu.add("\u23ED", "Piste suivante", this::next);
            }
            menu.add(slot.muted ? "\u266B" : "\u266A",
                    slot.muted ? "Retablir le son" : "Couper le son", this::toggleMute);
            menu.add("\u25A3", "Image dans l'image",
                    "continue au-dessus des autres applications", this::enterPictureInPicture);
            if (supports(slot, org.mozilla.geckoview.MediaSession.Feature.STOP)) {
                menu.add("\u25A0", "Arreter", this::stop);
            }
        }

        boolean auto = autoPictureInPicture();
        menu.add("\u21AA", "PiP automatique en quittant",
                auto ? "actif" : "inactif", () -> {
                    activity.getSharedPreferences("geckobrowser", Context.MODE_PRIVATE)
                            .edit().putBoolean("mediaAutoPip", !auto).apply();
                    showMenu(back);
                });
        menu.add("\u24D8", "Fonctionnement",
                "commandes GeckoView et Android", () -> Menus.info(activity,
                        "Multimedia",
                        "Les commandes pilotent directement la session multimedia de GeckoView. "
                      + "La notification et les boutons du casque utilisent la session multimedia "
                      + "native d'Android. L'image dans l'image est disponible a partir d'Android 8."));
        menu.back(back).show();
    }

    public void onUserLeaveHint() {
        Slot slot = currentSlot;
        if (autoPictureInPicture() && slot != null && slot.active && slot.playing) {
            enterPictureInPicture();
        }
    }

    public void onPipModeChanged(boolean enabled) {
        GeckoSession target = owner;
        if (target != null) {
            try { target.getCompositorController().onPipModeChanged(enabled); }
            catch (Throwable ignored) { }
        }
    }

    public void release() {
        try { notifications.cancel(NOTIFICATION_ID); } catch (Throwable ignored) { }
        try { platformSession.setActive(false); } catch (Throwable ignored) { }
        try { platformSession.release(); } catch (Throwable ignored) { }
        slots.clear();
        owner = null;
        currentSlot = null;
        if (current.get() == this) current = new WeakReference<>(null);
    }

    static void dispatchExternalAction(String action) {
        MediaHub hub = current.get();
        if (hub == null || action == null) return;
        hub.handler.post(() -> {
            switch (action) {
                case ACTION_PLAY_PAUSE: hub.togglePlayPause(); break;
                case ACTION_PREVIOUS: hub.previous(); break;
                case ACTION_NEXT: hub.next(); break;
                case ACTION_REWIND: hub.rewind(); break;
                case ACTION_FORWARD: hub.forward(); break;
                case ACTION_STOP: hub.stop(); break;
                default: break;
            }
        });
    }

    @Override
    public void onActivated(GeckoSession session,
            org.mozilla.geckoview.MediaSession mediaSession) {
        Slot slot = slot(session, mediaSession);
        slot.active = true;
        touch(session, slot);
        updatePlatform(true);
    }

    @Override
    public void onDeactivated(GeckoSession session,
            org.mozilla.geckoview.MediaSession mediaSession) {
        Slot slot = slot(session, mediaSession);
        slot.active = false;
        slot.playing = false;
        if (session == owner) chooseCurrent();
        updatePlatform(true);
    }

    @Override
    public void onMetadata(GeckoSession session,
            org.mozilla.geckoview.MediaSession mediaSession,
            org.mozilla.geckoview.MediaSession.Metadata metadata) {
        Slot slot = slot(session, mediaSession);
        slot.title = clean(metadata == null ? null : metadata.title);
        slot.artist = clean(metadata == null ? null : metadata.artist);
        slot.album = clean(metadata == null ? null : metadata.album);
        touch(session, slot);
        updatePlatform(true);
    }

    @Override
    public void onFeatures(GeckoSession session,
            org.mozilla.geckoview.MediaSession mediaSession, long features) {
        Slot slot = slot(session, mediaSession);
        slot.features = features;
        touch(session, slot);
        updatePlatform(true);
    }

    @Override
    public void onPlay(GeckoSession session,
            org.mozilla.geckoview.MediaSession mediaSession) {
        Slot slot = slot(session, mediaSession);
        slot.active = true;
        slot.playing = true;
        touch(session, slot);
        updatePlatform(true);
    }

    @Override
    public void onPause(GeckoSession session,
            org.mozilla.geckoview.MediaSession mediaSession) {
        Slot slot = slot(session, mediaSession);
        slot.playing = false;
        touch(session, slot);
        updatePlatform(true);
    }

    @Override
    public void onStop(GeckoSession session,
            org.mozilla.geckoview.MediaSession mediaSession) {
        Slot slot = slot(session, mediaSession);
        slot.playing = false;
        slot.position = 0;
        touch(session, slot);
        updatePlatform(true);
    }

    @Override
    public void onPositionState(GeckoSession session,
            org.mozilla.geckoview.MediaSession mediaSession,
            org.mozilla.geckoview.MediaSession.PositionState state) {
        Slot slot = slot(session, mediaSession);
        if (state != null) {
            slot.duration = state.duration;
            slot.position = state.position;
            slot.rate = state.playbackRate;
        }
        touch(session, slot);
        updatePlatform(false);
    }

    @Override
    public void onFullscreen(GeckoSession session,
            org.mozilla.geckoview.MediaSession mediaSession, boolean enabled,
            org.mozilla.geckoview.MediaSession.ElementMetadata metadata) {
        Slot slot = slot(session, mediaSession);
        slot.fullscreen = enabled;
        if (metadata != null) {
            slot.videoWidth = metadata.width;
            slot.videoHeight = metadata.height;
            if (slot.duration <= 0) slot.duration = metadata.duration;
        }
        touch(session, slot);
        activity.onMediaFullscreen(session, enabled);
        updatePlatform(true);
    }

    private Slot slot(GeckoSession session,
            org.mozilla.geckoview.MediaSession mediaSession) {
        Slot slot = slots.get(session);
        if (slot == null) {
            slot = new Slot();
            slots.put(session, slot);
        }
        slot.gecko = mediaSession;
        return slot;
    }

    private void touch(GeckoSession session, Slot slot) {
        slot.touched = SystemClock.elapsedRealtime();
        if (slot.active || slot.playing || owner == null) {
            owner = session;
            currentSlot = slot;
        }
    }

    private void chooseCurrent() {
        owner = null;
        currentSlot = null;
        long newest = Long.MIN_VALUE;
        for (Map.Entry<GeckoSession, Slot> entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            if (slot == null || !slot.active) continue;
            if (slot.playing) {
                owner = entry.getKey();
                currentSlot = slot;
                return;
            }
            if (slot.touched > newest) {
                newest = slot.touched;
                owner = entry.getKey();
                currentSlot = slot;
            }
        }
    }

    private boolean autoPictureInPicture() {
        return activity.getSharedPreferences("geckobrowser", Context.MODE_PRIVATE)
                .getBoolean("mediaAutoPip", true);
    }

    private void enterPictureInPicture() {
        Slot slot = currentSlot;
        GeckoSession target = owner;
        if (slot == null || target == null || !slot.active) return;
        activity.openMediaSession(target);
        activity.prepareMediaPictureInPicture();
        handler.postDelayed(() -> activity.requestMediaPictureInPicture(
                slot.videoWidth, slot.videoHeight), 180);
    }

    private void togglePlayPause() {
        Slot slot = currentSlot;
        if (slot == null) return;
        if (slot.playing) pause(); else play();
    }

    private void play() {
        Slot slot = currentSlot;
        if (slot == null || slot.gecko == null) return;
        try { slot.gecko.play(); } catch (Throwable ignored) { }
    }

    private void pause() {
        Slot slot = currentSlot;
        if (slot == null || slot.gecko == null) return;
        try { slot.gecko.pause(); } catch (Throwable ignored) { }
    }

    private void stop() {
        Slot slot = currentSlot;
        if (slot == null || slot.gecko == null) return;
        try { slot.gecko.stop(); } catch (Throwable ignored) { }
    }

    private void previous() {
        Slot slot = currentSlot;
        if (slot == null || slot.gecko == null) return;
        try { slot.gecko.previousTrack(); } catch (Throwable ignored) { }
    }

    private void next() {
        Slot slot = currentSlot;
        if (slot == null || slot.gecko == null) return;
        try { slot.gecko.nextTrack(); } catch (Throwable ignored) { }
    }

    private void rewind() {
        Slot slot = currentSlot;
        if (slot == null || slot.gecko == null) return;
        try { slot.gecko.seekBackward(); } catch (Throwable ignored) { }
    }

    private void forward() {
        Slot slot = currentSlot;
        if (slot == null || slot.gecko == null) return;
        try { slot.gecko.seekForward(); } catch (Throwable ignored) { }
    }

    private void toggleMute() {
        Slot slot = currentSlot;
        if (slot == null || slot.gecko == null) return;
        slot.muted = !slot.muted;
        try { slot.gecko.muteAudio(slot.muted); } catch (Throwable ignored) { }
        updatePlatform(true);
    }

    private static boolean supports(Slot slot, long feature) {
        return slot != null && (slot.features & feature) != 0;
    }

    private void createChannel() {
        if (notifications == null || Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Lecture multimedia", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Commandes des videos et morceaux lus dans GeckoBrowser");
        channel.setShowBadge(false);
        notifications.createNotificationChannel(channel);
    }

    private void updatePlatform(boolean redrawNotification) {
        Slot slot = currentSlot;
        if (slot == null || !slot.active) {
            try { platformSession.setActive(false); } catch (Throwable ignored) { }
            try { notifications.cancel(NOTIFICATION_ID); } catch (Throwable ignored) { }
            return;
        }

        try {
            platformSession.setActive(true);
            platformSession.setMetadata(buildMetadata(slot));
            platformSession.setPlaybackState(buildPlaybackState(slot));
        } catch (Throwable ignored) { }

        long now = SystemClock.elapsedRealtime();
        if (redrawNotification || now - lastNotificationAt > 1500) {
            lastNotificationAt = now;
            showNotification(slot);
        }
    }

    private MediaMetadata buildMetadata(Slot slot) {
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, displayTitle(slot, owner));
        if (!slot.artist.isEmpty()) {
            builder.putString(MediaMetadata.METADATA_KEY_ARTIST, slot.artist);
        }
        if (!slot.album.isEmpty()) {
            builder.putString(MediaMetadata.METADATA_KEY_ALBUM, slot.album);
        }
        if (slot.duration > 0 && Double.isFinite(slot.duration)) {
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION,
                    Math.max(0L, Math.round(slot.duration * 1000.0)));
        }
        return builder.build();
    }

    private PlaybackState buildPlaybackState(Slot slot) {
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE;
        if (supports(slot, org.mozilla.geckoview.MediaSession.Feature.STOP)) {
            actions |= PlaybackState.ACTION_STOP;
        }
        if (supports(slot, org.mozilla.geckoview.MediaSession.Feature.SEEK_TO)) {
            actions |= PlaybackState.ACTION_SEEK_TO;
        }
        if (supports(slot, org.mozilla.geckoview.MediaSession.Feature.SEEK_FORWARD)) {
            actions |= PlaybackState.ACTION_FAST_FORWARD;
        }
        if (supports(slot, org.mozilla.geckoview.MediaSession.Feature.SEEK_BACKWARD)) {
            actions |= PlaybackState.ACTION_REWIND;
        }
        if (supports(slot, org.mozilla.geckoview.MediaSession.Feature.NEXT_TRACK)) {
            actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        }
        if (supports(slot, org.mozilla.geckoview.MediaSession.Feature.PREVIOUS_TRACK)) {
            actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        }

        int state = slot.playing
                ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        long position = slot.position > 0 && Double.isFinite(slot.position)
                ? Math.round(slot.position * 1000.0) : PlaybackState.PLAYBACK_POSITION_UNKNOWN;
        float speed = slot.playing ? (float) (slot.rate == 0 ? 1.0 : slot.rate) : 0f;
        return new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position, speed, SystemClock.elapsedRealtime())
                .build();
    }

    private void showNotification(Slot slot) {
        if (notifications == null) return;
        try {
            Intent open = new Intent(activity, MainActivity.class)
                    .setAction("com.example.geckobrowser.OPEN_MEDIA")
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent content = PendingIntent.getActivity(activity, 646, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(activity, CHANNEL)
                    : new Notification.Builder(activity);
            builder.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(displayTitle(slot, owner))
                    .setContentText(detail(slot))
                    .setContentIntent(content)
                    .setCategory(Notification.CATEGORY_TRANSPORT)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOnlyAlertOnce(true)
                    .setOngoing(slot.playing)
                    .setShowWhen(false);

            int playIndex;
            int actionIndex = 0;
            if (supports(slot, org.mozilla.geckoview.MediaSession.Feature.SEEK_BACKWARD)) {
                builder.addAction(action(android.R.drawable.ic_media_rew,
                        "Reculer", ACTION_REWIND));
                actionIndex++;
            }
            playIndex = actionIndex;
            builder.addAction(action(slot.playing
                            ? android.R.drawable.ic_media_pause
                            : android.R.drawable.ic_media_play,
                    slot.playing ? "Pause" : "Lecture", ACTION_PLAY_PAUSE));
            actionIndex++;
            if (supports(slot, org.mozilla.geckoview.MediaSession.Feature.SEEK_FORWARD)) {
                builder.addAction(action(android.R.drawable.ic_media_ff,
                        "Avancer", ACTION_FORWARD));
            }

            Notification.MediaStyle style = new Notification.MediaStyle()
                    .setMediaSession(platformSession.getSessionToken())
                    .setShowActionsInCompactView(playIndex);
            builder.setStyle(style);
            notifications.notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {
            // L'utilisateur peut avoir refuse POST_NOTIFICATIONS.
        } catch (Throwable ignored) { }
    }

    private Notification.Action action(int icon, String title, String action) {
        Intent intent = new Intent(activity, MediaActionReceiver.class).setAction(action);
        PendingIntent pending = PendingIntent.getBroadcast(activity, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(icon, title, pending).build();
    }

    private String displayTitle(Slot slot, GeckoSession session) {
        if (slot != null && !slot.title.isEmpty()) return slot.title;
        String fallback = activity.mediaTitleFor(session);
        return fallback == null || fallback.isEmpty() ? "Media web" : fallback;
    }

    private static String detail(Slot slot) {
        StringBuilder text = new StringBuilder();
        if (slot == null) return "";
        if (!slot.artist.isEmpty()) text.append(slot.artist);
        if (!slot.album.isEmpty()) {
            if (text.length() > 0) text.append(" · ");
            text.append(slot.album);
        }
        if (slot.duration > 0 && Double.isFinite(slot.duration)) {
            if (text.length() > 0) text.append(" · ");
            text.append(format(slot.position)).append(" / ").append(format(slot.duration));
        }
        if (text.length() == 0) text.append(slot.playing ? "Lecture en cours" : "En pause");
        return text.toString();
    }

    private static String format(double seconds) {
        long value = Math.max(0L, Math.round(seconds));
        long hours = value / 3600;
        long minutes = (value % 3600) / 60;
        long secs = value % 60;
        return hours > 0
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(java.util.Locale.ROOT, "%d:%02d", minutes, secs);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
