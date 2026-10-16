package com.stanly.walkietalkie;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

final class PresenceSender {
    interface TargetProvider {
        List<Peer> targets();
    }

    private static final long HEARTBEAT_INTERVAL_MS = 5000L;

    private final UUID selfId;
    private final TargetProvider targetProvider;
    private final byte[] heartbeat = new byte[AudioPacket.HEADER_BYTES];

    private volatile boolean running;
    private Thread thread;
    private DatagramSocket socket;

    PresenceSender(UUID selfId, TargetProvider targetProvider) {
        this.selfId = selfId;
        this.targetProvider = targetProvider;
        AudioPacket.writeHeartbeat(heartbeat, selfId);
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::runLoop, "walkie-presence-sender");
        thread.start();
    }

    synchronized void stop() {
        running = false;
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
        try {
            socket = new DatagramSocket();
            while (running) {
                sendHeartbeat();
                sleepInterval();
            }
        } catch (IOException ignored) {
        } finally {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        }
    }

    private void sendHeartbeat() throws IOException {
        for (Peer peer : targetProvider.targets()) {
            try {
                InetAddress address = InetAddress.getByName(peer.host);
                DatagramPacket packet = new DatagramPacket(heartbeat, heartbeat.length, address, peer.port);
                socket.send(packet);
            } catch (IOException ignored) {
            }
        }
    }

    private void sleepInterval() {
        long remaining = HEARTBEAT_INTERVAL_MS;
        while (running && remaining > 0) {
            long slice = Math.min(remaining, 500L);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining -= slice;
        }
    }
}
