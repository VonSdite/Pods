package io.github.pods.core;

public final class ModeSession {
    public static final long TIMEOUT_MS = 7000;
    private ListeningMode confirmed;
    private ListeningMode pending;
    private long deadline;

    public ListeningMode confirmed() { return confirmed; }
    public ListeningMode pending() { return pending; }

    public boolean begin(ListeningMode mode, long now) {
        if (mode == null || pending != null) return false;
        pending = mode;
        deadline = now + TIMEOUT_MS;
        return true;
    }

    // 写入成功不代表切换成功，只接受耳机返回的聆听模式。
    public boolean receive(int value) {
        ListeningMode mode = ListeningMode.fromValue(value);
        if (mode == null) return false;
        confirmed = mode;
        if (mode != pending) return false;
        pending = null;
        return true;
    }

    public boolean expire(long now) {
        if (pending == null || now < deadline) return false;
        pending = null;
        return true;
    }

    public void reset() {
        confirmed = null;
        pending = null;
        deadline = 0;
    }
}
