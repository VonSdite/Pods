package io.github.pods.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class NearbyAirPodsTest {
    private AirPodsAdvertisement frame(int model, int status, int batteries, int lid) {
        byte[] bytes = new byte[27];
        bytes[0] = 7;
        bytes[1] = 25;
        bytes[2] = 1;
        bytes[3] = (byte) (model >> 8);
        bytes[4] = (byte) model;
        bytes[5] = (byte) status;
        bytes[6] = (byte) batteries;
        bytes[7] = 9;
        bytes[8] = (byte) lid;
        return AirPodsAdvertisement.parse(bytes);
    }

    @Test public void singleNearbyDeviceDoesNotRequireAudioAddressOrIdentityKey() {
        NearbyAirPods monitor = new NearbyAirPods();
        monitor.observe("random-a", frame(0x3620, 0x22, 0x87, 0), -51, 1000);
        assertEquals(NearbyAirPods.State.WAITING, monitor.select(1000).state);
        NearbyAirPods.Selection selection = monitor.select(4000);
        assertEquals(NearbyAirPods.State.SINGLE, selection.state);
        assertEquals(Integer.valueOf(70), selection.sample.data.leftBattery);
        assertTrue(selection.sample.data.leftInEar);
    }

    @Test public void strongerSignalCannotChooseBetweenDifferentNearbyPairs() {
        NearbyAirPods monitor = new NearbyAirPods();
        monitor.observe("random-a", frame(0x3620, 0x20, 0x87, 0), -35, 1000);
        monitor.select(1000);
        assertEquals(NearbyAirPods.State.SINGLE, monitor.select(4000).state);
        monitor.observe("random-b", frame(0x2720, 0x20, 0x99, 0), -75, 4001);
        NearbyAirPods.Selection selection = monitor.select(4001);
        assertEquals(NearbyAirPods.State.AMBIGUOUS, selection.state);
        assertEquals(2, selection.candidates);
        assertNull(selection.sample);
    }

    @Test public void twoEarphonesAndAddressRotationUseNormalizedBatteryOrder() {
        NearbyAirPods monitor = new NearbyAirPods();
        monitor.observe("left-random", frame(0x3620, 0x20, 0x87, 0), -50, 1000);
        monitor.select(1000);
        monitor.observe("right-random", frame(0x3620, 0, 0x78, 0), -55, 4000);
        NearbyAirPods.Selection selection = monitor.select(4000);
        assertEquals(NearbyAirPods.State.SINGLE, selection.state);
        assertEquals(1, selection.candidates);
        assertEquals(Integer.valueOf(70), selection.sample.data.leftBattery);
    }

    @Test public void differentBatteryDoesNotSilentlyMergePairs() {
        NearbyAirPods monitor = new NearbyAirPods();
        monitor.observe("a", frame(0x3620, 0x20, 0x87, 0), -50, 1000);
        monitor.observe("b", frame(0x3620, 0x20, 0x99, 0), -50, 1000);
        assertEquals(NearbyAirPods.State.AMBIGUOUS, monitor.select(4000).state);
    }

    @Test public void unknownModelsAndDistantBroadcastsDoNotBecomeNearbyAirPods() {
        NearbyAirPods monitor = new NearbyAirPods();
        monitor.observe("a", frame(0xEE20, 0x20, 0x87, 0), -50, 1000);
        monitor.observe("b", frame(0x3620, 0x20, 0x87, 0), -95, 1000);
        assertEquals(NearbyAirPods.State.NONE, monitor.select(4000).state);
    }

    @Test public void ambiguityMustSettleAgainAfterOtherDeviceExpires() {
        NearbyAirPods monitor = new NearbyAirPods();
        monitor.observe("a", frame(0x3620, 0x20, 0x87, 0), -50, 1000);
        monitor.observe("b", frame(0x2720, 0x20, 0x99, 0), -50, 1000);
        assertEquals(NearbyAirPods.State.AMBIGUOUS, monitor.select(1000).state);
        monitor.observe("a", frame(0x3620, 0x20, 0x87, 0), -50, 22000);
        assertEquals(NearbyAirPods.State.WAITING, monitor.select(22000).state);
        assertEquals(NearbyAirPods.State.SINGLE, monitor.select(25000).state);
        assertEquals(NearbyAirPods.State.NONE, monitor.select(42001).state);
    }

    @Test public void recentCaseBroadcastWinsButCannotHideNewEarStateIndefinitely() {
        NearbyAirPods monitor = new NearbyAirPods();
        monitor.observe("case", frame(0x3620, 0x64, 0x99, 0), -50, 1000);
        monitor.select(1000);
        monitor.observe("ear", frame(0x3620, 0x22, 0x99, 0), -50, 2000);
        monitor.select(2000);
        monitor.observe("ear", frame(0x3620, 0x22, 0x99, 0), -50, 3000);
        assertEquals(Boolean.TRUE, monitor.select(4000).sample.data.lidOpen);
        monitor.observe("ear", frame(0x3620, 0x22, 0x99, 0), -50, 5000);
        assertTrue(monitor.select(5000).sample.data.leftInEar);
        assertNull(monitor.select(5000).sample.data.lidOpen);
    }

    @Test public void resetDoesNotCarryPreviousEarphonesIntoNextConnection() {
        NearbyAirPods monitor = new NearbyAirPods();
        monitor.observe("a", frame(0x3620, 0x20, 0x87, 0), -50, 1000);
        monitor.select(1000);
        assertEquals(NearbyAirPods.State.SINGLE, monitor.select(4000).state);
        monitor.reset();
        assertEquals(NearbyAirPods.State.NONE, monitor.select(4001).state);
    }
}
