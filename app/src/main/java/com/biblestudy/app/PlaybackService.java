package com.biblestudy.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.util.List;

/**
 * Runs as a foreground service so Android does not kill audio playback when
 * the screen turns off or the app goes to the background. Also owns the
 * "advanced audio" pipeline: equalizer/bass/volume-boost (via
 * AudioEffectsController), mono downmix (via MonoDownmixAudioProcessor),
 * and gapless/fade chapter transitions (via ExoPlayer's playlist API).
 */
@OptIn(markerClass = UnstableApi.class)
public class PlaybackService extends MediaSessionService {

    private static final String SYSTEM_BIND_ACTION = "androidx.media3.session.MediaSessionService";
    private static final long FADE_DURATION_MS = 900;
    private static final long FADE_STEP_MS = 40;
    private static final long STALL_WATCHDOG_INTERVAL_MS = 4_000;

    private ExoPlayer player;
    private MediaSession mediaSession;
    private AudioEffectsController audioEffects;
    private MonoDownmixAudioProcessor monoProcessor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SkipListener skipListener;

    /**
     * "Previous/next chapter" is a concept the WebView's JS owns (it knows
     * the book/chapter list); ExoPlayer only ever has the current chapter
     * (plus maybe one pre-queued "next" for gapless). So the actual chapter
     * that a Bluetooth headset button or the notification's prev/next icon
     * should jump to isn't something the player itself can compute. Rather
     * than let ExoPlayer try (and mostly fail, since there's usually no
     * "previous" item queued at all), the skip is forwarded up to JS via
     * this listener, and JS decides the new chapter and calls back into
     * AudioBridge.play(...) with it — the same path a manual tap on the
     * in-app prev/next buttons already uses.
     */
    public interface SkipListener {
        void onSkipNext();
        void onSkipPrevious();
    }

    public void setSkipListener(SkipListener listener) {
        this.skipListener = listener;
    }

    /**
     * Wraps the real ExoPlayer for everything the MediaSession exposes to
     * the outside world (system notification, lock screen, Bluetooth
     * AVRCP). Two things it changes from the underlying player:
     *  1. Always reports skip-to-next/previous as available, even when
     *     ExoPlayer's own playlist has nothing queued in that direction —
     *     otherwise the notification greys the buttons out and a
     *     Bluetooth "next" press is simply dropped.
     *  2. Routes an actual next/previous press to `skipListener` (which
     *     forwards it to the WebView JS for real chapter navigation)
     *     instead of ExoPlayer's own seekToNext/Previous, which only
     *     knows how to move within its tiny internal playlist.
     */
    private final class ChapterAwarePlayer extends ForwardingPlayer {
        ChapterAwarePlayer(Player player) {
            super(player);
        }

        @Override
        public boolean isCommandAvailable(int command) {
            if (command == Player.COMMAND_SEEK_TO_NEXT
                    || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                    || command == Player.COMMAND_SEEK_TO_PREVIOUS
                    || command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
                return true;
            }
            return super.isCommandAvailable(command);
        }

        @Override
        public Commands getAvailableCommands() {
            return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build();
        }

        @Override
        public void seekToNext() {
            if (skipListener != null) skipListener.onSkipNext();
        }

        @Override
        public void seekToNextMediaItem() {
            if (skipListener != null) skipListener.onSkipNext();
        }

        @Override
        public void seekToPrevious() {
            if (skipListener != null) skipListener.onSkipPrevious();
        }

        @Override
        public void seekToPreviousMediaItem() {
            if (skipListener != null) skipListener.onSkipPrevious();
        }
    }

    private boolean crossfadeEnabled = false; // "crossfade" = fade-out/fade-in at chapter boundary; see class doc
    private boolean gaplessEnabled = true;
    private Runnable fadeRunnable;

