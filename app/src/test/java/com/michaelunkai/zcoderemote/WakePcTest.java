package com.michaelunkai.zcoderemote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** Pure JUnit 4 tests for the Wake-on-LAN packet builder contract. */
public class WakePcTest {

    @Test
    public void parseMac_colonSeparators_parsed() {
        assertArrayEquals(
                new byte[] {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF},
                WakePc.parseMac("AA:BB:CC:DD:EE:FF"));
    }

    @Test
    public void parseMac_dashSeparatorsAndLowercase_parsed() {
        assertArrayEquals(
                new byte[] {0x00, 0x11, 0x22, 0x33, 0x44, 0x55},
                WakePc.parseMac("00-11-22-33-44-55"));
    }

    @Test
    public void parseMac_noSeparators_parsed() {
        assertArrayEquals(
                new byte[] {0x12, 0x34, 0x56, 0x78, (byte) 0x9A, (byte) 0xBC},
                WakePc.parseMac("123456789abc"));
    }

    @Test
    public void parseMac_wrongLength_throws() {
        assertThrows(IllegalArgumentException.class, () -> WakePc.parseMac("AA:BB:CC"));
        assertThrows(IllegalArgumentException.class, () -> WakePc.parseMac(""));
        assertThrows(IllegalArgumentException.class, () -> WakePc.parseMac(null));
    }

    @Test
    public void magicPacket_is102BytesWithSyncAndSixteenMacRepeats() {
        byte[] mac = WakePc.parseMac("DE:AD:BE:EF:01:02");
        byte[] packet = WakePc.magicPacket(mac);
        assertEquals(102, packet.length);
        for (int i = 0; i < 6; i++) {
            assertEquals((byte) 0xFF, packet[i]);
        }
        for (int r = 0; r < 16; r++) {
            for (int i = 0; i < 6; i++) {
                assertEquals(mac[i], packet[6 + r * 6 + i]);
            }
        }
    }

    @Test
    public void magicPacket_wrongMacSize_throws() {
        assertThrows(IllegalArgumentException.class, () -> WakePc.magicPacket(new byte[5]));
        assertThrows(IllegalArgumentException.class, () -> WakePc.magicPacket(null));
    }
}
