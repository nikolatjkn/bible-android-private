package com.biblestudy.app;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Exposed to the web page as window.AndroidAudio (see MainActivity).
 * All calls hop onto the main thread since ExoPlayer must be driven from
 * the thread it was created on; the WebView JS-bridge calls arrive on a
 * background thread.
 *
 * Native -> JS: player state changes are pushed to
 * window.NativeAudioEvents.* rather than exposed as synchronous getters,
 * since polling ExoPlayer from a background thread is unsafe.
 */
@OptIn(markerClass = UnstableApi.class)
public class AudioBridge {

    private final MainActivity activity;
    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PlaybackService service;
    private Runnable positionTicker;
    private final Uri artworkUri;

    // ExoPlayer's getCurrentPosition() doesn't update instantly once a seek
    // is issued — it's still resolving for a beat. If skipBy() is called
    // again in that window (rapid taps on the 10s button), basing the new
    // target on getCurrentPosition() reads the pre-seek position, so
    // several fast taps can collapse into far less net movement than
    // intended. This tracks the last *requested* target instead, so each
    // tap accumulates from where we just told the player to go, not from
    // wherever it physically still is.
    private long pendingSeekTargetMs = C.TIME_UNSET;

    // Coalesces a burst of rapid ±10s taps into a single real ExoPlayer
    // seek instead of one seek per tap. The JS side already updates its
    // own optimistic UI position instantly on every tap (see skip() in
    // index.html), so nothing visible waits on this — only the actual
    // engine-level seekTo() call is delayed/collapsed.
    private static final long SKIP_SEEK_DEBOUNCE_MS = 90;
    private Runnable pendingSkipSeekRunnable;

    // Coalesces a burst of rapid chapter changes (e.g. someone tapping
    // "next" several times fast) into a single playFresh() instead of
    // preparing several different chapters back-to-back in quick
    // succession.
    private static final long CHAPTER_LOAD_DEBOUNCE_MS = 120;
    private Runnable pendingPlayRunnable;

    // True from the moment JS asks for a fresh load until the debounced
    // playFresh() above actually runs. While a load is pending, the player
    // is still holding the OLD item (often in STATE_ENDED/IDLE), so a
    // queueNext() arriving in that window can't be applied — the service
    // (correctly) refuses to append onto an ended/idle player. Previously
    // that call was simply dropped, and because JS only queues once per
    // stage/chapter, nothing ever re-queued it. Net effect: after any
    // JS-driven load (manual pick, cold start, or the JS fallback for the
    // lang1->lang2 switch) the gapless "next" was silently missing, so the
    // last leg of the chapter ended with nothing after it and playback
    // stopped whenever JS wasn't awake to move on (screen off). The
    // queueNext() is now parked here and applied right after the load.
    private boolean loadPending = false;
    private MediaItem deferredNext = null;
    private MediaItem deferredNext2 = null;
    // Same idea as deferredNext, for the multi-chapter queueItems() path.
    private List<MediaItem> deferredItems = null;
    private String deferredExpectedId = null;

    AudioBridge(MainActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        // Points at the packaged notification_art.png (res/drawable-nodpi),
        // not anything inside the WebView's asset bundle — the lock
        // screen/notification renderer needs a real Android resource URI
        // it can decode on its own, not a webview-only
        // appassets.androidplatform.net URL.
        this.artworkUri = Uri.parse("android.resource://" + activity.getPackageName() + "/drawable/notification_art");
    }

