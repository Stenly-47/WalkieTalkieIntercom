package com.stanly.walkietalkie;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ActivationActivity extends Activity {
    private final LicenseApiClient licenseApiClient = new LicenseApiClient();

    private EditText licenseInput;
    private TextView statusText;
    private Button activateButton;
    private String deviceId;
    private String deviceName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        deviceId = LicenseStore.getOrCreateDeviceId(this);
        deviceName = LicenseStore.getDeviceName();
        buildUi();

        String savedLicenseKey = LicenseStore.getLicenseKey(this);
        if (savedLicenseKey.length() > 0) {
            licenseInput.setText(savedLicenseKey);
            setLoading(true, "Memeriksa lisensi...");
            checkLicense(savedLicenseKey);
        } else {
            setLoading(false, "Masukkan kode lisensi untuk aktivasi.");
        }
    }

    private void buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 248, 251));

        TextView title = new TextView(this);
        title.setText("Aktivasi Lisensi");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(23, 32, 51));
        root.addView(title);

        statusText = new TextView(this);
        statusText.setPadding(0, dp(8), 0, dp(16));
        statusText.setTextColor(Color.rgb(101, 113, 132));
        statusText.setTextSize(15);
        root.addView(statusText);

        licenseInput = new EditText(this);
        licenseInput.setHint("Kode Lisensi");
        licenseInput.setSingleLine(true);
        licenseInput.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(licenseInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        activateButton = new Button(this);
        activateButton.setText("Aktivasi");
        activateButton.setAllCaps(false);
        activateButton.setOnClickListener(view -> activate());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        buttonParams.topMargin = dp(14);
        root.addView(activateButton, buttonParams);

        TextView deviceInfo = new TextView(this);
        deviceInfo.setPadding(0, dp(14), 0, 0);
        deviceInfo.setTextColor(Color.rgb(101, 113, 132));
        deviceInfo.setTextSize(13);
        deviceInfo.setText("Perangkat: " + deviceName);
        root.addView(deviceInfo);

        setContentView(root);
    }

    private void activate() {
        String licenseKey = licenseInput.getText().toString().trim().toUpperCase();
        if (licenseKey.length() == 0) {
            setLoading(false, "Kode lisensi wajib diisi.");
            return;
        }
        setLoading(true, "Mengaktivasi lisensi...");
        licenseApiClient.activate(licenseKey, deviceId, deviceName, new LicenseApiClient.Callback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    LicenseStore.saveLicenseKey(ActivationActivity.this, licenseKey);
                    setLoading(false, "Aktivasi berhasil.");
                    openMainActivity();
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    LicenseStore.clearLicenseKey(ActivationActivity.this);
                    setLoading(false, errorMessage);
                });
            }
        });
    }

    private void checkLicense(String licenseKey) {
        licenseApiClient.check(licenseKey, deviceId, deviceName, new LicenseApiClient.Callback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    LicenseStore.saveLicenseKey(ActivationActivity.this, licenseKey);
                    openMainActivity();
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    LicenseStore.clearLicenseKey(ActivationActivity.this);
                    setLoading(false, errorMessage);
                });
            }
        });
    }

    private void openMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean loading, String message) {
        statusText.setText(message);
        statusText.setTextColor(message != null && message.toLowerCase().contains("berhasil")
                ? Color.rgb(12, 124, 89)
                : Color.rgb(101, 113, 132));
        activateButton.setEnabled(!loading);
        licenseInput.setEnabled(!loading);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
