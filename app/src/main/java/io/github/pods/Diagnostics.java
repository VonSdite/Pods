package io.github.pods;

import android.os.Build;
import android.util.Log;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;

public final class Diagnostics {
    private static final ArrayDeque<String> ENTRIES = new ArrayDeque<>();

    private Diagnostics() {}

    public static synchronized void record(String event) {
        String safe = event.replaceAll("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}", "[redacted]");
        Log.i("Pods", safe);
        ENTRIES.addLast(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + safe);
        while (ENTRIES.size() > 80) ENTRIES.removeFirst();
    }

    public static synchronized String report(String state) {
        return "Pods 0.1.6\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                + "\nState: " + state + "\n\n" + String.join("\n", ENTRIES)
                + "\n\nNo Bluetooth addresses, serial numbers or pairing keys are included.\n";
    }
}