    void attachService(PlaybackService svc) {
        this.service = svc;
        final ExoPlayer player = svc.getPlayer();

        // Bluetooth-headset buttons and the system notification's
        // prev/next icons land here (see PlaybackService.ChapterAwarePlayer),
        // not as ExoPlayer seeks — chapter navigation only exists in JS, so
        // forward the press up to it the same way a manual tap would.
        svc.setSkipListener(new PlaybackService.SkipListener() {
            @Override
            public void onSkipNext() {
                dispatch("onSkipNext", "");
            }

            @Override
            public void onSkipPrevious() {
                dispatch("onSkipPrevious", "");
            }
        });

        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                // ExoPlayer also flips isPlaying to false during a brief
                // buffering stall even though playback is still intended
                // (playWhenReady stays true). Forwarding that blip to JS as
                // a real "paused" makes any chapter-navigation that reads
                // the JS "playing" state at that instant wrongly conclude
                // playback should stop, so only forward genuine pauses.
                if (!isPlaying && player.getPlayWhenReady()
                        && player.getPlaybackState() == Player.STATE_BUFFERING) {
                    return;
                }
                dispatch("onPlayingChanged", isPlaying ? "true" : "false");
                if (isPlaying) startPositionTicker(); else stopPositionTicker();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    stopEndedRetry();
                    dispatch("onReady", String.valueOf(player.getDuration()));
                } else if (state == Player.STATE_ENDED) {
                    startEndedRetry();
                } else {
                    stopEndedRetry();
                }
            }

            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null && mediaItem.mediaId != null) {
                    // Gapless auto-advanced to a queued chapter. Tell JS which
                    // one, so it can update its own "current chapter" state
                    // without re-issuing play() (which would restart it).
                    dispatch("onAutoAdvanced", mediaItem.mediaId);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                dispatch("onError", error.getMessage() == null ? "playback error" : error.getMessage());
            }
        });
    }

    private void startPositionTicker() {
        stopPositionTicker();
        positionTicker = new Runnable() {
            @Override
            public void run() {
                if (service == null) return;
                ExoPlayer p = service.getPlayer();
                long position = p.getCurrentPosition();
                long duration = p.getDuration();
                // Once the real player has caught up to whatever we last
                // asked it to seek to, stop treating that target as
                // authoritative — later skipBy() calls should accumulate
                // from the live position again, not a now-stale one.
                if (pendingSeekTargetMs != C.TIME_UNSET
                        && (duration == C.TIME_UNSET || Math.abs(position - pendingSeekTargetMs) <= 500)) {
                    pendingSeekTargetMs = C.TIME_UNSET;
                }
                dispatch("onPosition", position + "," + duration);
                // The seek bar/time label no longer depend on this tick for
                // smoothness — the JS side interpolates the position itself
                // every animation frame (see the anchor/rAF loop in
                // index.html) and only uses this as a periodic correction.
                // 200ms meant 5 WebView JS-bridge calls/sec competing with
                // the WebView's own main thread for no visible benefit;
                // 500ms is still an imperceptible correction interval.
                mainHandler.postDelayed(this, 500);
            }
        };
        mainHandler.post(positionTicker);
    }

    private void stopPositionTicker() {
        if (positionTicker != null) mainHandler.removeCallbacks(positionTicker);
    }

    private void dispatch(String event, String arg) {
        if (webView == null) return;
        String js = "window.NativeAudioEvents && window.NativeAudioEvents." + event + "('" + escape(arg) + "')";
        mainHandler.post(() -> webView.evaluateJavascript(js, null));
    }

    // ---- Resilient "chapter ended" delivery ----
    //
    // Whether the next thing to play is the chapter's 2nd language leg or
    // a fresh next chapter, that decision is made in JS (see
    // AUDIO_IDS/hasSecondLeg handling in index.html) and reached by a
    // single native->JS evaluateJavascript("onEnded") call. That is a
    // fire-and-forget hop into a WebView renderer that can be briefly
    // deprioritized by the OS right when the screen goes off or the app
    // is backgrounded (a completely separate process/priority from
    // PlaybackService's own foreground-service protection). If that one
    // call is dropped or merely delayed past the moment ExoPlayer reports
    // STATE_ENDED, playback used to just stop dead until the app was
    // reopened. Retrying the same "onEnded" dispatch every 1.5s, stopping
    // as soon as the player state actually moves off STATE_ENDED (i.e.
    // something new got loaded), means a delayed-but-not-actually-dead
    // WebView still gets the message and playback self-recovers without
    // the user having to reopen the app.
    private static final long ENDED_RETRY_INTERVAL_MS = 1_500;
    private static final int ENDED_RETRY_MAX_ATTEMPTS = 40; // ~60s of retrying
    private Runnable endedRetryRunnable;
    private int endedRetryAttempts;

    private void startEndedRetry() {
        stopEndedRetry();
        endedRetryAttempts = 0;
        endedRetryRunnable = new Runnable() {
            @Override
            public void run() {
                if (service == null) return;
                dispatch("onEnded", "");
                endedRetryAttempts++;
                if (endedRetryAttempts < ENDED_RETRY_MAX_ATTEMPTS
                        && service.getPlayer().getPlaybackState() == Player.STATE_ENDED) {
                    mainHandler.postDelayed(this, ENDED_RETRY_INTERVAL_MS);
                }
            }
        };
        mainHandler.post(endedRetryRunnable);
    }

    private void stopEndedRetry() {
        if (endedRetryRunnable != null) mainHandler.removeCallbacks(endedRetryRunnable);
        endedRetryRunnable = null;
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private MediaItem buildItem(String url, String mediaId, String title, String artist) {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(artworkUri)
                .build();
        return new MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setMediaId(mediaId != null ? mediaId : url)
                .setMediaMetadata(metadata)
                .build();
    }

    // ---- Playback ----

    /**
     * @param autoPlay whether playback should actually start once the item
     *                 is prepared. False loads the chapter (so seeking,
     *                 metadata, and the notification/lock-screen controls
     *                 are all ready) without making any sound — used when
     *                 the web page opens a chapter that shouldn't resume
     *                 audio on its own (e.g. app cold-start).
     */
    @JavascriptInterface
    public void play(String url, String mediaId, String title, String artist, long startPositionMs, boolean autoPlay) {
        mainHandler.post(() -> {
            // Cancel whatever previous chapter-load was still waiting to
            // fire — if the user tapped next/prev/a chapter picker several
            // times fast, only the last one should ever actually reach
            // ExoPlayer. Without this, a burst of taps could queue up
            // several back-to-back setMediaItem()+prepare() calls, which is
            // exactly the kind of overlap that produces an audible hiccup.
            if (pendingPlayRunnable != null) mainHandler.removeCallbacks(pendingPlayRunnable);
            // A brand-new load supersedes anything parked for the old one.
            // (JS calls queueNext() AFTER play() for the same render, and
            // both hop through mainHandler in order, so the right "next"
            // arrives after this line and is parked below.)
            deferredNext = null;
            deferredNext2 = null;
            deferredItems = null;
            deferredExpectedId = null;
            loadPending = true;
            pendingPlayRunnable = () -> {
                loadPending = false;
                if (service == null) return;
                service.playFresh(buildItem(url, mediaId, title, artist), Math.max(0, startPositionMs), autoPlay);
                if (deferredNext != null) {
                    MediaItem parked = deferredNext;
                    MediaItem parked2 = deferredNext2;
                    deferredNext = null;
                    deferredNext2 = null;
                    service.queueNext(parked, parked2);
                }
                if (deferredItems != null) {
                    List<MediaItem> parkedItems = deferredItems;
                    String parkedExpected = deferredExpectedId;
                    deferredItems = null;
                    deferredExpectedId = null;
                    service.queueItems(parkedItems, parkedExpected);
                }
            };
            mainHandler.postDelayed(pendingPlayRunnable, CHAPTER_LOAD_DEBOUNCE_MS);
        });
    }

    /** Queues the given chapter to play next, gaplessly, once the current one finishes (used for auto-play-next-chapter). */
    @JavascriptInterface
    public void queueNext(String url, String mediaId, String title, String artist) {
        queueNext(url, mediaId, title, artist, null, null, null, null);
    }

    /**
     * Same as queueNext(...), but also chains a second item right behind
     * the first (pass null for the url2 group to queue only one item, same
     * as the 4-arg form above). Used to queue lang1's chapter -> lang2 ->
     * next chapter's lang1 all in a single native call, so the whole chain
     * survives the screen turning off with no JS round trip needed between
     * hops. See PlaybackService.queueNext(item, item2) for why this matters.
     */
    @JavascriptInterface
    public void queueNext(String url, String mediaId, String title, String artist,
                           String url2, String mediaId2, String title2, String artist2) {
        mainHandler.post(() -> {
            if (service == null) return;
            MediaItem item = buildItem(url, mediaId, title, artist);
            MediaItem item2 = (url2 != null && !url2.isEmpty())
                    ? buildItem(url2, mediaId2, title2, artist2) : null;
            if (loadPending) {
                // See loadPending above: park it, applied right after
                // playFresh() so it can't be dropped by the ended/idle guard.
                deferredNext = item;
                deferredNext2 = item2;
                return;
            }
            service.queueNext(item, item2);
        });
    }

    /**
     * Queues several upcoming items at once (see PlaybackService.queueItems).
     *
     * @param expectedCurrentId mediaId JS believes is playing right now; the
     *                          queue is ignored if native is already elsewhere.
     * @param json JSON array of {url, id, title, artist}, in play order.
     */
    @JavascriptInterface
    public void queueItems(String expectedCurrentId, String json) {
        mainHandler.post(() -> {
            if (service == null) return;
            List<MediaItem> items = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    items.add(buildItem(
                            o.getString("url"),
                            o.optString("id", null),
                            o.optString("title", ""),
                            o.optString("artist", "")));
                }
            } catch (Exception e) {
                return;
            }
            if (loadPending) {
                deferredItems = items;
                deferredExpectedId = expectedCurrentId;
                return;
            }
            service.queueItems(items, expectedCurrentId);
        });
    }

    @JavascriptInterface
    public void clearQueuedNext() {
        mainHandler.post(() -> {
            deferredNext = null;
            deferredNext2 = null;
            deferredItems = null;
            deferredExpectedId = null;
            if (service != null) service.clearQueuedNext();
        });
    }

    @JavascriptInterface
    public void pause() {
        mainHandler.post(() -> { if (service != null) service.getPlayer().pause(); });
    }

    @JavascriptInterface
    public void resume() {
        mainHandler.post(() -> { if (service != null) service.getPlayer().play(); });
    }

    @JavascriptInterface
    public void seekTo(long ms) {
        mainHandler.post(() -> {
            if (service == null) return;
            ExoPlayer p = service.getPlayer();
            boolean wasPlaying = p.getPlayWhenReady();
            long target = clampSeekTarget(p, ms);
            pendingSeekTargetMs = target;
            p.seekTo(target);
            // ExoPlayer keeps playing across an ordinary seek on its own
            // whenever playWhenReady is already true — calling play() again
            // here unconditionally, on every single seek, was redundant and
            // is itself a plausible source of an audible hiccup right at
            // the seek point. The one case that genuinely needs a nudge is
            // seeking right at/near the true end, which can leave ExoPlayer
            // sitting in STATE_ENDED — so only reassert play() then.
            reassertIfEnded(p, wasPlaying);
        });
    }

    @JavascriptInterface
    public void skipBy(long ms) {
        mainHandler.post(() -> {
            if (service == null) return;
            ExoPlayer p = service.getPlayer();
            // Accumulate from the last *requested* target (if one is still
            // in flight) rather than getCurrentPosition(), so a burst of
            // taps adds up correctly instead of several of them reading
            // the same stale pre-seek position.
            long basePosition = (pendingSeekTargetMs != C.TIME_UNSET) ? pendingSeekTargetMs : p.getCurrentPosition();
            long target = clampSeekTarget(p, basePosition + ms);
            pendingSeekTargetMs = target;

            // Debounce the actual engine seek: a rapid burst of taps only
            // needs to reach ExoPlayer once, with the final accumulated
            // target, not once per tap.
            if (pendingSkipSeekRunnable != null) mainHandler.removeCallbacks(pendingSkipSeekRunnable);
            pendingSkipSeekRunnable = () -> {
                if (service == null) return;
                ExoPlayer player = service.getPlayer();
                boolean wasPlaying = player.getPlayWhenReady();
                player.seekTo(target);
                reassertIfEnded(player, wasPlaying);
            };
            mainHandler.postDelayed(pendingSkipSeekRunnable, SKIP_SEEK_DEBOUNCE_MS);
        });
    }

    /** See seekTo()/skipBy() above — only nudges play() back on when a seek left the player stuck in STATE_ENDED, instead of unconditionally on every seek. */
    private void reassertIfEnded(ExoPlayer p, boolean wasPlaying) {
        if (!wasPlaying) return;
        mainHandler.post(() -> {
            if (p.getPlaybackState() == Player.STATE_ENDED) p.play();
        });
    }

    /**
     * Keeps a seek target inside [0, duration). Landing exactly on or past
     * the last sample tips ExoPlayer straight into STATE_ENDED, which is
     * what made seeking near the end of a chapter feel unreliable — so we
     * leave a small safety margin before the true end instead.
     */
    private long clampSeekTarget(ExoPlayer p, long ms) {
        long target = Math.max(0, ms);
        long dur = p.getDuration();
        if (dur != C.TIME_UNSET && dur > 0 && target >= dur) {
            target = Math.max(0, dur - 300);
        }
        return target;
    }

    @JavascriptInterface
    public void setSpeed(float speed) {
        mainHandler.post(() -> { if (service != null) service.getPlayer().setPlaybackSpeed(speed); });
    }

    /** 0f (silent) .. 1f (full). The JS side folds "muted" into this by passing 0. */
    @JavascriptInterface
    public void setVolume(float volume) {
        mainHandler.post(() -> { if (service != null) service.getPlayer().setVolume(Math.max(0f, Math.min(1f, volume))); });
    }

    @JavascriptInterface
    public void setRepeatMode(int mode) {
        // 0 = Player.REPEAT_MODE_OFF, 1 = REPEAT_MODE_ONE, 2 = REPEAT_MODE_ALL
        mainHandler.post(() -> { if (service != null) service.getPlayer().setRepeatMode(mode); });
    }

    // ---- Advanced audio ----

    @JavascriptInterface
    public void setGaplessEnabled(boolean enabled) {
        mainHandler.post(() -> { if (service != null) service.setGaplessEnabled(enabled); });
    }

    @JavascriptInterface
    public void setCrossfadeEnabled(boolean enabled) {
        mainHandler.post(() -> { if (service != null) service.setCrossfadeEnabled(enabled); });
    }

    @JavascriptInterface
    public void setMonoEnabled(boolean enabled) {
        mainHandler.post(() -> { if (service != null) service.setMonoEnabled(enabled); });
    }

    /** 0-1000, Android's native BassBoost strength range. */
    @JavascriptInterface
    public void setBass(int strength) {
        mainHandler.post(() -> { if (service != null && service.getAudioEffects() != null) service.getAudioEffects().setBass(strength); });
    }

    /** millibels of gain on the top EQ band, roughly -1500..1500 depending on device. */
    @JavascriptInterface
    public void setTreble(int millibels) {
        mainHandler.post(() -> { if (service != null && service.getAudioEffects() != null) service.getAudioEffects().setTreble(millibels); });
    }

    /** millibels of extra gain, 0 = off. Helps quiet recordings; large values can distort. */
    @JavascriptInterface
    public void setVolumeBoost(int millibels) {
        mainHandler.post(() -> { if (service != null && service.getAudioEffects() != null) service.getAudioEffects().setVolumeBoost(millibels); });
    }
}
