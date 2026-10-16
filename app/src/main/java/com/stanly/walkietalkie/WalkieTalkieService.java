package com.stanly.walkietalkie;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WalkieTalkieService extends Service {
    public static final String ACTION_PEERS_CHANGED = "com.stanly.walkietalkie.PEERS_CHANGED";
    public static final String ACTION_STATUS_CHANGED = "com.stanly.walkietalkie.STATUS_CHANGED";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_WIFI_READY = "wifiReady";
    public static final String TARGET_ALL = "ALL";
    public static final String MODE_LAN = "LAN";
    public static final String MODE_INTERNET = "INTERNET";

    private static final String PREFS = "walkie_prefs";
    private static final String PREF_NAME = "display_name";
    private static final String PREF_RELAY_HOST = "relay_host";
    private static final String PREF_RELAY_PORT = "relay_port";
    private static final String PREF_ROOM = "relay_room";
    private static final String PREF_RELAY_TOKEN = "relay_token";
    private static final String SERVICE_TYPE = "_walkieintercom._udp.";
    private static final String CHANNEL_ID = "walkie_active";
    private static final int NOTIFICATION_ID = 42;
    private static final int AUDIO_PORT = 45678;
    private static final int DEFAULT_RELAY_PORT = 47654;
    private static final int VOX_THRESHOLD = 1400;
    private static final long PEER_STALE_MS = 45000L;

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Queue<NsdServiceInfo> resolveQueue = new ArrayDeque<>();

    private UUID deviceUuid;
    private String displayName;
    private String selectedPeerId = TARGET_ALL;
    private String statusText = "Menunggu Wi-Fi aktif...";
    private String mode = MODE_LAN;
    private String internetHost = "";
    private String internetRoom = "umum";
    private String internetRelayToken = "";
    private int internetPort = DEFAULT_RELAY_PORT;
    private boolean voxEnabled;
    private boolean manualTalking;

    private NsdManager nsdManager;
    private ConnectivityManager connectivityManager;
    private WifiManager.MulticastLock multicastLock;
    private ConnectivityManager.NetworkCallback networkCallback;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean wifiReady;
    private boolean nsdRunning;
    private boolean resolving;

    private AudioReceiver receiver;
    private AudioSender sender;
    private PresenceSender presenceSender;
    private InternetRelayClient relayClient;
    private InternetAudioSender internetSender;
    private PcmPlayer internetPlayer;

    private final Runnable pruneRunnable = new Runnable() {
        @Override
        public void run() {
            pruneStalePeers();
            mainHandler.postDelayed(this, 10000L);
        }
    };

    public final class LocalBinder extends Binder {
        public WalkieTalkieService service() {
            return WalkieTalkieService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        loadIdentity();
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        startForegroundNotification();
        startAudioReceiver();
        startPresenceSender();
        internetPlayer = new PcmPlayer();
        internetPlayer.start();
        registerWifiCallback();
        setWifiReady(isWifiConnected());
        mainHandler.postDelayed(pruneRunnable, 10000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        updateNotification();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        endTalk();
        stopVoiceSenders();
        if (relayClient != null) {
            relayClient.disconnect();
            relayClient = null;
        }
        if (receiver != null) {
            receiver.stop();
            receiver = null;
        }
        if (presenceSender != null) {
            presenceSender.stop();
            presenceSender = null;
        }
        if (internetPlayer != null) {
            internetPlayer.stop();
            internetPlayer = null;
        }
        stopNsd();
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
            }
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    public List<Peer> getPeers() {
        List<Peer> snapshot = new ArrayList<>(peers.values());
        Collections.sort(snapshot, Comparator.comparing(peer -> peer.name.toLowerCase()));
        return snapshot;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getStatusText() {
        return statusText;
    }

    public boolean isWifiReady() {
        return wifiReady;
    }

    public String getMode() {
        return mode;
    }

    public boolean isInternetMode() {
        return MODE_INTERNET.equals(mode);
    }

    public boolean isInternetConnected() {
        return relayClient != null && relayClient.isConnected();
    }

    public String getInternetHost() {
        return internetHost;
    }

    public int getInternetPort() {
        return internetPort;
    }

    public String getInternetRoom() {
        return internetRoom;
    }

    public String getInternetRelayToken() {
        return internetRelayToken;
    }

    public boolean isVoxEnabled() {
        return voxEnabled;
    }

    public String getSelectedPeerId() {
        return selectedPeerId;
    }

    public void setSelectedPeerId(String selectedPeerId) {
        this.selectedPeerId = selectedPeerId == null ? TARGET_ALL : selectedPeerId;
        broadcastStatus();
    }

    public void setDisplayName(String name) {
        String cleanName = sanitizeName(name);
        if (cleanName.equals(displayName)) {
            return;
        }
        displayName = cleanName;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_NAME, displayName).apply();
        if (MODE_INTERNET.equals(mode) && relayClient != null && relayClient.isConnected()) {
            connectInternet(internetHost, internetPort, internetRoom, internetRelayToken);
        } else if (wifiReady) {
            stopNsd();
            startNsd();
        }
        updateNotification();
        broadcastStatus();
    }

    public void beginTalk() {
        if (voxEnabled) {
            setStatus("VOX aktif. Bicara saja untuk mengirim suara.");
            return;
        }
        manualTalking = true;
        startVoiceSender(false);
    }

    public void endTalk() {
        manualTalking = false;
        if (!voxEnabled) {
            stopVoiceSenders();
            setStatus(readyStatus());
        }
    }

    public void setVoxEnabled(boolean enabled) {
        if (voxEnabled == enabled) {
            return;
        }
        voxEnabled = enabled;
        manualTalking = false;
        stopVoiceSenders();
        if (voxEnabled) {
            startVoiceSender(true);
        } else {
            setStatus(readyStatus());
        }
        broadcastStatus();
    }

    public void connectInternet(String host, int port, String room) {
        connectInternet(host, port, room, internetRelayToken);
    }

    public void updateRelayDefaults(String host, int port, String relayToken) {
        String cleanHost = cleanRelayHost(host);
        int cleanPort = port > 0 ? port : DEFAULT_RELAY_PORT;
        internetHost = cleanHost;
        internetPort = cleanPort;
        internetRelayToken = relayToken == null ? "" : relayToken.trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_RELAY_HOST, internetHost)
                .putInt(PREF_RELAY_PORT, internetPort)
                .putString(PREF_RELAY_TOKEN, internetRelayToken)
                .apply();
    }

    public void connectInternet(String host, int port, String room, String relayToken) {
        String cleanHost = cleanRelayHost(host);
        if (cleanHost.length() == 0) {
            setStatus("Isi alamat server relay internet.");
            return;
        }
        int cleanPort = port > 0 ? port : DEFAULT_RELAY_PORT;
        String cleanRoom = cleanRoom(room);
        String cleanToken = relayToken == null ? "" : relayToken.trim();

        stopVoiceSenders();
        if (relayClient != null) {
            relayClient.disconnect();
        }
        stopNsd();
        peers.clear();
        selectedPeerId = TARGET_ALL;
        mode = MODE_INTERNET;
        internetHost = cleanHost;
        internetPort = cleanPort;
        internetRoom = cleanRoom;
        internetRelayToken = cleanToken;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_RELAY_HOST, internetHost)
                .putInt(PREF_RELAY_PORT, internetPort)
                .putString(PREF_ROOM, internetRoom)
                .putString(PREF_RELAY_TOKEN, internetRelayToken)
                .apply();
        broadcastPeers();
        relayClient = new InternetRelayClient(deviceUuid.toString(), relayListener());
        relayClient.connect(internetHost, internetPort, displayName, internetRoom, internetRelayToken);
        setStatus("Menghubungkan ke room " + internetRoom + "...");
    }

    public void disconnectInternet() {
        stopVoiceSenders();
        if (relayClient != null) {
            relayClient.disconnect();
            relayClient = null;
        }
        if (!MODE_INTERNET.equals(mode)) {
            return;
        }
        mode = MODE_LAN;
        peers.clear();
        selectedPeerId = TARGET_ALL;
        if (wifiReady) {
            startNsd();
        }
        setStatus(wifiReady ? "Mode LAN lokal aktif." : "Mode LAN lokal. Menunggu Wi-Fi aktif...");
        if (voxEnabled) {
            startVoiceSender(true);
        }
        broadcastPeers();
    }

    private void loadIdentity() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String id = LicenseStore.getOrCreatePeerDeviceId(this);
        try {
            deviceUuid = UUID.fromString(id);
        } catch (IllegalArgumentException ignored) {
            deviceUuid = UUID.randomUUID();
            prefs.edit()
                    .putString(LicenseStore.PREF_PEER_DEVICE_ID, deviceUuid.toString())
                    .putString(LicenseStore.PREF_PEER_DEVICE_FINGERPRINT, "")
                    .apply();
        }
        displayName = sanitizeName(prefs.getString(PREF_NAME, defaultDeviceName()));
        internetHost = prefs.getString(PREF_RELAY_HOST, "");
        internetPort = prefs.getInt(PREF_RELAY_PORT, DEFAULT_RELAY_PORT);
        internetRoom = cleanRoom(prefs.getString(PREF_ROOM, "umum"));
        internetRelayToken = prefs.getString(PREF_RELAY_TOKEN, "");
    }

    private String defaultDeviceName() {
        String model = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        return model.length() == 0 ? "User Android" : model;
    }

    private String sanitizeName(String input) {
        if (input == null) {
            return "User Android";
        }
        String clean = input.trim();
        if (clean.length() == 0) {
            clean = "User Android";
        }
        return clean.length() > 32 ? clean.substring(0, 32) : clean;
    }

    private String cleanRoom(String input) {
        if (input == null) {
            return "umum";
        }
        String clean = input.trim().toLowerCase();
        if (clean.length() == 0) {
            clean = "umum";
        }
        return clean.length() > 40 ? clean.substring(0, 40) : clean;
    }

    private String cleanRelayHost(String input) {
        if (input == null) {
            return "";
        }
        String clean = input.trim();
        if (clean.startsWith("wss://") || clean.startsWith("ws://")) {
            return clean;
        }
        clean = clean
                .replace("https://", "")
                .replace("http://", "")
                .replace("tcp://", "");
        int slash = clean.indexOf('/');
        if (slash >= 0) {
            clean = clean.substring(0, slash);
        }
        int colon = clean.lastIndexOf(':');
        if (colon > 0 && clean.indexOf(']') < 0) {
            clean = clean.substring(0, colon);
        }
        return clean.trim();
    }

    private InternetRelayClient.Listener relayListener() {
        return new InternetRelayClient.Listener() {
            @Override
            public void onRelayStatus(String status) {
                mainHandler.post(() -> setStatus(status));
            }

            @Override
            public void onRelayConnected(String room) {
                mainHandler.post(() -> {
                    internetRoom = room;
                    mode = MODE_INTERNET;
                    setStatus("Internet aktif di room " + internetRoom + ".");
                    if (voxEnabled) {
                        startVoiceSender(true);
                    }
                    broadcastStatus();
                });
            }

            @Override
            public void onRelayDisconnected(String reason) {
                mainHandler.post(() -> {
                    stopVoiceSenders();
                    peers.clear();
                    setStatus(reason);
                    broadcastPeers();
                });
            }

            @Override
            public void onRelayPeers(String room, List<Peer> relayPeers) {
                mainHandler.post(() -> {
                    internetRoom = room;
                    peers.clear();
                    for (Peer peer : relayPeers) {
                        peers.put(peer.id, peer);
                    }
                    if (!TARGET_ALL.equals(selectedPeerId) && !peers.containsKey(selectedPeerId)) {
                        selectedPeerId = TARGET_ALL;
                    }
                    broadcastPeers();
                    updateNotification();
                });
            }

            @Override
            public void onRelayAudio(String senderId, byte[] pcm, int length) {
                if (internetPlayer != null) {
                    internetPlayer.play(pcm, length);
                }
                touchPeer(senderId);
            }
        };
    }

    private void startVoiceSender(boolean vox) {
        if (!hasMicrophonePermission()) {
            setStatus("Izin mikrofon belum diberikan.");
            return;
        }
        if (MODE_INTERNET.equals(mode)) {
            if (relayClient == null || !relayClient.isConnected()) {
                setStatus("Relay internet belum tersambung.");
                return;
            }
            if (internetSender == null) {
                internetSender = new InternetAudioSender(relayClient, this::setStatus, vox, VOX_THRESHOLD);
                internetSender.start();
            }
            setStatus(vox ? "VOX internet aktif." : "Mengirim suara via internet...");
            return;
        }
        if (!wifiReady) {
            setStatus("VOX menunggu Wi-Fi aktif.");
            return;
        }
        if (sender == null) {
            sender = new AudioSender(deviceUuid, this::getTargetsForCurrentSelection, this::setStatus, vox, VOX_THRESHOLD);
            sender.start();
        }
        setStatus(vox ? "VOX LAN aktif." : "Mengirim suara via LAN...");
    }

    private void stopVoiceSenders() {
        if (sender != null) {
            sender.stop();
            sender = null;
        }
        if (internetSender != null) {
            internetSender.stop();
            internetSender = null;
        }
    }

    private boolean hasMicrophonePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private String readyStatus() {
        if (MODE_INTERNET.equals(mode)) {
            return relayClient != null && relayClient.isConnected()
                    ? "Internet aktif di room " + internetRoom + "."
                    : "Mode internet belum tersambung.";
        }
        return wifiReady ? "Siap menerima dan mengirim." : "Menunggu Wi-Fi aktif...";
    }

    private void startForegroundNotification() {
        createNotificationChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, flags);

        String content;
        if (MODE_INTERNET.equals(mode)) {
            content = isInternetConnected()
                    ? peers.size() + " user di room " + internetRoom
                    : "Mode internet belum tersambung";
        } else {
            content = wifiReady
                    ? peers.size() + " user online di jaringan ini"
                    : "Aktifkan Wi-Fi untuk mencari user";
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(content)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        manager.createNotificationChannel(channel);
    }

    private void startAudioReceiver() {
        receiver = new AudioReceiver(AUDIO_PORT, deviceUuid, new AudioReceiver.Callback() {
            @Override
            public void onPacket(UUID senderId) {
                touchPeer(senderId.toString());
            }

            @Override
            public void onReceiverError(String message) {
                setStatus(message);
            }
        });
        receiver.start();
    }

    private void startPresenceSender() {
        presenceSender = new PresenceSender(deviceUuid, () ->
                MODE_LAN.equals(mode) ? getPeers() : Collections.emptyList()
        );
        presenceSender.start();
    }

    private void registerWifiCallback() {
        if (connectivityManager == null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                mainHandler.post(() -> setWifiReady(isWifiConnected()));
            }

            @Override
            public void onLost(Network network) {
                mainHandler.post(() -> setWifiReady(isWifiConnected()));
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                mainHandler.post(() -> setWifiReady(isWifiConnected()));
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private boolean isWifiConnected() {
        if (connectivityManager == null) {
            return false;
        }
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private void setWifiReady(boolean ready) {
        if (wifiReady == ready) {
            return;
        }
        wifiReady = ready;
        if (MODE_INTERNET.equals(mode)) {
            broadcastStatus();
            return;
        }
        if (wifiReady) {
            startNsd();
            setStatus("Siap menerima dan mengirim.");
            if (voxEnabled) {
                startVoiceSender(true);
            }
        } else {
            stopNsd();
            peers.clear();
            setStatus("Menunggu Wi-Fi aktif...");
            broadcastPeers();
        }
    }

    private void startNsd() {
        if (MODE_INTERNET.equals(mode) || nsdRunning || nsdManager == null) {
            return;
        }
        acquireMulticastLock();
        registerService();
        discoverServices();
        nsdRunning = true;
        broadcastStatus();
    }

    private void stopNsd() {
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (IllegalArgumentException ignored) {
            }
        }
        discoveryListener = null;

        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (IllegalArgumentException ignored) {
            }
        }
        registrationListener = null;
        nsdRunning = false;
        resolving = false;
        resolveQueue.clear();
        releaseMulticastLock();
        updateNotification();
    }

    private void acquireMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) {
            return;
        }
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("walkie-nsd");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        multicastLock = null;
    }

    private void registerService() {
        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                setStatus("Terhubung ke jaringan. Mencari user online...");
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                setStatus("Gagal publish user: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }
        };

        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceType(SERVICE_TYPE);
        info.setServiceName(serviceName());
        info.setPort(AUDIO_PORT);
        info.setAttribute("id", deviceUuid.toString());
        info.setAttribute("name", displayName);
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    private String serviceName() {
        String clean = displayName.replaceAll("[^A-Za-z0-9_-]", "-");
        if (clean.length() == 0) {
            clean = "User";
        }
        if (clean.length() > 20) {
            clean = clean.substring(0, 20);
        }
        return "Walkie-" + clean + "-" + deviceUuid.toString().substring(0, 8);
    }

    private void discoverServices() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (SERVICE_TYPE.equals(serviceInfo.getServiceType())) {
                    enqueueResolve(serviceInfo);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                removeByServiceName(serviceInfo.getServiceName());
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                setStatus("Discovery gagal: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }
        };
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    private void enqueueResolve(NsdServiceInfo serviceInfo) {
        mainHandler.post(() -> {
            resolveQueue.offer(serviceInfo);
            if (!resolving) {
                resolveNext();
            }
        });
    }

    private void resolveNext() {
        NsdServiceInfo next = resolveQueue.poll();
        if (next == null || nsdManager == null) {
            resolving = false;
            return;
        }
        resolving = true;
        nsdManager.resolveService(next, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                mainHandler.post(() -> {
                    resolving = false;
                    resolveNext();
                });
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                mainHandler.post(() -> {
                    addResolvedPeer(serviceInfo);
                    resolving = false;
                    resolveNext();
                });
            }
        });
    }

    private void addResolvedPeer(NsdServiceInfo serviceInfo) {
        String id = attribute(serviceInfo, "id");
        if (id == null || id.equals(deviceUuid.toString())) {
            return;
        }
        InetAddress host = serviceInfo.getHost();
        if (host == null || serviceInfo.getPort() <= 0) {
            return;
        }
        String name = attribute(serviceInfo, "name");
        if (name == null || name.trim().length() == 0) {
            name = serviceInfo.getServiceName();
        }
        Peer peer = new Peer(
                id,
                sanitizeName(name),
                host.getHostAddress(),
                serviceInfo.getPort(),
                serviceInfo.getServiceName(),
                System.currentTimeMillis()
        );
        peers.put(peer.id, peer);
        broadcastPeers();
        updateNotification();
    }

    private String attribute(NsdServiceInfo serviceInfo, String key) {
        Map<String, byte[]> attributes = serviceInfo.getAttributes();
        byte[] bytes = attributes.get(key);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private void removeByServiceName(String serviceName) {
        boolean changed = false;
        Iterator<Map.Entry<String, Peer>> iterator = peers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Peer> entry = iterator.next();
            if (entry.getValue().serviceName.equals(serviceName)) {
                peers.remove(entry.getKey());
                changed = true;
            }
        }
        if (changed) {
            broadcastPeers();
            updateNotification();
        }
    }

    private void touchPeer(String id) {
        Peer peer = peers.get(id);
        if (peer != null) {
            peers.put(id, peer.seenNow(System.currentTimeMillis()));
        }
    }

    private void pruneStalePeers() {
        if (MODE_INTERNET.equals(mode)) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Map.Entry<String, Peer>> iterator = peers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Peer> entry = iterator.next();
            if (now - entry.getValue().lastSeenMillis > PEER_STALE_MS) {
                peers.remove(entry.getKey());
                changed = true;
            }
        }
        if (changed) {
            broadcastPeers();
            updateNotification();
        }
    }

    private List<Peer> getTargetsForCurrentSelection() {
        if (TARGET_ALL.equals(selectedPeerId)) {
            return getPeers();
        }
        Peer peer = peers.get(selectedPeerId);
        if (peer == null) {
            return Collections.emptyList();
        }
        List<Peer> target = new ArrayList<>();
        target.add(peer);
        return target;
    }

    private void setStatus(String status) {
        statusText = status;
        updateNotification();
        broadcastStatus();
    }

    private void broadcastPeers() {
        Intent intent = new Intent(ACTION_PEERS_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void broadcastStatus() {
        Intent intent = new Intent(ACTION_STATUS_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, statusText);
        intent.putExtra(EXTRA_WIFI_READY, wifiReady);
        sendBroadcast(intent);
    }
}
