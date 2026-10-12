package com.michaelunkai.zcoderemote;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Locale;

/**
 * Wake-on-LAN: builds and broadcasts the magic packet that powers a PC back on
 * from S5 (soft-off), so the phone can bring the desktop environment back the
 * moment it is needed. Pure JVM code — no android.* imports — so the packet
 * logic stays unit-testable.
 */
public final class WakePc {

    private WakePc() {
    }

    /** Parses "AA:BB:CC:DD:EE:FF", "aa-bb-cc-dd-ee-ff" or "aabbccddeeff" into 6 bytes. */
    public static byte[] parseMac(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("MAC is empty");
        }
        String hex = raw.trim().toLowerCase(Locale.ROOT).replace(":", "").replace("-", "").replace(".", "");
        if (hex.length() != 12) {
            throw new IllegalArgumentException("MAC must have 12 hex digits");
        }
        byte[] mac = new byte[6];
        for (int i = 0; i < 6; i++) {
            mac[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return mac;
    }

    /** 6x 0xFF sync stream followed by the target MAC repeated 16 times (102 bytes total). */
    public static byte[] magicPacket(byte[] mac) {
        if (mac == null || mac.length != 6) {
            throw new IllegalArgumentException("MAC must be exactly 6 bytes");
        }
        byte[] packet = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) {
            packet[i] = (byte) 0xFF;
        }
        for (int r = 0; r < 16; r++) {
            for (int i = 0; i < 6; i++) {
                packet[6 + r * 6 + i] = mac[i];
            }
        }
        return packet;
    }

    /**
     * Sends the magic packet to {@code targetHost} on UDP {@code port}, repeated
     * {@code repeats} times for loss tolerance. LAN use: broadcast address
     * (e.g. 192.168.1.255 or 255.255.255.255). Away from home: the router can
     * forward a UDP port to the subnet broadcast for a directed wake.
     *
     * @return the number of packets actually handed to the network stack.
     */
    public static int send(String macRaw, String targetHost, int port, int repeats) throws Exception {
        byte[] packet = magicPacket(parseMac(macRaw));
        InetAddress address = InetAddress.getByName(targetHost);
        int sent = 0;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (int i = 0; i < repeats; i++) {
                try {
                    socket.send(new DatagramPacket(packet, packet.length, address, port));
                    sent++;
                } catch (Exception ignored) {
                    // Keep trying the remaining repeats; one lost datagram must not abort the wake.
                }
            }
        }
        return sent;
    }
}
