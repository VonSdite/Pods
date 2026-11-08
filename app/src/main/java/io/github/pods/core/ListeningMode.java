package io.github.pods.core;

public enum ListeningMode {
    OFF(1, "关闭"),
    NOISE_CANCELLATION(2, "降噪"),
    TRANSPARENCY(3, "通透"),
    ADAPTIVE(4, "自适应");

    public final int value;
    public final String label;

    ListeningMode(int value, String label) {
        this.value = value;
        this.label = label;
    }

    public static ListeningMode fromValue(int value) {
        for (ListeningMode mode : values()) {
            if (mode.value == value) return mode;
        }
        return null;
    }
}
