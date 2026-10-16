package com.stanly.walkietalkie;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ConfigClient {
    // Public repository placeholder. Production credentials must be injected
    // by the private release configuration and must not be committed here.
    private static final String API_TOKEN = "";
    private static final String CONFIG_URL = "http://intercom.ssrapps.my.id/api/config";

    public interface Callback {
        void onSuccess(AppConfig config);

        void onError(String errorMessage);
    }

    public void fetchConfig(String licenseKey, String deviceId, Callback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(buildConfigUrl(licenseKey, deviceId)).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("Accept", "application/json");

                int responseCode = connection.getResponseCode();
                String responseText = readResponse(responseCode >= 200 && responseCode < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream());
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException(extractErrorMessage(responseText, "HTTP " + responseCode));
                }

                callback.onSuccess(parse(responseText));
            } catch (Exception e) {
                callback.onError(cleanErrorMessage(e));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "walkie-config-client").start();
    }

    private String buildConfigUrl(String licenseKey, String deviceId) {
        return CONFIG_URL
                + "?token=" + encode(API_TOKEN)
                + "&licenseKey=" + encode(licenseKey)
                + "&deviceId=" + encode(deviceId);
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private AppConfig parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject relayJson = root.getJSONObject("relay");
        JSONObject audioJson = root.getJSONObject("audio");
        JSONObject licenseJson = root.getJSONObject("license");
        JSONObject securityJson = root.optJSONObject("security");

        String relayHost = relayJson.getString("host");
        int relayPort = relayJson.getInt("port");
        String relayMode = relayJson.optString("mode", "");
        String relayUrl = relayJson.optString("url", "");
        String relayPath = relayJson.optString("path", "");

        return new AppConfig(
                relayHost,
                relayPort,
                relayMode,
                relayUrl,
                relayPath,
                parseRooms(root.optJSONArray("rooms")),
                new AudioConfig(
                        audioJson.getInt("sampleRate"),
                        audioJson.getInt("frameBytes"),
                        audioJson.getInt("micGain"),
                        audioJson.getInt("speakerVolume"),
                        audioJson.getInt("voxThreshold")
                ),
                new LicenseConfig(
                        licenseJson.getString("organization"),
                        licenseJson.getString("licenseKey"),
                        licenseJson.getBoolean("active"),
                        licenseJson.getString("activeUntil"),
                        licenseJson.getInt("maxUsers"),
                        licenseJson.getInt("maxDevices")
                ),
                securityJson == null ? "" : securityJson.optString("relayToken", "")
        );
    }

    private List<String> parseRooms(JSONArray roomsJson) {
        List<String> rooms = new ArrayList<>();
        if (roomsJson == null) {
            return rooms;
        }
        for (int index = 0; index < roomsJson.length(); index++) {
            String room = roomsJson.optString(index, "").trim();
            if (room.length() > 0) {
                rooms.add(room);
            }
        }
        return rooms;
    }

    private String readResponse(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        }
        return builder.toString().trim();
    }

    private String cleanErrorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().length() == 0 ? error.getClass().getSimpleName() : message.trim();
    }

    private String extractErrorMessage(String responseText, String fallback) {
        try {
            JSONObject jsonObject = new JSONObject(responseText);
            String error = jsonObject.optString("error", "").trim();
            if (error.length() > 0) {
                return error;
            }
        } catch (Exception ignored) {
        }
        return responseText == null || responseText.trim().length() == 0 ? fallback : responseText.trim();
    }

    public static final class AppConfig {
        public final String relayHost;
        public final int relayPort;
        public final String relayMode;
        public final String relayUrl;
        public final String relayPath;
        public final List<String> rooms;
        public final AudioConfig audio;
        public final LicenseConfig license;
        public final String relayToken;

        AppConfig(String relayHost, int relayPort, String relayMode, String relayUrl, String relayPath, List<String> rooms, AudioConfig audio, LicenseConfig license, String relayToken) {
            this.relayHost = relayHost;
            this.relayPort = relayPort;
            this.relayMode = relayMode == null ? "" : relayMode.trim();
            this.relayUrl = relayUrl == null ? "" : relayUrl.trim();
            this.relayPath = relayPath == null ? "" : relayPath.trim();
            this.rooms = rooms;
            this.audio = audio;
            this.license = license;
            this.relayToken = relayToken == null ? "" : relayToken.trim();
        }
    }

    public static final class AudioConfig {
        public final int sampleRate;
        public final int frameBytes;
        public final int micGain;
        public final int speakerVolume;
        public final int voxThreshold;

        AudioConfig(int sampleRate, int frameBytes, int micGain, int speakerVolume, int voxThreshold) {
            this.sampleRate = sampleRate;
            this.frameBytes = frameBytes;
            this.micGain = micGain;
            this.speakerVolume = speakerVolume;
            this.voxThreshold = voxThreshold;
        }
    }

    public static final class LicenseConfig {
        public final String organization;
        public final String licenseKey;
        public final boolean active;
        public final String activeUntil;
        public final int maxUsers;
        public final int maxDevices;

        LicenseConfig(String organization, String licenseKey, boolean active, String activeUntil, int maxUsers, int maxDevices) {
            this.organization = organization;
            this.licenseKey = licenseKey;
            this.active = active;
            this.activeUntil = activeUntil;
            this.maxUsers = maxUsers;
            this.maxDevices = maxDevices;
        }
    }
}
