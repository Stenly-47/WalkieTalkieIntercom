package com.stanly.walkietalkie;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 1001;

    private WalkieTalkieService service;
    private boolean bound;
    private boolean receiverRegistered;
    private ConfigClient.AppConfig adminConfig;
    private String configStatusMessage;
    private String currentLicenseKey;
    private String currentDeviceId;
    private boolean internetLicenseAllowed = true;
    private boolean redirectingToActivation;

    private TextView statusText;
    private TextView countText;
    private EditText nameInput;
    private EditText serverInput;
    private EditText portInput;
    private EditText roomInput;
    private LinearLayout peersContainer;
    private Button pttButton;
    private Button serviceButton;
    private Button internetButton;
    private Button voxButton;

    private String selectedPeerId = WalkieTalkieService.TARGET_ALL;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            WalkieTalkieService.LocalBinder localBinder = (WalkieTalkieService.LocalBinder) binder;
            service = localBinder.service();
            bound = true;
            selectedPeerId = service.getSelectedPeerId();
            nameInput.setText(service.getDisplayName());
            if (adminConfig != null) {
                service.updateRelayDefaults(resolveRelayConnectTarget(adminConfig), adminConfig.relayPort, adminConfig.relayToken);
            }
            applyRelayInputs();
            refreshUi();
            showConfigStatusMessage();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            refreshUi();
        }
    };

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshUi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentDeviceId = LicenseStore.getOrCreateDeviceId(this);
        currentLicenseKey = LicenseStore.getLicenseKey(this);
        if (currentLicenseKey.length() == 0) {
            openActivationActivity();
            return;
        }
        buildUi();
        loadAdminConfig();
        ensurePermissionsThenStart();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (redirectingToActivation) {
            return;
        }
        registerUpdates();
        if (hasRequiredPermissions()) {
            startAndBindService();
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(updateReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS && hasRequiredPermissions()) {
            startAndBindService();
        } else {
            statusText.setText("Izin mikrofon/Wi-Fi diperlukan agar interkom berjalan.");
        }
    }

    private void buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(246, 248, 251));

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextColor(Color.rgb(23, 32, 51));
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        statusText = new TextView(this);
        statusText.setText("Menyiapkan interkom...");
        statusText.setTextColor(Color.rgb(101, 113, 132));
        statusText.setTextSize(15);
        statusText.setPadding(0, dp(6), 0, dp(14));
        root.addView(statusText);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("Nama user");
        nameInput.setTextColor(Color.rgb(23, 32, 51));
        nameInput.setTextSize(16);
        nameRow.addView(nameInput, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button saveName = new Button(this);
        saveName.setText("Simpan");
        saveName.setOnClickListener(view -> {
            if (service != null) {
                service.setDisplayName(nameInput.getText().toString());
            }
        });
        nameRow.addView(saveName, new LinearLayout.LayoutParams(dp(104), dp(48)));
        root.addView(nameRow);

        TextView internetLabel = new TextView(this);
        internetLabel.setText("Internet relay dan room");
        internetLabel.setTextColor(Color.rgb(23, 32, 51));
        internetLabel.setTypeface(Typeface.DEFAULT_BOLD);
        internetLabel.setTextSize(16);
        internetLabel.setPadding(0, dp(14), 0, dp(6));
        root.addView(internetLabel);

        serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setHint("Alamat server relay / domain");
        serverInput.setTextColor(Color.rgb(23, 32, 51));
        serverInput.setTextSize(15);
        root.addView(serverInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        ));

        LinearLayout relayRow = new LinearLayout(this);
        relayRow.setOrientation(LinearLayout.HORIZONTAL);
        portInput = new EditText(this);
        portInput.setSingleLine(true);
        portInput.setHint("Port");
        portInput.setText("47654");
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        relayRow.addView(portInput, new LinearLayout.LayoutParams(dp(92), dp(46)));

        roomInput = new EditText(this);
        roomInput.setSingleLine(true);
        roomInput.setHint("Room / channel");
        relayRow.addView(roomInput, new LinearLayout.LayoutParams(0, dp(46), 1));
        root.addView(relayRow);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, dp(8), 0, dp(4));

        internetButton = new Button(this);
        internetButton.setAllCaps(false);
        internetButton.setText("Sambung Internet");
        internetButton.setOnClickListener(view -> toggleInternetMode());
        modeRow.addView(internetButton, new LinearLayout.LayoutParams(0, dp(46), 1));

        voxButton = new Button(this);
        voxButton.setAllCaps(false);
        voxButton.setText("VOX: OFF");
        voxButton.setOnClickListener(view -> {
            if (service != null) {
                service.setVoxEnabled(!service.isVoxEnabled());
                refreshUi();
            }
        });
        modeRow.addView(voxButton, new LinearLayout.LayoutParams(dp(116), dp(46)));
        root.addView(modeRow);

        LinearLayout topActions = new LinearLayout(this);
        topActions.setOrientation(LinearLayout.HORIZONTAL);
        topActions.setPadding(0, dp(12), 0, dp(8));

        countText = new TextView(this);
        countText.setText("0 user online");
        countText.setGravity(Gravity.CENTER_VERTICAL);
        countText.setTextColor(Color.rgb(23, 32, 51));
        countText.setTypeface(Typeface.DEFAULT_BOLD);
        countText.setTextSize(16);
        topActions.addView(countText, new LinearLayout.LayoutParams(0, dp(44), 1));

        serviceButton = new Button(this);
        serviceButton.setText("Stop");
        serviceButton.setOnClickListener(view -> stopIntercom());
        topActions.addView(serviceButton, new LinearLayout.LayoutParams(dp(96), dp(44)));
        root.addView(topActions);

        peersContainer = new LinearLayout(this);
        peersContainer.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(peersContainer);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        pttButton = new Button(this);
        pttButton.setAllCaps(false);
        pttButton.setText("Tahan untuk bicara");
        pttButton.setTextSize(20);
        pttButton.setTypeface(Typeface.DEFAULT_BOLD);
        pttButton.setTextColor(Color.WHITE);
        pttButton.setBackgroundColor(Color.rgb(12, 124, 89));
        pttButton.setOnTouchListener((view, event) -> {
            if (service == null) {
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.performClick();
                pttButton.setText("Sedang bicara...");
                pttButton.setBackgroundColor(Color.rgb(7, 91, 66));
                service.beginTalk();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                pttButton.setText("Tahan untuk bicara");
                pttButton.setBackgroundColor(Color.rgb(12, 124, 89));
                service.endTalk();
                return true;
            }
            return true;
        });
        root.addView(pttButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(72)
        ));

        setContentView(root);
    }

    private void loadAdminConfig() {
        configStatusMessage = "Mengambil config Web Admin...";
        statusText.setText(configStatusMessage);
        new ConfigClient().fetchConfig(currentLicenseKey, currentDeviceId, new ConfigClient.Callback() {
            @Override
            public void onSuccess(ConfigClient.AppConfig config) {
                runOnUiThread(() -> {
                    adminConfig = config;
                    currentLicenseKey = config.license.licenseKey;
                    LicenseStore.saveLicenseKey(MainActivity.this, currentLicenseKey);
                    internetLicenseAllowed = true;
                    if (service != null) {
                        service.updateRelayDefaults(
                                resolveRelayConnectTarget(config),
                                config.relayPort,
                                config.relayToken
                        );
                    }
                    applyRelayInputs();
                    configStatusMessage = "Config Web Admin berhasil dimuat";
                    statusText.setText(configStatusMessage);
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    if (isLicenseError(errorMessage)) {
                        internetLicenseAllowed = false;
                        configStatusMessage = "Lisensi tidak valid atau sudah digunakan perangkat lain";
                        statusText.setText(configStatusMessage);
                        refreshUi();
                        return;
                    }
                    configStatusMessage = "Gagal ambil config: " + errorMessage;
                    statusText.setText(configStatusMessage);
                    refreshUi();
                });
            }
        });
    }

    private void applyRelayInputs() {
        if (adminConfig != null) {
            serverInput.setText(resolveRelayDisplayTarget(adminConfig));
            portInput.setText(String.valueOf(adminConfig.relayPort));
            return;
        }
        if (service != null) {
            serverInput.setText(service.getInternetHost());
            portInput.setText(String.valueOf(service.getInternetPort()));
        }
    }

    private void showConfigStatusMessage() {
        if (configStatusMessage != null && configStatusMessage.length() > 0) {
            statusText.setText(configStatusMessage);
        }
    }

    private void registerUpdates() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(WalkieTalkieService.ACTION_PEERS_CHANGED);
        filter.addAction(WalkieTalkieService.ACTION_STATUS_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void ensurePermissionsThenStart() {
        if (hasRequiredPermissions()) {
            startAndBindService();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(missingPermissions(), REQ_PERMISSIONS);
        }
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        for (String permission : missingPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] missingPermissions() {
        List<String> permissions = new ArrayList<>();
        addIfMissing(permissions, Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(permissions, Manifest.permission.POST_NOTIFICATIONS);
            addIfMissing(permissions, Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        return permissions.toArray(new String[0]);
    }

    private void addIfMissing(List<String> permissions, String permission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(permission);
        }
    }

    private void startAndBindService() {
        Intent intent = new Intent(this, WalkieTalkieService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        if (!bound) {
            bindService(intent, connection, Context.BIND_AUTO_CREATE);
        }
        serviceButton.setText("Stop");
        serviceButton.setOnClickListener(view -> stopIntercom());
    }

    private void stopIntercom() {
        if (service != null) {
            service.endTalk();
        }
        if (bound) {
            unbindService(connection);
            bound = false;
            service = null;
        }
        stopService(new Intent(this, WalkieTalkieService.class));
        statusText.setText("Interkom dimatikan.");
        peersContainer.removeAllViews();
        countText.setText("0 user online");
        serviceButton.setText("Start");
        serviceButton.setOnClickListener(view -> startAndBindService());
    }

    private void refreshUi() {
        if (service == null) {
            pttButton.setEnabled(false);
            internetButton.setEnabled(false);
            voxButton.setEnabled(false);
            return;
        }
        internetButton.setEnabled(internetLicenseAllowed);
        voxButton.setEnabled(true);
        if (!internetLicenseAllowed && configStatusMessage != null && configStatusMessage.length() > 0) {
            statusText.setText(configStatusMessage);
        } else {
            statusText.setText(service.getStatusText());
        }
        List<Peer> peers = service.getPeers();
        countText.setText(service.isInternetMode()
                ? peers.size() + " user di room " + service.getInternetRoom()
                : peers.size() + " user online");
        selectedPeerId = service.getSelectedPeerId();
        internetButton.setText(service.isInternetMode() ? "Putus Internet" : "Sambung Internet");
        voxButton.setText(service.isVoxEnabled() ? "VOX: ON" : "VOX: OFF");
        pttButton.setEnabled(!service.isVoxEnabled());
        pttButton.setText(service.isVoxEnabled() ? "VOX aktif" : "Tahan untuk bicara");
        renderPeers(peers);
    }

    private void toggleInternetMode() {
        if (service == null) {
            return;
        }
        if (!internetLicenseAllowed || currentLicenseKey.length() == 0) {
            statusText.setText("Lisensi tidak valid atau sudah digunakan perangkat lain");
            return;
        }
        if (service.isInternetMode()) {
            service.disconnectInternet();
            refreshUi();
            return;
        }
        service.connectInternet(
                resolveRelayConnectTarget(adminConfig != null ? adminConfig : null),
                parsePort(),
                currentRoomText(),
                resolveRelayToken()
        );
        refreshUi();
    }

    private String currentRoomText() {
        return roomInput.getText().toString();
    }

    private String resolveRelayToken() {
        if (adminConfig != null && adminConfig.relayToken.length() > 0) {
            return adminConfig.relayToken;
        }
        return service != null ? service.getInternetRelayToken() : "";
    }

    private String resolveRelayConnectTarget(ConfigClient.AppConfig config) {
        if (config != null && "websocket".equalsIgnoreCase(config.relayMode) && config.relayUrl.length() > 0) {
            return config.relayUrl;
        }
        return serverInput == null ? "" : serverInput.getText().toString().trim();
    }

    private String resolveRelayDisplayTarget(ConfigClient.AppConfig config) {
        if (config != null && "websocket".equalsIgnoreCase(config.relayMode) && config.relayUrl.length() > 0) {
            return config.relayUrl;
        }
        return config == null ? "" : config.relayHost;
    }

    private boolean isLicenseError(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        String lower = errorMessage.toLowerCase();
        return lower.contains("lisensi")
                || lower.contains("license")
                || lower.contains("device")
                || lower.contains("kode lisensi");
    }

    private void openActivationActivity() {
        redirectingToActivation = true;
        startActivity(new Intent(this, ActivationActivity.class));
        finish();
    }

    private int parsePort() {
        try {
            return Integer.parseInt(portInput.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            return 47654;
        }
    }

    private void renderPeers(List<Peer> peers) {
        peersContainer.removeAllViews();
        peersContainer.addView(peerRow("Semua user", "Kirim suara ke semua user online", WalkieTalkieService.TARGET_ALL));
        for (Peer peer : peers) {
            peersContainer.addView(peerRow(peer.name, peer.endpointLabel(), peer.id));
        }
    }

    private View peerRow(String title, String subtitle, String peerId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(cardBackground(peerId.equals(selectedPeerId)));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.rgb(23, 32, 51));
        titleView.setTextSize(17);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(titleView);

        TextView subView = new TextView(this);
        subView.setText(subtitle);
        subView.setTextColor(Color.rgb(101, 113, 132));
        subView.setTextSize(13);
        row.addView(subView);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);
        row.setOnClickListener(view -> {
            selectedPeerId = peerId;
            if (service != null) {
                service.setSelectedPeerId(peerId);
            }
            renderPeers(service == null ? new ArrayList<>() : service.getPeers());
        });
        return row;
    }

    private GradientDrawable cardBackground(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(8));
        drawable.setColor(Color.WHITE);
        drawable.setStroke(dp(selected ? 2 : 1), selected ? Color.rgb(12, 124, 89) : Color.rgb(215, 222, 232));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
