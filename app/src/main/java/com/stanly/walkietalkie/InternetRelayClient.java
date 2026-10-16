package com.stanly.walkietalkie;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class InternetRelayClient {
    interface Listener {
        void onRelayStatus(String status);

        void onRelayConnected(String room);

        void onRelayDisconnected(String reason);

        void onRelayPeers(String room, List<Peer> peers);

        void onRelayAudio(String senderId, byte[] pcm, int length);
    }

    private static final Pattern IPV4_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final String deviceId;
    private final Listener listener;
    private final Object writerLock = new Object();

    private volatile boolean running;
    private volatile boolean connected;

    private OkHttpClient httpClient;
    private WebSocket webSocket;

    private String room;
    private String relayToken = "";

    InternetRelayClient(String deviceId, Listener listener) {
        this.deviceId = deviceId;
        this.listener = listener;
    }

    synchronized void connect(String host, int port, String displayName, String roomName, String token) {
        disconnect();

        running = true;
        connected = false;
        room = cleanRoom(roomName);
        relayToken = token == null ? "" : token.trim();
        Log.d("DEVICE_ID", deviceId);
        Log.d("RELAY_CONNECT", host + ":" + port + " room=" + room);

        String relayUrl = buildRelayUrl(host, port);
        listener.onRelayStatus("Menghubungkan ke relay internet...");

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(relayUrl)
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                connected = true;

                try {
                    JSONObject hello = new JSONObject();
                    hello.put("type", "hello");
                    hello.put("id", deviceId);
                    hello.put("name", displayName);
                    hello.put("room", room);
                    hello.put("token", relayToken);
                    send(hello);
                } catch (JSONException e) {
                    listener.onRelayDisconnected("Gagal membuat data login relay.");
                    disconnect();
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    handleLine(text);
                } catch (JSONException e) {
                    listener.onRelayStatus("Data relay tidak valid.");
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                connected = false;
                if (running) {
                    running = false;
                    listener.onRelayDisconnected(reason == null || reason.trim().isEmpty()
                            ? "Koneksi relay terputus."
                            : reason);
                }
                closeSocket();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                connected = false;
                if (running) {
                    running = false;
                    String message = t == null || t.getMessage() == null
                            ? "Koneksi relay gagal."
                            : t.getMessage();
                    listener.onRelayDisconnected("Relay gagal: " + message);
                }
                closeSocket();
            }
        });
    }

    synchronized void disconnect() {
        running = false;
        connected = false;
        closeSocket();
    }

    boolean isConnected() {
        return running && connected && webSocket != null;
    }

    String room() {
        return room == null ? "umum" : room;
    }

    void sendAudio(byte[] pcm, int length, int sequence) throws IOException {
        if (!isConnected()) {
            return;
        }

        byte[] copy = new byte[length];
        System.arraycopy(pcm, 0, copy, 0, length);

        String encoded = Base64.encodeToString(copy, Base64.NO_WRAP);

        JSONObject payload = new JSONObject();
        try {
            payload.put("type", "audio");
            payload.put("seq", sequence);
            payload.put("pcm", encoded);
        } catch (JSONException e) {
            return;
        }

        send(payload);
    }

    private void handleLine(String line) throws JSONException {
        Log.d("RELAY_RAW", line);

        if (line == null || !line.trim().startsWith("{")) {
            return;
        }

        JSONObject message = new JSONObject(line);

        String type = message.optString("type", "");
        if ("welcome".equals(type)) {
            listener.onRelayConnected(room());
            return;
        }

        if ("joined".equals(type)) {
            room = message.optString("room", room());
            listener.onRelayConnected(room());
            return;
        }

        if ("peers".equals(type)) {
            listener.onRelayPeers(message.optString("room", room()), parsePeers(message.optJSONArray("peers")));
            return;
        }

        if ("audio".equals(type)) {
            String from = message.optString("from", "");
            if (deviceId.equals(from)) {
                return;
            }

            byte[] pcm = Base64.decode(message.optString("pcm", ""), Base64.DEFAULT);
            listener.onRelayAudio(from, pcm, pcm.length);
            return;
        }

        if ("error".equals(type)) {
            listener.onRelayStatus(message.optString("message", "Relay error"));
        }
    }

    private List<Peer> parsePeers(JSONArray array) {
        List<Peer> peers = new ArrayList<>();
        if (array == null) {
            return peers;
        }

        long now = System.currentTimeMillis();
        for (int index = 0; index < array.length(); index++) {
            JSONObject peerJson = array.optJSONObject(index);
            if (peerJson == null) {
                continue;
            }

            String id = peerJson.optString("id", "");
            if (id.length() == 0 || deviceId.equals(id)) {
                continue;
            }

            String name = peerJson.optString("name", "User");
            peers.add(new Peer(id, name, "internet", 0, "internet", now));
        }

        return peers;
    }

    private void send(JSONObject payload) {
        synchronized (writerLock) {
            if (webSocket != null && connected) {
                webSocket.send(payload.toString());
            }
        }
    }

    private void closeSocket() {
        WebSocket oldSocket = webSocket;
        webSocket = null;

        if (oldSocket != null) {
            try {
                oldSocket.close(1000, "Client closed");
            } catch (Exception ignored) {
            }
        }

        if (httpClient != null) {
            try {
                httpClient.dispatcher().executorService().shutdown();
                httpClient.connectionPool().evictAll();
            } catch (Exception ignored) {
            }
        }

        httpClient = null;
    }

    private String cleanRoom(String value) {
        if (value == null) {
            return "umum";
        }

        String clean = value.trim().toLowerCase(Locale.US);
        return clean.length() == 0 ? "umum" : clean;
    }

    private String buildRelayUrl(String host, int port) {
        String value = host == null ? "" : host.trim();

        if (value.isEmpty()) {
            value = "127.0.0.1";
        }

        value = value.replace("\\", "/");

        if (value.startsWith("https://")) {
            return "wss://" + value.substring("https://".length());
        }

        if (value.startsWith("http://")) {
            return "ws://" + value.substring("http://".length());
        }

        if (value.startsWith("wss://") || value.startsWith("ws://")) {
            return value;
        }

        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }

        if (isLocalOrIpHost(value)) {
            int safePort = port > 0 ? port : 47654;
            if (hasPort(value)) {
                return "ws://" + value + "/";
            }
            return "ws://" + value + ":" + safePort + "/";
        }

        // Domain publik lewat Cloudflare Tunnel memakai HTTPS/WSS port 443 dari luar.
        // Service URL di Cloudflare tetap boleh menuju http://127.0.0.1:47654.
        if (hasPort(value)) {
            return "wss://" + value + "/";
        }

        return "wss://" + value + "/";
    }

    private boolean isLocalOrIpHost(String host) {
        String lower = host.toLowerCase(Locale.US);

        if (lower.equals("localhost") || lower.startsWith("localhost:")) {
            return true;
        }

        String cleanHost = lower;
        int colonIndex = cleanHost.indexOf(":");
        if (colonIndex >= 0) {
            cleanHost = cleanHost.substring(0, colonIndex);
        }

        if (!IPV4_PATTERN.matcher(cleanHost).matches()) {
            return false;
        }

        return cleanHost.startsWith("10.")
                || cleanHost.startsWith("127.")
                || cleanHost.startsWith("192.168.")
                || cleanHost.startsWith("172.16.")
                || cleanHost.startsWith("172.17.")
                || cleanHost.startsWith("172.18.")
                || cleanHost.startsWith("172.19.")
                || cleanHost.startsWith("172.20.")
                || cleanHost.startsWith("172.21.")
                || cleanHost.startsWith("172.22.")
                || cleanHost.startsWith("172.23.")
                || cleanHost.startsWith("172.24.")
                || cleanHost.startsWith("172.25.")
                || cleanHost.startsWith("172.26.")
                || cleanHost.startsWith("172.27.")
                || cleanHost.startsWith("172.28.")
                || cleanHost.startsWith("172.29.")
                || cleanHost.startsWith("172.30.")
                || cleanHost.startsWith("172.31.");
    }

    private boolean hasPort(String host) {
        int colonIndex = host.lastIndexOf(":");
        int slashIndex = host.indexOf("/");
        if (colonIndex < 0) {
            return false;
        }
        return slashIndex < 0 || colonIndex < slashIndex;
    }
}
