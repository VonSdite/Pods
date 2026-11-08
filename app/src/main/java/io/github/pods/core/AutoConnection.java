package io.github.pods.core;

import java.util.Objects;

// 同一段音频连接最多尝试两次，避免系统不兼容时不断重连。
public final class AutoConnection {
    private String device;
    private int attempts;
    private long retryAt;
    private boolean busy;
    private boolean ready;

    public boolean follow(String address) {
        if (Objects.equals(device, address)) return false;
        device = address;
        attempts = 0;
        retryAt = 0;
        busy = false;
        ready = false;
        return true;
    }

    public boolean begin(long now) {
        if (device == null || busy || ready || attempts >= 2 || now < retryAt) return false;
        attempts++;
        busy = true;
        return true;
    }

    public void failed(long now) {
        busy = false;
        ready = false;
        retryAt = now + 10000;
    }

    public void ready() {
        busy = false;
        ready = true;
        attempts = 0;
    }

    public int attempts() { return attempts; }
}