    // "The audio just freezes on its own" reports on players like this are
    // almost never a JS/UI bug — the UI just keeps showing whatever it last
    // heard. The actual cause lives one level below what our own code
    // controls: the OS silently pausing ExoPlayer out from under us
    // (a brief audio-focus hiccup from another app/notification/OEM
    // background-restriction that ends without a matching "resume" ever
    // reaching us). ExoPlayer *should* auto-resume once it regains focus,
    // but on several OEM skins (MIUI/Samsung/etc.) that resume signal is
    // the thing that gets dropped, not the pause. Rather than chase every
    // individual OEM's audio-focus quirk, this watchdog just notices the
    // symptom directly — "we intend to be playing, we're fully loaded, but
    // we're not actually making sound" — and nudges play() again. It's a
    // no-op the other 99% of the time (isPlaying() is already true).
    // How long STATE_ENDED is allowed to sit with nothing queued before
    // this absolute-last-resort kicks in. AudioBridge already retries
    // handing "chapter ended" to JS for ~60s (see AudioBridge's
    // ENDED_RETRY_*), which is the real fix and covers the normal case of
    // a briefly throttled/delayed WebView. This is only for the rarer
    // case where the WebView renderer never comes back at all (crashed,
    // killed outright): rather than leave the app silent and looking
    // "closed" indefinitely, replay the chapter that was already loaded
    // so there is always something audible for the user to notice and
    // manually skip past, instead of dead air forever.
    private static final long ENDED_FALLBACK_MS = 75_000;
    private long endedSinceMs = -1L;

    private final Runnable stallWatchdog = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                if (player.getPlayWhenReady()
                        && player.getPlaybackState() == Player.STATE_READY
                        && !player.isPlaying()) {
                    player.play();
                }

