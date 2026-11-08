package io.github.pods.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModeSessionTest {
    @Test public void sendingDoesNotChangeConfirmedMode() {
        ModeSession session = new ModeSession();
        session.receive(3);
        assertTrue(session.begin(ListeningMode.OFF, 100));
        assertEquals(ListeningMode.TRANSPARENCY, session.confirmed());
        assertEquals(ListeningMode.OFF, session.pending());
    }

    @Test public void matchingEarphoneReportConfirmsRequest() {
        ModeSession session = new ModeSession();
        session.begin(ListeningMode.OFF, 100);
        assertTrue(session.receive(1));
        assertNull(session.pending());
        assertEquals(ListeningMode.OFF, session.confirmed());
    }

    @Test public void unrelatedReportDoesNotConfirmPendingRequest() {
        ModeSession session = new ModeSession();
        session.begin(ListeningMode.OFF, 0);
        assertFalse(session.receive(3));
        assertEquals(ListeningMode.OFF, session.pending());
        assertEquals(ListeningMode.TRANSPARENCY, session.confirmed());
    }

    @Test public void timeoutKeepsActualModeAndAllowsRetry() {
        ModeSession session = new ModeSession();
        session.receive(3);
        session.begin(ListeningMode.OFF, 500);
        assertFalse(session.expire(7499));
        assertTrue(session.expire(7500));
        assertEquals(ListeningMode.TRANSPARENCY, session.confirmed());
        assertNull(session.pending());
        assertTrue(session.begin(ListeningMode.OFF, 8000));
    }

    @Test public void rapidRequestsCannotReplaceAnInFlightRequest() {
        ModeSession session = new ModeSession();
        assertTrue(session.begin(ListeningMode.OFF, 0));
        assertFalse(session.begin(ListeningMode.NOISE_CANCELLATION, 1));
        assertEquals(ListeningMode.OFF, session.pending());
    }

    @Test public void unknownFirmwareValuesDoNotBecomeOff() {
        ModeSession session = new ModeSession();
        session.begin(ListeningMode.OFF, 0);
        assertFalse(session.receive(0));
        assertFalse(session.receive(255));
        assertNull(session.confirmed());
        assertEquals(ListeningMode.OFF, session.pending());
    }

    @Test public void disconnectClearsBothPendingAndConfirmedMode() {
        ModeSession session = new ModeSession();
        session.receive(1);
        session.begin(ListeningMode.TRANSPARENCY, 0);
        session.reset();
        assertNull(session.confirmed());
        assertNull(session.pending());
        assertFalse(session.expire(Long.MAX_VALUE));
    }
}
