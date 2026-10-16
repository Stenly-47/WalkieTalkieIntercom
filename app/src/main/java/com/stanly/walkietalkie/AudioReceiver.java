package com.stanly.walkietalkie;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.UUID;

final class AudioReceiver {
    interface Callback {
        void onPacket(UUID senderId);

        void onReceiverError(String message);
    }

    private final int port;
    private final UUID selfId;
    private final Callback callback;

    private volatile boolean running;
    private Thread thread;
    private DatagramSocket socket;
    private AudioTrack audioTrack;

    AudioReceiver(int port, UUID selfId, Callback callback) {
        this.port = port;
        this.selfId = selfId;
        this.callback = callback;
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::runLoop, "walkie-audio-receiver");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
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
        byte[] buffer = new byte[1400];
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            socket.setSoTimeout(1000);

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

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    int length = packet.getLength();
                    if (!AudioPacket.isValid(buffer, length)) {
                        continue;
                    }
                    UUID senderId = AudioPacket.readSenderId(buffer);
                    if (selfId.equals(senderId)) {
                        continue;
                    }
                    if (AudioPacket.isHeartbeat(buffer)) {
                        callback.onPacket(senderId);
                        continue;
                    }
                    int audioBytes = length - AudioPacket.HEADER_BYTES;
                    if (audioBytes > 0) {
                        audioTrack.write(buffer, AudioPacket.HEADER_BYTES, audioBytes);
                        callback.onPacket(senderId);
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (SocketException e) {
            if (running) {
                callback.onReceiverError("Port audio tidak bisa dibuka: " + e.getMessage());
            }
        } catch (IOException e) {
            if (running) {
                callback.onReceiverError("Gagal menerima audio: " + e.getMessage());
            }
        } finally {
            if (audioTrack != null) {
                audioTrack.release();
                audioTrack = null;
            }
            if (socket != null) {
                socket.close();
                socket = null;
            }
        }
    }
}
