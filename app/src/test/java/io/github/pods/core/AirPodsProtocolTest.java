package io.github.pods.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class AirPodsProtocolTest {
    @Test public void identityKeyRequestMatchesPublishedProtocol() {
        assertArrayEquals(new byte[]{4, 0, 4, 0, 0x30, 0, 1, 0}, AirPodsProtocol.requestIdentityKey());
    }

    @Test public void splitIdentityKeyResponseDoesNotDecodeEmbeddedHeaders() {
        byte[] frame = new byte[27];
        byte[] prefix = {4, 0, 4, 0, 0x31, 0, 1, 1, 0, 16, 0};
        System.arraycopy(prefix, 0, frame, 0, prefix.length);
        byte[] key = {4, 0, 4, 0, 9, 0, 13, 1, 0, 0, 0, 7, 8, 9, 10, 11};
        System.arraycopy(key, 0, frame, 11, key.length);
        AirPodsProtocol.Decoder decoder = new AirPodsProtocol.Decoder();
        assertTrue(decoder.feed(java.util.Arrays.copyOf(frame, 15)).isEmpty());
        java.util.List<byte[]> parsed = decoder.feed(java.util.Arrays.copyOfRange(frame, 15, frame.length));
        assertEquals(1, parsed.size());
        assertArrayEquals(key, AirPodsProtocol.identityKey(parsed.get(0)));
        assertNull(AirPodsProtocol.identityKey(java.util.Arrays.copyOf(frame, 20)));
        frame[9] = (byte) 255;
        assertNull(AirPodsProtocol.identityKey(frame));
    }

    private static byte[] hex(String value) {
        String clean = value.replace(" ", "");
        byte[] bytes = new byte[clean.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    @Test public void offCommandsMatchKnownWireFormat() {
        assertArrayEquals(hex("0400040009003401000000"), AirPodsProtocol.control(AirPodsProtocol.ALLOW_OFF, 1));
        assertArrayEquals(hex("0400040009000d01000000"), AirPodsProtocol.control(AirPodsProtocol.LISTENING_MODE, ListeningMode.OFF.value));
    }

    @Test public void everyPossibleReadBoundaryPreservesFrames() {
        byte[] source = hex("0400040009000d0300000004000400060000010400040009000d01000000");
        for (int split = 0; split <= source.length; split++) {
            AirPodsProtocol.Decoder decoder = new AirPodsProtocol.Decoder();
            List<byte[]> frames = new ArrayList<>(decoder.feed(Arrays.copyOfRange(source, 0, split)));
            frames.addAll(decoder.feed(Arrays.copyOfRange(source, split, source.length)));
            assertEquals("Split at " + split, 3, frames.size());
            assertArrayEquals(hex("0400040009000d01000000"), frames.get(2));
        }
    }

    @Test public void byteByByteReadsDoNotEmitPartialState() {
        AirPodsProtocol.Decoder decoder = new AirPodsProtocol.Decoder();
        byte[] packet = hex("0400040009000d02000000");
        for (int i = 0; i < packet.length - 1; i++) assertTrue(decoder.feed(new byte[]{packet[i]}).isEmpty());
        assertEquals(1, decoder.feed(new byte[]{packet[packet.length - 1]}).size());
    }

    @Test public void malformedAndUnknownPacketsResynchronize() {
        AirPodsProtocol.Decoder decoder = new AirPodsProtocol.Decoder();
        List<byte[]> frames = decoder.feed(hex("ffff040004004d00d700000000000000040004000400ff0400040009000d01000000"));
        assertEquals(1, frames.size());
        assertArrayEquals(hex("0400040009000d01000000"), frames.get(0));
    }

    @Test public void batteryComponentsAreIdentifiedIndependentlyOfOrder() {
        byte[] packet = hex("04000400040003 0200550200 04004b0100 0800640200");
        List<AirPodsProtocol.Battery> readings = AirPodsProtocol.batteries(packet);
        assertEquals(3, readings.size());
        assertEquals(AirPodsProtocol.RIGHT, readings.get(0).component);
        assertEquals(Integer.valueOf(85), readings.get(0).level);
        assertEquals(AirPodsProtocol.LEFT, readings.get(1).component);
        assertTrue(readings.get(1).charging);
        assertEquals(Integer.valueOf(100), readings.get(2).level);
    }

    @Test public void disconnectedAndInvalidBatteriesStayUnknown() {
        List<AirPodsProtocol.Battery> readings = AirPodsProtocol.batteries(hex("04000400040003 0400320400 0200ff0200 0800000200"));
        assertNull(readings.get(0).level);
        assertNull(readings.get(1).level);
        assertEquals(Integer.valueOf(0), readings.get(2).level);
    }

    @Test public void truncatedBatteryPacketDoesNotPublishValues() {
        assertTrue(AirPodsProtocol.batteries(hex("04000400040003 0400320100")).isEmpty());
        assertTrue(AirPodsProtocol.batteries(hex("04000400040000")).isEmpty());
    }

    @Test public void variableComponentCountsCanBeDecoded() {
        byte[] oneBattery = hex("04000400040001 04005a0200");
        AirPodsProtocol.Decoder decoder = new AirPodsProtocol.Decoder();
        assertEquals(1, decoder.feed(oneBattery).size());
        assertEquals(1, AirPodsProtocol.batteries(oneBattery).size());
    }

    @Test public void nonProtocolPrefixIsRejected() {
        assertFalse(AirPodsProtocol.isFrame(hex("0000040009000d01000000"), 9));
        assertFalse(AirPodsProtocol.isFrame(null, 9));
    }

    @Test(expected = IllegalArgumentException.class) public void invalidControlValueCannotReachWire() {
        AirPodsProtocol.control(13, 256);
    }
}
