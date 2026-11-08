package io.github.pods.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class BatteryStatusTest {
    @Test public void firstConnectionWithNoReadingsDoesNotCrashOrShowZero() {
        assertNull(BatteryStatus.level(null, null, null));
    }

    @Test public void oneKnownEarIsEnoughEvenWithoutSystemBattery() {
        assertEquals(Integer.valueOf(90), BatteryStatus.level(90, null, null));
        assertEquals(Integer.valueOf(80), BatteryStatus.level(null, 80, null));
    }

    @Test public void lowerEarTakesPriorityOverSystemBattery() {
        assertEquals(Integer.valueOf(60), BatteryStatus.level(90, 60, 100));
        assertEquals(Integer.valueOf(0), BatteryStatus.level(0, 10, null));
    }

    @Test public void systemBatteryIsUsedOnlyWhenBothEarsAreUnknown() {
        assertEquals(Integer.valueOf(70), BatteryStatus.level(null, null, 70));
        assertEquals(Integer.valueOf(90), BatteryStatus.level(90, null, 70));
    }
}
