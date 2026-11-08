package io.github.pods.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class AutoConnectionTest {
    @Test public void existingAudioDeviceStartsWithoutUserAction() {
        AutoConnection session = new AutoConnection();
        assertFalse(session.begin(100));
        assertTrue(session.follow("first"));
        assertTrue(session.begin(100));
        assertFalse(session.begin(101));
    }

    @Test public void repeatedProfileEventsDoNotDuplicateConnections() {
        AutoConnection session = new AutoConnection();
        session.follow("first");
        session.begin(0);
        assertFalse(session.follow("first"));
        assertFalse(session.begin(20000));
        session.ready();
        assertFalse(session.begin(30000));
    }

    @Test public void unsupportedSystemGetsOneDelayedRetry() {
        AutoConnection session = new AutoConnection();
        session.follow("first");
        assertTrue(session.begin(0));
        assertEquals(1, session.attempts());
        session.failed(8000);
        assertFalse(session.begin(17999));
        assertTrue(session.begin(18000));
        assertEquals(2, session.attempts());
        session.failed(26000);
        assertFalse(session.begin(36000));
        assertFalse(session.follow("first"));
        assertFalse(session.begin(100000));
    }

    @Test public void realAudioReconnectRestoresRetryBudget() {
        AutoConnection session = new AutoConnection();
        session.follow("first");
        session.begin(0);
        session.failed(8000);
        session.follow(null);
        assertEquals(0, session.attempts());
        assertFalse(session.begin(9000));
        session.follow("first");
        assertTrue(session.begin(9001));
    }

    @Test public void switchingDevicesDoesNotReuseOldFailureState() {
        AutoConnection session = new AutoConnection();
        session.follow("first");
        session.begin(0);
        session.failed(8000);
        session.follow("second");
        assertTrue(session.begin(8001));
        session.ready();
        session.failed(10000);
        assertTrue(session.begin(20000));
    }
}
