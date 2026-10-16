package com.stanly.walkietalkie;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

final class AudioSender {
    interface TargetProvider {
        List<Peer> targets();
    }

    interface Callback {
        void onSenderError(String message);
    }

    private static final int AUDIO_FRAME_BYTES = 640;

    private final UUID selfId;
    private final TargetProvider targetProvider;
    private final Callback callback;
    private final boolean voxMode;
    private final int voxThreshold;

    private volatile boolean talking;
    private Thread thread;
    private AudioRecord recorder;
    private DatagramSocket socket;

    AudioSender(UUID selfId, TargetProvider targetProvider, Callback callback, boolean voxMode, int voxThreshold) {
        this.selfId = selfId;
        this.targetProvider = targetProvider;
        this.callback = callback;
        this.voxMode = voxMode;
        this.voxThreshold = voxThreshold;
    }

    synchronized void start() {
        if (talking) {
            return;
        }
        talking = true;
        thread = new Thread(this::runLoop, "walkie-audio-sender");
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
        if (socket != null) {
            socket.close();
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
        byte[] packetBuffer = new byte[AudioPacket.HEADER_BYTES + AUDIO_FRAME_BYTES];
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
            socket = new DatagramSocket();
            recorder.startRecording();

            while (talking) {
                int read = recorder.read(audioBuffer, 0, audioBuffer.length);
                if (read <= 0) {
                    continue;
                }
                if (voxMode && !isVoiceFrame(audioBuffer, read)) {
                    continue;
                }
                AudioPacket.writeHeader(packetBuffer, sequence++, selfId);
                System.arraycopy(audioBuffer, 0, packetBuffer, AudioPacket.HEADER_BYTES, read);
                List<Peer> targets = targetProvider.targets();
                for (Peer peer : targets) {
                    sendToPeer(packetBuffer, AudioPacket.HEADER_BYTES + read, peer);
                }
            }
        } catch (SecurityException e) {
            callback.onSenderError("Izin mikrofon belum diberikan.");
        } catch (IOException e) {
            if (talking) {
                callback.onSenderError("Gagal mengirim audio: " + e.getMessage());
            }
        } finally {
            if (recorder != null) {
                recorder.release();
                recorder = null;
            }
            if (socket != null) {
                socket.close();
                socket = null;
            }
            talking = false;
        }
    }

    private void sendToPeer(byte[] packetBuffer, int length, Peer peer) throws IOException {
        InetAddress address = InetAddress.getByName(peer.host);
        DatagramPacket packet = new DatagramPacket(packetBuffer, length, address, peer.port);
        socket.send(packet);
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
