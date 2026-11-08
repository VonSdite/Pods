package io.github.pods.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class AirPodsIdentityTest {
    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    @Test public void renamedHeadphonesAreRecognizedByServiceUuid() {
        assertTrue(AirPodsIdentity.recognizes("我的耳机", new String[]{AirPodsIdentity.SERVICE_UUID}));
        assertTrue(AirPodsIdentity.recognizes("AirPods #2", null));
        assertFalse(AirPodsIdentity.recognizes("其他耳机", new String[]{"0000110b-0000-1000-8000-00805f9b34fb"}));
        assertFalse(AirPodsIdentity.recognizes(null, null));
    }

    @Test public void systemResolvedIdentityAddressNeedsNoManualBinding() {
        assertTrue(AirPodsIdentity.matches("01:02:03:04:05:06", "01:02:03:04:05:06", null));
        assertFalse(AirPodsIdentity.matches("01:02:03:04:05:06", "11:12:13:14:15:16", null));
        assertFalse(AirPodsIdentity.matches(null, "01:02:03:04:05:06", null));
    }

    @Test public void randomAddressMatchesBluetoothSpecificationVector() {
        // 蓝牙规范的 ah 示例，IRK 按耳机回报的小端顺序排列。
        byte[] irk = hex("9b7d390aa610103405adc857a33402ec");
        assertTrue(AirPodsIdentity.resolves("70:81:94:0D:FB:AA", irk));
        assertTrue(AirPodsIdentity.matches("01:02:03:04:05:06", "70:81:94:0D:FB:AA", irk));
        assertFalse(AirPodsIdentity.resolves("70:81:94:0D:FB:AB", irk));
        assertFalse(AirPodsIdentity.resolves("70:81:94:0D:FB:AA", new byte[16]));
    }

    @Test public void nearbyAndMalformedAddressesAreNotClaimed() {
        byte[] irk = new byte[16];
        assertFalse(AirPodsIdentity.resolves("invalid", irk));
        assertFalse(AirPodsIdentity.resolves("FF:81:94:0D:FB:AA", irk));
        assertFalse(AirPodsIdentity.resolves("70:81:94:0D:FB:AA", new byte[15]));
        assertFalse(AirPodsIdentity.resolves(null, irk));
    }
}
