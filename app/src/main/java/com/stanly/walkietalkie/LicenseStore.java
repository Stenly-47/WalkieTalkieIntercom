package com.stanly.walkietalkie;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.util.UUID;

final class LicenseStore {
    static final String PREFS = "walkie_prefs";
    static final String PREF_DEVICE_ID = "device_id";
    static final String PREF_PEER_DEVICE_ID = "peer_device_id";
    static final String PREF_PEER_DEVICE_FINGERPRINT = "peer_device_fingerprint";
    static final String PREF_LICENSE_KEY = "license_key";

    private LicenseStore() {
    }

    static String getOrCreateDeviceId(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = preferences.getString(PREF_DEVICE_ID, "");
        if (value != null && value.trim().length() > 0) {
            return value.trim();
        }
        String deviceId = UUID.randomUUID().toString();
        preferences.edit().putString(PREF_DEVICE_ID, deviceId).apply();
        return deviceId;
    }

    static String getOrCreatePeerDeviceId(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String storedId = safe(preferences.getString(PREF_PEER_DEVICE_ID, ""));
        String storedFingerprint = safe(preferences.getString(PREF_PEER_DEVICE_FINGERPRINT, ""));
        String currentFingerprint = currentDeviceFingerprint(context);

        if (isUuid(storedId) && storedFingerprint.equals(currentFingerprint)) {
            return storedId;
        }

        String peerId = UUID.randomUUID().toString();
        preferences.edit()
                .putString(PREF_PEER_DEVICE_ID, peerId)
                .putString(PREF_PEER_DEVICE_FINGERPRINT, currentFingerprint)
                .apply();
        return peerId;
    }

    static String getLicenseKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_LICENSE_KEY, "")
                .trim()
                .toUpperCase();
    }

    static void saveLicenseKey(Context context, String licenseKey) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LICENSE_KEY, licenseKey == null ? "" : licenseKey.trim().toUpperCase())
                .apply();
    }

    static void clearLicenseKey(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_LICENSE_KEY)
                .apply();
    }

    static String getDeviceName() {
        String manufacturer = String.valueOf(Build.MANUFACTURER).trim();
        String model = String.valueOf(Build.MODEL).trim();
        String name = (manufacturer + " " + model).trim();
        return name.length() == 0 ? "Android Device" : name;
    }

    private static boolean isUuid(String value) {
        if (value.length() == 0) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String currentDeviceFingerprint(Context context) {
        String androidId = safe(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID));
        if (androidId.length() > 0) {
            return androidId;
        }
        return safe(Build.BRAND) + "|" + safe(Build.DEVICE) + "|" + safe(Build.MODEL);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
