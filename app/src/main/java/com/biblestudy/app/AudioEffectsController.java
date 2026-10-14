package com.biblestudy.app;

import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Bass/treble/volume-boost use Android's built-in AudioEffect classes,
 * attached to ExoPlayer's audio session id. This is the same mechanism
 * apps like Spotify/YouTube Music use for their EQ screens — no signal
 * processing code of our own needed for these three.
 *
 * "Treble" isn't a distinct Android effect the way BassBoost is, so it's
 * implemented as a gain boost on the Equalizer's highest frequency band.
 */
public class AudioEffectsController {

    private Equalizer equalizer;
    private BassBoost bassBoost;
    private LoudnessEnhancer loudnessEnhancer;
    private int attachedSessionId = -1;

    private short trebleBandIndex = -1;
    private short bassRequestedStrength = 0;     // 0-1000
    private short trebleRequestedMillibels = 0;  // relative to band's own range
    private int volumeBoostRequestedMb = 0;      // millibels, 0 = off

    public void attach(ExoPlayer player) {
        int sessionId = player.getAudioSessionId();
        if (sessionId == attachedSessionId || sessionId == 0) return;
        release();
        attachedSessionId = sessionId;
        // Effects are constructed up front (so they're ready the moment the
        // user turns one on) but left DISABLED here. Leaving all three
        // enabled at all times — even at a "0" setting — routes every
        // chapter through three extra DSP stages on the output path for no
        // audible benefit, and on some OEM audio stacks (Samsung/MIUI in
        // particular) that idle-but-enabled effect chain is a known source
        // of crackling/distortion. setBass/setTreble/setVolumeBoost below
        // are what actually flip each one on, and only when the user has
        // asked for something other than the default.
        try {
            equalizer = new Equalizer(0, sessionId);
            equalizer.setEnabled(false);
            short bands = equalizer.getNumberOfBands();
            if (bands > 0) trebleBandIndex = (short) (bands - 1);
        } catch (Exception ignored) { /* device without an equalizer effect */ }

        try {
            bassBoost = new BassBoost(0, sessionId);
            bassBoost.setEnabled(false);
        } catch (Exception ignored) { }

        try {
            loudnessEnhancer = new LoudnessEnhancer(sessionId);
            loudnessEnhancer.setEnabled(false);
        } catch (Exception ignored) { }

        // Re-apply whatever the user had set before this session id existed
        // (e.g. right after the very first track starts playing).
        setBass(bassRequestedStrength);
        setTreble(trebleRequestedMillibels);
        setVolumeBoost(volumeBoostRequestedMb);
    }

    /** strength: 0-1000 (Android's native BassBoost range). 0 turns the effect off entirely rather than leaving it enabled at zero strength. */
    public void setBass(int strength) {
        bassRequestedStrength = (short) Math.max(0, Math.min(1000, strength));
        if (bassBoost != null) {
            try {
                boolean on = bassRequestedStrength > 0;
                if (bassBoost.getEnabled() != on) bassBoost.setEnabled(on);
                if (on) bassBoost.setStrength(bassRequestedStrength);
            } catch (Exception ignored) { }
        }
    }

    /** millibels of gain applied to the top EQ band, e.g. -1500..1500. 0 turns the equalizer off entirely rather than leaving it enabled at a flat response. */
    public void setTreble(int millibels) {
        trebleRequestedMillibels = (short) millibels;
        if (equalizer != null && trebleBandIndex >= 0) {
            try {
                boolean on = millibels != 0;
                if (equalizer.getEnabled() != on) equalizer.setEnabled(on);
                if (on) {
                    short[] range = equalizer.getBandLevelRange();
                    short clamped = (short) Math.max(range[0], Math.min(range[1], millibels));
                    equalizer.setBandLevel(trebleBandIndex, clamped);
                }
            } catch (Exception ignored) { }
        }
    }

    /** millibels, typically 0-2000; use sparingly, this can clip/distort quiet-mic recordings if pushed too far. 0 turns the effect off entirely. */
    public void setVolumeBoost(int millibels) {
        volumeBoostRequestedMb = Math.max(0, millibels);
        if (loudnessEnhancer != null) {
            try {
                boolean on = volumeBoostRequestedMb > 0;
                if (loudnessEnhancer.getEnabled() != on) loudnessEnhancer.setEnabled(on);
                if (on) loudnessEnhancer.setTargetGain(volumeBoostRequestedMb);
            } catch (Exception ignored) { }
        }
    }

    public void release() {
        if (equalizer != null) { try { equalizer.release(); } catch (Exception ignored) { } equalizer = null; }
        if (bassBoost != null) { try { bassBoost.release(); } catch (Exception ignored) { } bassBoost = null; }
        if (loudnessEnhancer != null) { try { loudnessEnhancer.release(); } catch (Exception ignored) { } loudnessEnhancer = null; }
        attachedSessionId = -1;
    }
}
