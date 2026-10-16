package com.stanly.walkietalkie;

import java.util.Locale;

public final class Peer {
    public final String id;
    public final String name;
    public final String host;
    public final int port;
    public final String serviceName;
    public final long lastSeenMillis;

    public Peer(String id, String name, String host, int port, String serviceName, long lastSeenMillis) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.serviceName = serviceName;
        this.lastSeenMillis = lastSeenMillis;
    }

    public String endpointLabel() {
        if (port <= 0 || "internet".equals(host)) {
            return "Internet relay";
        }
        return String.format(Locale.US, "%s:%d", host, port);
    }

    public Peer seenNow(long nowMillis) {
        return new Peer(id, name, host, port, serviceName, nowMillis);
    }
}
