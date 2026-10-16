package com.stanly.walkietalkie;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

final class InternetAudioSender {
    interface Callback {
        void onSenderError(String message);
    }

    private static final int AUDIO_FRAME_BYTES = 320;

    private final InternetRelayClient relayClient;
    private final Callback callback;
    private final boolean voxMode;
    private final int voxThreshold;

    private volatile boolean talking;
    private Thread thread;
    private AudioRecord recorder;

    InternetAudioSender(InternetRelayClient relayClient, Callback callback, boolean voxMode, int voxThreshold) {
        this.relayClient = relayClient;
        this.callback = callback;
        this.voxMode = voxMode;
        this.voxThreshold = voxThreshold;
    }

    synchronized void start() {
        if (talking) {
            return;
        }
        talking = true;
        thread = new Thread(this::runLoop, "walkie-internet-audio-sender");
        thread.start();
    }

    synchronized void stop() {
        talking = false;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        if (thread != null) {
            try {
                thread.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    private void runLoop() {
        int minBuffer = AudioRecord.getMinBufferSize(
                AudioPacket.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int recordBuffer = Math.max(minBuffer, AUDIO_FRAME_BYTES * 4);
        byte[] audioBuffer = new byte[AUDIO_FRAME_BYTES];
        int sequence = 0;

        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    AudioPacket.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recordBuffer
            );
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                callback.onSenderError("Mikrofon tidak bisa dibuka.");
                return;
            }
            recorder.startRecording();

            while (talking) {
                int read = recorder.read(audioBuffer, 0, audioBuffer.length);
                if (read <= 0) {
                    continue;
                }
                if (voxMode && !isVoiceFrame(audioBuffer, read)) {
                    continue;
                }
                relayClient.sendAudio(audioBuffer, read, sequence++);
            }
        } catch (SecurityException e) {
            callback.onSenderError("Izin mikrofon belum diberikan.");
        } catch (Exception e) {
            if (talking) {
                callback.onSenderError("Gagal mengirim audio internet: " + e.getMessage());
            }
        } finally {
            if (recorder != null) {
                recorder.release();
                recorder = null;
            }
            talking = false;
        }
    }

    private boolean isVoiceFrame(byte[] audioBuffer, int length) {
        long total = 0;
        int samples = 0;
        for (int index = 0; index + 1 < length; index += 2) {
            int sample = (audioBuffer[index] & 0xFF) | (audioBuffer[index + 1] << 8);
            total += Math.abs(sample);
            samples++;
        }
        return samples > 0 && total / samples >= voxThreshold;
    }
}
