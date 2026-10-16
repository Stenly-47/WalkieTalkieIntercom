package com.stanly.walkietalkie;

import java.nio.ByteBuffer;
import java.util.UUID;

final class AudioPacket {
    static final int SAMPLE_RATE = 8000;
    static final int HEADER_BYTES = 24;
    static final int HEARTBEAT_SEQUENCE = -1;

    private static final int MAGIC = 0x57544B31;

    private AudioPacket() {
    }

    static void writeHeader(byte[] packet, int sequence, UUID senderId) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.putInt(MAGIC);
        buffer.putInt(sequence);
        buffer.putLong(senderId.getMostSignificantBits());
        buffer.putLong(senderId.getLeastSignificantBits());
    }

    static void writeHeartbeat(byte[] packet, UUID senderId) {
        writeHeader(packet, HEARTBEAT_SEQUENCE, senderId);
    }

    static boolean isValid(byte[] packet, int length) {
        if (length < HEADER_BYTES) {
            return false;
        }
        return ByteBuffer.wrap(packet, 0, 4).getInt() == MAGIC;
    }

    static UUID readSenderId(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.getInt();
        buffer.getInt();
        long most = buffer.getLong();
        long least = buffer.getLong();
        return new UUID(most, least);
    }

    static boolean isHeartbeat(byte[] packet) {
        return ByteBuffer.wrap(packet, 4, 4).getInt() == HEARTBEAT_SEQUENCE;
    }
}