                if (player.getPlaybackState() == Player.STATE_ENDED && player.getPlayWhenReady()) {
                    if (endedSinceMs < 0) {
                        endedSinceMs = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - endedSinceMs >= ENDED_FALLBACK_MS) {
                        endedSinceMs = -1L;
                        player.seekTo(0);
                        player.play();
                    }
                } else {
                    endedSinceMs = -1L;
                }
            }
            mainHandler.postDelayed(this, STALL_WATCHDOG_INTERVAL_MS);
        }
    };

    // ------------------------------------------------------------------
    // "Screen off -> chapter ends -> nothing plays next" fix.
    //
    // The instant ExoPlayer reaches STATE_ENDED with nothing left in its
    // playlist, two protections vanish at once:
    //   1. Media3 only keeps a MediaSessionService in the foreground while
    //      the player is READY/BUFFERING, so at ENDED it drops foreground
    //      status (and the process goes back to being an ordinary,
    //      freezable background app).
    //   2. ExoPlayer's own wake lock is only held while actually playing,
    //      so it is released at ENDED too - the CPU can go to sleep with
    //      the screen off.
    // The "chapter finished, please load the next one" message to the
    // WebView (AudioBridge's ended-retry) needs the CPU and the process
    // to be alive, so with the screen off it silently never got delivered
    // and playback stayed stopped until the phone was woken up.
    // Holding a short partial wake lock AND staying foreground for as long
    // as we are sitting in "ended but the listener still wants to play"
    // closes that gap.
    // ------------------------------------------------------------------
    private static final long ENDED_WAKELOCK_MS = 90_000;
    private PowerManager.WakeLock endedWakeLock;

    private void acquireEndedWakeLock() {
        try {
            if (endedWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                endedWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "biblestudy:chapterEnded");
                endedWakeLock.setReferenceCounted(false);
            }
            endedWakeLock.acquire(ENDED_WAKELOCK_MS);
        } catch (Throwable ignored) { }
    }

    private void releaseEndedWakeLock() {
        try {
            if (endedWakeLock != null && endedWakeLock.isHeld()) endedWakeLock.release();
        } catch (Throwable ignored) { }
    }

    @Override
    public void onUpdateNotification(MediaSession session, boolean startInForegroundRequired) {
        boolean endedButStillWanted = player != null
                && player.getPlayWhenReady()
                && player.getPlaybackState() == Player.STATE_ENDED;
        super.onUpdateNotification(session, startInForegroundRequired || endedButStillWanted);
    }

    public class LocalBinder extends Binder {
        PlaybackService getService() { return PlaybackService.this; }
    }
    private final IBinder localBinder = new LocalBinder();

    @Override
    public void onCreate() {
        super.onCreate();

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_MEDIA)
                .build();

        monoProcessor = new MonoDownmixAudioProcessor();

        // Wider buffer cushion than ExoPlayer's streaming-tuned defaults for
        // steady-state playback (absorbs brief CPU/GC pressure from the
        // WebView so it doesn't audibly stutter). BUT: bufferForPlayback-
        // AfterRebufferMs is what fires on every seek — every "click the
        // middle of the track" — and Media3's own default there is 5000ms,
        // meant for *network* buffering. These are local files already on
        // disk, so there's no reason to wait anywhere near that long: a
        // couple hundred ms of audio is available from local storage
        // almost instantly. That mismatch (a threshold tuned for network
        // streaming, applied to local files) is what "seek and it just
        // sits there for a second or two" actually was.
        LoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        /* minBufferMs= */ 30_000,
                        /* maxBufferMs= */ 90_000,
                        /* bufferForPlaybackMs= */ 1_000,
                        /* bufferForPlaybackAfterRebufferMs= */ 300)
                .build();

        // Custom audio sink so the mono downmix processor sits in the
        // actual output pipeline. Wrapped in a try/catch: if this specific
        // Media3 version's AudioSink API doesn't match exactly, we fall
        // back to a completely standard player instead of risking playback
        // itself breaking over one optional feature (mono downmix would
        // just not be available in that case — everything else still works).
        ExoPlayer builtPlayer;
        try {
            AudioSink audioSink = new DefaultAudioSink.Builder(this)
                    .setAudioProcessorChain(new DefaultAudioSink.DefaultAudioProcessorChain(monoProcessor))
                    .build();
            RenderersFactory renderersFactory = new DefaultRenderersFactory(this) {
                @Override
                protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                    return audioSink;
                }
            };
            builtPlayer = new ExoPlayer.Builder(this, renderersFactory)
                    .setLoadControl(loadControl)
                    .build();
        } catch (Throwable t) {
            builtPlayer = new ExoPlayer.Builder(this)
                    .setLoadControl(loadControl)
                    .build();
        }
        player = builtPlayer;
        player.setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true);
        player.setHandleAudioBecomingNoisy(true);
        // These are local files (imported onto the device), not a network
        // stream, so a local wake lock is the correct/lighter mode — the
        // network one was pointlessly holding a Wi-Fi lock during playback.
        player.setWakeMode(C.WAKE_MODE_LOCAL);

        audioEffects = new AudioEffectsController();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) audioEffects.attach(player);
                if (state == Player.STATE_ENDED && player.getPlayWhenReady()) {
                    acquireEndedWakeLock();
                } else {
                    releaseEndedWakeLock();
                }
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (!playWhenReady) releaseEndedWakeLock();
            }

            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // A gapless auto-advance to a queued next chapter just
                    // happened. Make sure volume is back to full (in case a
                    // fade was mid-flight) and let the UI know.
                    cancelFade();
                    player.setVolume(1f);
                }
            }
        });

        Intent openAppIntent = new Intent(this, MainActivity.class);
        PendingIntent sessionActivityPendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        mediaSession = new MediaSession.Builder(this, new ChapterAwarePlayer(player))
                .setSessionActivity(sessionActivityPendingIntent)
                .build();

        // REQUIRED for this app's setup. Media3 only posts the media
        // notification (and promotes the service to a real foreground
        // service) for sessions that have been *added* to the
        // MediaSessionService. Normally that happens automatically when a
        // MediaController connects through onGetSession() — but this app
        // never uses a MediaController: MainActivity binds straight to
        // LocalBinder and AudioBridge drives the ExoPlayer directly. So
        // without this line the session was never registered, no
        // notification was ever built (screen on OR off), and the service
        // never became foreground — leaving the process fully exposed to
        // being throttled/killed once the app left the screen.
        addSession(mediaSession);

        // Without this, the notification/lock-screen control falls back to
        // Media3's own generic music-note icon. This swaps in the app's
        // icon so the notification bar and lock screen are clearly
        // "Bible Study" and not an unbranded system default.
        DefaultMediaNotificationProvider notificationProvider =
                new DefaultMediaNotificationProvider.Builder(this).build();
        notificationProvider.setSmallIcon(R.drawable.ic_notification);
        setMediaNotificationProvider(notificationProvider);

        mainHandler.postDelayed(stallWatchdog, STALL_WATCHDOG_INTERVAL_MS);
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    public ExoPlayer getPlayer() { return player; }
    public AudioEffectsController getAudioEffects() { return audioEffects; }

    // ---- Gapless queue ----

    /** Replaces the whole playlist with a single item and starts it fresh (used for manual chapter picks / language switches). */
    public void playFresh(MediaItem item, long startMs, boolean autoPlay) {
        cancelFade();
        player.setVolume(1f);
        player.setMediaItem(item, startMs);
        player.prepare();
        // setPlayWhenReady (not play()) so a false autoPlay genuinely never
        // makes a sound — including the brief chirp that could happen if a
        // caller prepared, then paused, then something else here still
        // called play() a moment later.
        player.setPlayWhenReady(autoPlay);
    }

    /** Queues the next chapter so ExoPlayer can transition to it gaplessly when the current one ends (only meaningful if gapless is enabled). */
    public void queueNext(MediaItem item) {
        queueNext(item, null);
    }

    /**
     * Same as queueNext(item), but also appends a second item right behind
     * the first (item2 may be null to queue only one, same as before).
     *
     * This is what lets a bilingual chapter's lang1 -> lang2 -> next
     * chapter's lang1 all happen with zero JS involvement in between: both
     * hops get loaded into ExoPlayer's own playlist together, at the
     * moment lang1 starts, instead of the second hop only being queued
     * once JS wakes up, processes the lang1->lang2 auto-advance, and reacts
     * to it — which is what left playback stuck at the end of lang2 with
     * the screen off (JS throttled = nothing ever got queued for the
     * chapter after it).
     */
    public void queueNext(MediaItem item, @Nullable MediaItem item2) {
        if (!gaplessEnabled) return;
        // Never append onto a player currently sitting in STATE_ENDED (or
        // STATE_IDLE). ExoPlayer treats "add a media item after the
        // current one, when the current one already ended" as new work to
        // resume into immediately (a real MEDIA_ITEM_TRANSITION_REASON_AUTO
        // advance) rather than something queued for later - so this item
        // would start playing right now, not "next".
        //
        // This is exactly the window that exists the instant lang1 ends
        // and JS switches the stage to lang2: the bridge's play() call for
        // lang2 is intentionally debounced (CHAPTER_LOAD_DEBOUNCE_MS, see
        // AudioBridge.play) so a burst of manual chapter taps coalesces
        // into one load, but that means ExoPlayer can sit in STATE_ENDED
        // for ~120ms with the *old* (lang1) single-item playlist still
        // loaded before playFresh() actually swaps in lang2. If the
        // gapless pre-queue effect's queueNext() call for the chapter
        // *after* this one lands in that window - it isn't debounced at
        // all - ExoPlayer auto-advances straight into next chapter's 1st
        // language, skipping lang2 entirely, and JS updates its own
        // book/chapter state to match right before the delayed playFresh()
        // for lang2 finally fires and forces the player back - leaving JS
        // and native disagreeing about what chapter is even playing.
        //
        // Bailing out here is safe: this pre-queue is purely a gapless
        // nice-to-have, and skipping it just means the eventual real
        // transition (still driven by handleEnded/AndroidAudio.play on the
        // JS side) falls back to a normal, non-gapless chapter change
        // instead of a silently wrong one.
        int state = player.getPlaybackState();
        if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) return;
        int count = player.getMediaItemCount();
        int currentIndex = player.getCurrentMediaItemIndex();
        // Drop anything already queued after the current item, then queue the new "next".
        if (count > currentIndex + 1) {
            player.removeMediaItems(currentIndex + 1, count);
        }
        player.addMediaItem(item);
        if (item2 != null) player.addMediaItem(item2);

        if (crossfadeEnabled) scheduleFadeOutNearEnd();
    }

    /**
     * Queues SEVERAL upcoming items (this chapter's 2nd language, then the
     * next few chapters, each in 1st + 2nd language) in one go, replacing
     * whatever was queued after the current item. Because ExoPlayer then
     * has several chapters lined up natively, playback keeps going with the
     * screen off even if the WebView's JavaScript is throttled for a whole
     * chapter or two - it no longer needs a JS round trip at every single
     * hop just to stay one step ahead.
     *
     * expectedCurrentId guards against a stale/late JS call: if JS's idea
     * of "what is playing now" no longer matches what ExoPlayer is really
     * on (native already advanced), applying its queue would splice the
     * wrong items in and replay/skip chapters, so it is ignored - JS gets
     * an onAutoAdvanced event, re-syncs, and queues again from the right
     * place.
     */
    public void queueItems(List<MediaItem> items, @Nullable String expectedCurrentId) {
        if (!gaplessEnabled || items == null || items.isEmpty()) return;
        int state = player.getPlaybackState();
        // Same reasoning as queueNext(): never append onto an ended/idle
        // player, it would start playing immediately instead of "next".
        if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) return;
        MediaItem current = player.getCurrentMediaItem();
        if (expectedCurrentId != null && current != null
                && !expectedCurrentId.equals(current.mediaId)) {
            return;
        }
        int count = player.getMediaItemCount();
        int currentIndex = player.getCurrentMediaItemIndex();
        if (count > currentIndex + 1) {
            player.removeMediaItems(currentIndex + 1, count);
        }
        for (MediaItem item : items) player.addMediaItem(item);
        if (crossfadeEnabled) scheduleFadeOutNearEnd();
    }

    public void clearQueuedNext() {
        int count = player.getMediaItemCount();
        int currentIndex = player.getCurrentMediaItemIndex();
        if (count > currentIndex + 1) player.removeMediaItems(currentIndex + 1, count);
        cancelFade();
    }

    public void setGaplessEnabled(boolean enabled) { gaplessEnabled = enabled; }
    public void setCrossfadeEnabled(boolean enabled) {
        crossfadeEnabled = enabled;
        if (!enabled) cancelFade();
    }

    // ---- Fade transition ("crossfade") ----
    // Spoken-word chapters overlapping (true dual-voice crossfade) tends to
    // sound like two people talking over each other, so this implements a
    // fade-out-then-fade-in at the boundary instead — smoother than an
    // abrupt cut, without stepping on the next chapter's opening words.
    private void scheduleFadeOutNearEnd() {
        cancelFade();
        fadeRunnable = new Runnable() {
            @Override
            public void run() {
                if (player == null || !crossfadeEnabled) return;
                long remaining = player.getDuration() - player.getCurrentPosition();
                if (remaining <= FADE_DURATION_MS && remaining > 0 && player.getMediaItemCount() > player.getCurrentMediaItemIndex() + 1) {
                    float progress = 1f - (float) remaining / FADE_DURATION_MS;
                    player.setVolume(Math.max(0f, 1f - progress));
                }
                mainHandler.postDelayed(this, FADE_STEP_MS);
            }
        };
        mainHandler.postDelayed(fadeRunnable, FADE_STEP_MS);
    }

    private void cancelFade() {
        if (fadeRunnable != null) mainHandler.removeCallbacks(fadeRunnable);
        fadeRunnable = null;
    }

    public void setMonoEnabled(boolean enabled) {
        if (monoProcessor != null) monoProcessor.setMonoEnabled(enabled);
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        if (intent != null && SYSTEM_BIND_ACTION.equals(intent.getAction())) {
            return super.onBind(intent);
        }
        return localBinder;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (player == null || !player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        cancelFade();
        mainHandler.removeCallbacks(stallWatchdog);
        releaseEndedWakeLock();
        if (audioEffects != null) audioEffects.release();
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}
