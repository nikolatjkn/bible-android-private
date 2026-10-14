package com.biblestudy.app;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Keeps the channel count/sample rate unchanged (so it plugs into any
 * output device with no surprises) but, when enabled, replaces every
 * channel's sample with the average of all channels — so a stereo
 * recording plays identically out of both the left and right side. Useful
 * for anyone listening with one earbud/one working ear.
 */
@UnstableApi
public class MonoDownmixAudioProcessor extends BaseAudioProcessor {

    private volatile boolean monoEnabled = false;

    public void setMonoEnabled(boolean enabled) {
        monoEnabled = enabled;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        return inputAudioFormat; // same channel count/sample rate — we only mix content, not layout
    }

    // Only join the active pipeline when mono mode is actually on for a
    // format we know how to downmix (16-bit PCM, 2+ channels). When this
    // is false — which is the default, and the case for every chapter
    // until someone turns Mono on — ExoPlayer bypasses this processor
    // completely: queueInput is never called, so a bug in the downmix
    // math below can never break ordinary playback.
    @Override
    public boolean isActive() {
        return monoEnabled
                && inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
                && inputAudioFormat.channelCount >= 2;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int channelCount = inputAudioFormat.channelCount;
        int frameSize = channelCount * 2; // 16-bit samples
        int usableBytes = (inputBuffer.remaining() / frameSize) * frameSize;

        if (usableBytes <= 0) {
            // Less than one whole frame available (can happen at a buffer
            // boundary) — nothing safe to average; drop it rather than
            // risk reading a partial sample.
            inputBuffer.position(inputBuffer.limit());
            return;
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer outputBuffer = replaceOutputBuffer(usableBytes);
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN);

        int originalLimit = inputBuffer.limit();
        inputBuffer.limit(inputBuffer.position() + usableBytes);
        while (inputBuffer.hasRemaining()) {
            int sum = 0;
            for (int c = 0; c < channelCount; c++) sum += inputBuffer.getShort();
            short avg = (short) (sum / channelCount);
            for (int c = 0; c < channelCount; c++) outputBuffer.putShort(avg);
        }
        inputBuffer.limit(originalLimit);
        inputBuffer.position(inputBuffer.limit()); // fully consumed, including any dropped trailing partial frame
        outputBuffer.flip();
    }
}
