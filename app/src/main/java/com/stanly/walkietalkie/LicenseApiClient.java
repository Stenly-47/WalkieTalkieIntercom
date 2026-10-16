package com.stanly.walkietalkie;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class LicenseApiClient {
    // Configure a non-production client credential through the release process.
    // Never place an administrator or server credential in a public source tree.
    private static final String API_TOKEN = "";
    private static final String ACTIVATE_URL = "http://intercom.ssrapps.my.id/api/license/activate";
    private static final String CHECK_URL = "http://intercom.ssrapps.my.id/api/license/check";

    interface Callback {
        void onSuccess();

        void onError(String errorMessage);
    }

    void activate(String licenseKey, String deviceId, String deviceName, Callback callback) {
        post(ACTIVATE_URL, licenseKey, deviceId, deviceName, true, callback);
    }

    void check(String licenseKey, String deviceId, String deviceName, Callback callback) {
        post(CHECK_URL, licenseKey, deviceId, deviceName, false, callback);
    }

    private void post(String url, String licenseKey, String deviceId, String deviceName, boolean includeDeviceName, Callback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("Accept", "application/json");

                JSONObject body = new JSONObject();
                body.put("apiToken", API_TOKEN);
                body.put("licenseKey", licenseKey == null ? "" : licenseKey.trim().toUpperCase());
                body.put("deviceId", deviceId == null ? "" : deviceId.trim());
                if (includeDeviceName) {
                    body.put("deviceName", deviceName == null ? "" : deviceName.trim());
                }

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = connection.getResponseCode();
                String responseText = readResponse(responseCode >= 200 && responseCode < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream());
                if (responseCode < 200 || responseCode >= 300) {
                    callback.onError(extractErrorMessage(responseText, "HTTP " + responseCode));
                    return;
                }
                JSONObject responseJson = new JSONObject(responseText);
                if (!responseJson.optBoolean("success", false)) {
                    callback.onError(extractErrorMessage(responseText, "Aktivasi lisensi gagal"));
                    return;
                }
                callback.onSuccess();
            } catch (Exception error) {
                callback.onError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "walkie-license-api").start();
    }

    private String readResponse(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString().trim();
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
}
