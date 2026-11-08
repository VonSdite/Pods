package io.github.pods.core;

import java.util.Objects;

/** 只有明确的摘戴变化可以控制媒体，未知状态不视为摘下。 */
public final class WearPlayback {
    public enum Action { NONE, PAUSE, PLAY }

    private static final long FRESH_MS = 15000;
    private static final long CONFIRM_MS = 600;
    private static final long PAUSE_ACK_MS = 2500;
    private Boolean worn;
    private Boolean candidate;
    private long candidateSince;
    private long lastSample = -1;
    private int confirmations;
    private boolean waitingForPause;
    private boolean pausedByUs;
    private long pauseRequestedAt;

    public Action observe(Boolean left, Boolean right, long receivedAt, long now, boolean playing) {
        return observe(left, right, receivedAt, now, playing, false);
    }

    public Action observe(Boolean left, Boolean right, long receivedAt, long now, boolean playing, boolean trusted) {
        playbackChanged(playing, now);
        expire(now);
        Boolean next = Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right) ? Boolean.TRUE
                : Boolean.FALSE.equals(left) && Boolean.FALSE.equals(right) ? Boolean.FALSE : null;
        if (next == null || receivedAt > now || now - receivedAt > FRESH_MS) {
            unknown();
            return Action.NONE;
        }
        // 重复交付或倒序的同一广播不能充当第二次确认。
        if (receivedAt <= lastSample) return Action.NONE;
        lastSample = receivedAt;
        if (!Objects.equals(candidate, next)) {
            candidate = next;
            candidateSince = receivedAt;
            confirmations = 1;
            if (!trusted) return Action.NONE;
        }
        confirmations++;
        if (!trusted && (confirmations < 2 || receivedAt - candidateSince < CONFIRM_MS)) return Action.NONE;
        Boolean previous = worn;
        worn = next;
        if (next && pausedByUs && !playing) {
            pausedByUs = false;
            return Action.PLAY;
        }
        if (Boolean.TRUE.equals(previous) && !next && playing) {
            waitingForPause = true;
            pausedByUs = false;
            pauseRequestedAt = now;
            return Action.PAUSE;
        }
        return Action.NONE;
    }

    public void playbackChanged(boolean playing, long now) {
        if (waitingForPause) {
            if (now - pauseRequestedAt > PAUSE_ACK_MS) {
                waitingForPause = false;
                pausedByUs = false;
            } else if (!playing) {
                waitingForPause = false;
                pausedByUs = true;
            }
        } else if (playing) {
            // 其他来源重新播放后，不再保留之前的自动继续记录。
            pausedByUs = false;
        }
    }

    public void expire(long now) {
        if (lastSample >= 0 && now - lastSample > FRESH_MS) {
            // 静默不代表摘下；保留已确认基线，变化仍须由新的连续广播确认。
            candidate = null;
            confirmations = 0;
        }
    }

    private void unknown() {
        worn = null;
        candidate = null;
        confirmations = 0;
        lastSample = -1;
    }

    public boolean canResume() { return pausedByUs; }

    public void reset() {
        unknown();
        waitingForPause = false;
        pausedByUs = false;
    }
}
