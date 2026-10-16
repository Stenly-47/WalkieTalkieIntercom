package com.stanly.walkietalkie;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

final class PcmPlayer {
    private AudioTrack audioTrack;

    synchronized void start() {
        if (audioTrack != null) {
            return;
        }
        int minBuffer = AudioTrack.getMinBufferSize(
                AudioPacket.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int trackBuffer = Math.max(minBuffer, 4096);
        audioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                AudioPacket.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                trackBuffer,
                AudioTrack.MODE_STREAM
        );
        audioTrack.play();
    }

    synchronized void play(byte[] pcm, int length) {
        if (audioTrack == null) {
            start();
        }
        if (audioTrack != null && length > 0) {
            audioTrack.write(pcm, 0, length);
        }
    }

    synchronized void stop() {
        if (audioTrack == null) {
            return;
        }
        try {
            audioTrack.stop();
        } catch (IllegalStateException ignored) {
        }
        audioTrack.release();
        audioTrack = null;
    }
}
