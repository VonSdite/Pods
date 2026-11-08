package io.github.pods.core;

import org.junit.Test;

import static io.github.pods.core.WearPlayback.Action.*;
import static org.junit.Assert.*;

public class WearPlaybackTest {
    private final WearPlayback playback = new WearPlayback();

    private WearPlayback.Action sample(Boolean left, Boolean right, long at, boolean playing) {
        return playback.observe(left, right, at, at, playing);
    }

    private void worn() {
        assertEquals(NONE, sample(true, true, 0, true));
        assertEquals(NONE, sample(true, true, 600, true));
    }

    private void pause() {
        worn();
        assertEquals(NONE, sample(false, false, 1000, true));
        assertEquals(PAUSE, sample(false, false, 1600, true));
    }

    @Test public void firstObservationNeverStartsOrPausesMusic() {
        assertEquals(NONE, sample(false, false, 0, true));
        assertEquals(NONE, sample(false, false, 600, true));
        assertEquals(NONE, sample(true, false, 1000, false));
        assertEquals(NONE, sample(true, false, 1600, false));
    }

    @Test public void removingOneEarKeepsPlaying() {
        worn();
        assertEquals(NONE, sample(false, true, 1000, true));
        assertEquals(NONE, sample(false, true, 1600, true));
        assertEquals(NONE, sample(false, false, 2000, true));
        assertEquals(PAUSE, sample(false, false, 2600, true));
    }

    @Test public void eitherEarResumesOnlyAfterOurPauseTakesEffect() {
        pause();
        assertFalse(playback.canResume());
        playback.playbackChanged(false, 1700);
        assertTrue(playback.canResume());
        assertEquals(NONE, sample(false, true, 2000, false));
        assertEquals(PLAY, sample(false, true, 2600, false));
        assertEquals(NONE, sample(false, true, 3200, false));
        assertFalse(playback.canResume());
    }

    @Test public void alreadyPausedMusicIsNotResumed() {
        worn();
        assertEquals(NONE, sample(false, false, 1000, false));
        assertEquals(NONE, sample(false, false, 1600, false));
        assertEquals(NONE, sample(true, false, 2000, false));
        assertEquals(NONE, sample(true, false, 2600, false));
    }

    @Test public void repeatedPacketsAndShortGlitchesCannotPause() {
        worn();
        assertEquals(NONE, sample(false, false, 1000, true));
        assertEquals(NONE, playback.observe(false, false, 1000, 1800, true));
        assertEquals(NONE, sample(false, false, 1100, true));
        assertEquals(NONE, sample(true, false, 1200, true));
        assertEquals(NONE, sample(false, false, 2000, true));
        assertEquals(PAUSE, sample(false, false, 2600, true));
        assertEquals(NONE, sample(false, false, 3200, true));
    }

    @Test public void unknownDoesNotCountAsRemoval() {
        worn();
        assertEquals(NONE, sample(null, false, 1000, true));
        assertEquals(NONE, sample(false, false, 2000, true));
        assertEquals(NONE, sample(false, false, 2600, true));
    }

    @Test public void staleOrFutureSamplesCannotControlPlayback() {
        worn();
        assertEquals(NONE, playback.observe(false, false, 1000, 17000, true));
        assertEquals(NONE, playback.observe(false, false, 19000, 18000, true));
        assertEquals(NONE, sample(false, false, 20000, true));
        assertEquals(NONE, sample(false, false, 20600, true));
    }

    @Test public void missingBroadcastDoesNotPauseAndFreshWearCanRestoreOurPause() {
        pause();
        playback.playbackChanged(false, 1700);
        playback.expire(30000);
        assertTrue(playback.canResume());
        assertEquals(NONE, sample(true, false, 31000, false));
        assertEquals(PLAY, sample(true, false, 31600, false));
    }

    @Test public void quietBroadcastGapKeepsConfirmedWearBaseline() {
        worn();
        playback.expire(30000);
        assertEquals(NONE, sample(false, false, 31000, true));
        assertEquals(PAUSE, sample(false, false, 31600, true));
    }

    @Test public void removalCandidateBeforeGapNeedsTwoNewConfirmations() {
        worn();
        assertEquals(NONE, sample(false, false, 1000, true));
        playback.expire(30000);
        assertEquals(NONE, sample(false, false, 31000, true));
        assertEquals(PAUSE, sample(false, false, 31600, true));
    }

    @Test public void manualPlaybackCancelsAutoResumeEvenIfPausedAgain() {
        pause();
        playback.playbackChanged(false, 1700);
        playback.playbackChanged(true, 1800);
        playback.playbackChanged(false, 1900);
        assertEquals(NONE, sample(true, false, 2000, false));
        assertEquals(NONE, sample(true, false, 2600, false));
    }

    @Test public void unhandledPauseDoesNotArmAutoResume() {
        pause();
        playback.playbackChanged(true, 4200);
        playback.playbackChanged(false, 4300);
        assertEquals(NONE, sample(true, false, 4500, false));
        assertEquals(NONE, sample(true, false, 5100, false));
    }

    @Test public void disconnectDeviceSwitchAndDisableClearResume() {
        pause();
        playback.playbackChanged(false, 1700);
        playback.reset();
        assertFalse(playback.canResume());
        assertEquals(NONE, sample(true, true, 2000, false));
        assertEquals(NONE, sample(true, true, 2600, false));
    }

    @Test public void controlChannelTransitionsDoNotRequireRepeatedPackets() {
        assertEquals(NONE, playback.observe(true, true, 0, 0, true, true));
        assertEquals(PAUSE, playback.observe(false, false, 1000, 1000, true, true));
        playback.playbackChanged(false, 1100);
        assertEquals(PLAY, playback.observe(true, false, 2000, 2000, false, true));
    }

    @Test public void oneKnownWornEarIsEnoughButOneUnknownRemovedEarIsNot() {
        assertEquals(NONE, sample(true, null, 0, true));
        assertEquals(NONE, sample(true, null, 600, true));
        assertEquals(NONE, sample(false, null, 1000, true));
        assertEquals(NONE, sample(false, null, 1600, true));
    }
}
