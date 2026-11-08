package io.github.pods.core;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class AirPodsAdvertisementTest {
    @Test public void statusCanFollowAnotherContinuityMessage() {
        byte[] status = advertisement(0x20, 0x87, 0x19);
        byte[] combined = new byte[status.length + 4];
        combined[0] = 0x12;
        combined[1] = 2;
        System.arraycopy(status, 0, combined, 4, status.length);
        assertEquals(Integer.valueOf(70), AirPodsAdvertisement.parse(combined).leftBattery);
        combined[1] = (byte) 255;
        assertNull(AirPodsAdvertisement.parse(combined));
    }

    private byte[] advertisement(int status, int pods, int caseFlags) {
        byte[] data = new byte[27];
        data[0] = 7;
        data[1] = 25;
        data[2] = 1;
        data[3] = 0x36;
        data[4] = 0x20;
        data[5] = (byte) status;
        data[6] = (byte) pods;
        data[7] = (byte) caseFlags;
        return data;
    }

    @Test public void fifthGenerationModelsUseVerifiedBroadcastIdentifiers() {
        byte[] data = advertisement(0x20, 0x87, 0x09);
        assertEquals("AirPods 5", AirPodsAdvertisement.parse(data).model);
        data[3] = 0x30;
        assertEquals("AirPods 5 · 无线充电款", AirPodsAdvertisement.parse(data).model);
    }

    @Test public void primarySideChangesBatteryAndChargingOrder() {
        AirPodsAdvertisement leftPrimary = AirPodsAdvertisement.parse(advertisement(0x20, 0x87, 0x19));
        assertEquals(Integer.valueOf(70), leftPrimary.leftBattery);
        assertEquals(Integer.valueOf(80), leftPrimary.rightBattery);
        assertTrue(leftPrimary.leftCharging);
        assertFalse(leftPrimary.rightCharging);
        AirPodsAdvertisement rightPrimary = AirPodsAdvertisement.parse(advertisement(0, 0x87, 0x19));
        assertEquals(Integer.valueOf(80), rightPrimary.leftBattery);
        assertEquals(Integer.valueOf(70), rightPrimary.rightBattery);
        assertFalse(rightPrimary.leftCharging);
        assertTrue(rightPrimary.rightCharging);
    }

    @Test public void unknownBatteryIsDistinctFromEmptyBattery() {
        AirPodsAdvertisement data = AirPodsAdvertisement.parse(advertisement(0x20, 0xF0, 0x0F));
        assertEquals(Integer.valueOf(0), data.leftBattery);
        assertNull(data.rightBattery);
        assertNull(data.caseBattery);
    }

    @Test public void inEarBitsAccountForPrimaryEarphoneInCase() {
        assertTrue(AirPodsAdvertisement.parse(advertisement(0x22, 0, 0)).leftInEar);
        assertFalse(AirPodsAdvertisement.parse(advertisement(0x22, 0, 0)).rightInEar);
        assertFalse(AirPodsAdvertisement.parse(advertisement(0x62, 0, 0)).leftInEar);
        assertTrue(AirPodsAdvertisement.parse(advertisement(0x62, 0, 0)).rightInEar);
    }

    @Test public void chargingAndBothInCaseOverrideStaleInEarBits() {
        AirPodsAdvertisement charging = AirPodsAdvertisement.parse(advertisement(0x2A, 0x99, 0x39));
        assertTrue(charging.leftCharging && charging.rightCharging);
        assertFalse(charging.leftInEar || charging.rightInEar);
        AirPodsAdvertisement inCase = AirPodsAdvertisement.parse(advertisement(0x2E, 0x99, 0x09));
        assertFalse(inCase.leftInEar || inCase.rightInEar);
    }

    @Test public void microphoneSideAccountsForPrimaryEarphoneInCase() {
        AirPodsAdvertisement left = AirPodsAdvertisement.parse(advertisement(0x20, 0x99, 0x09));
        assertEquals(Boolean.TRUE, left.leftMicrophone);
        assertEquals(Boolean.FALSE, left.rightMicrophone);
        AirPodsAdvertisement right = AirPodsAdvertisement.parse(advertisement(0, 0x99, 0x09));
        assertEquals(Boolean.FALSE, right.leftMicrophone);
        assertEquals(Boolean.TRUE, right.rightMicrophone);
        AirPodsAdvertisement inCase = AirPodsAdvertisement.parse(advertisement(0x60, 0x99, 0x09));
        assertEquals(Boolean.FALSE, inCase.leftMicrophone);
        assertEquals(Boolean.TRUE, inCase.rightMicrophone);
        AirPodsAdvertisement reversed = AirPodsAdvertisement.parse(advertisement(0x40, 0x99, 0x09));
        assertEquals(Boolean.TRUE, reversed.leftMicrophone);
        assertEquals(Boolean.FALSE, reversed.rightMicrophone);
    }

    @Test public void chargingOrBothInCaseDoesNotClaimAnActiveMicrophone() {
        AirPodsAdvertisement charging = AirPodsAdvertisement.parse(advertisement(0x20, 0x99, 0x19));
        assertNull(charging.leftMicrophone);
        assertEquals(Boolean.FALSE, charging.rightMicrophone);
        AirPodsAdvertisement inCase = AirPodsAdvertisement.parse(advertisement(0x2E, 0x99, 0x09));
        assertNull(inCase.leftMicrophone);
        assertNull(inCase.rightMicrophone);
    }

    @Test public void encryptedAndTruncatedBroadcastsAreNotPresentedAsBattery() {
        byte[] data = advertisement(0, 0, 0);
        assertNull(AirPodsAdvertisement.parse(Arrays.copyOf(data, 10)));
        data[2] = 7;
        assertNull(AirPodsAdvertisement.parse(data));
        data[2] = 1;
        data[0] = 0x12;
        assertNull(AirPodsAdvertisement.parse(data));
    }

    @Test public void unknownModelDoesNotPromiseAdaptiveSupport() {
        byte[] data = advertisement(0, 0, 0);
        data[3] = (byte) 0xEE;
        AirPodsAdvertisement parsed = AirPodsAdvertisement.parse(data);
        assertEquals("AirPods · 型号待确认", parsed.model);
        assertFalse(parsed.supportsAdaptive());
    }

    @Test public void lidStateRequiresAnAuthoritativeInCaseBroadcast() {
        byte[] data = advertisement(0x13, 0x99, 0xF9);
        data[8] = 0x11;
        assertNull(AirPodsAdvertisement.parse(data).lidOpen);
        data[5] = 0x15;
        data[8] = 0x51;
        assertEquals(Boolean.TRUE, AirPodsAdvertisement.parse(data).lidOpen);
        data[8] = 0x59;
        assertEquals(Boolean.FALSE, AirPodsAdvertisement.parse(data).lidOpen);
    }

    @Test public void fifthGenerationOpenCaseFieldsMatchPublishedCapture() {
        byte[] data = advertisement(0x15, 0x99, 0xF9);
        data[8] = 0x51;
        AirPodsAdvertisement parsed = AirPodsAdvertisement.parse(data);
        assertEquals("AirPods 5", parsed.model);
        assertEquals(Integer.valueOf(90), parsed.leftBattery);
        assertEquals(Integer.valueOf(90), parsed.rightBattery);
        assertEquals(Integer.valueOf(90), parsed.caseBattery);
        assertTrue(parsed.leftCharging && parsed.rightCharging && parsed.caseCharging);
        assertFalse(parsed.leftInEar || parsed.rightInEar);
        assertEquals(Boolean.TRUE, parsed.lidOpen);
    }
}
