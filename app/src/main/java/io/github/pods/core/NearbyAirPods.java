package io.github.pods.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 无密钥时观察附近广播，不将广播相似性当作音频设备的身份凭据。 */
public final class NearbyAirPods {
    private static final long MAX_AGE_MS = 20000;
    private static final long SETTLE_MS = 3000;
    private static final long CASE_CONTEXT_MS = 2000;
    private static final int MIN_RSSI = -80;
    private static final int MAX_ADDRESSES = 32;

    public enum State { NONE, WAITING, SINGLE, AMBIGUOUS }

    public static final class Selection {
        public final State state;
        public final int candidates;
        public final Sample sample;

        private Selection(State state, int candidates, Sample sample) {
            this.state = state;
            this.candidates = candidates;
            this.sample = sample;
        }
    }

    public static final class Sample {
        public final AirPodsAdvertisement data;
        public final long receivedAt;

        private Sample(AirPodsAdvertisement data, long receivedAt) {
            this.data = data;
            this.receivedAt = receivedAt;
        }
    }

    private final Map<String, Sample> samples = new LinkedHashMap<>();
    private Signature singleSignature;
    private long singleSince;

    public void observe(String address, AirPodsAdvertisement data, int rssi, long receivedAt) {
        if (address == null || data == null || !data.knownModel || rssi < MIN_RSSI || rssi > 0) return;
        samples.remove(address);
        samples.put(address, new Sample(data, receivedAt));
        if (samples.size() > MAX_ADDRESSES) samples.remove(samples.keySet().iterator().next());
    }

    public Selection select(long now) {
        Map<Signature, Sample> candidates = new HashMap<>();
        Iterator<Sample> iterator = samples.values().iterator();
        while (iterator.hasNext()) {
            Sample sample = iterator.next();
            if (now - sample.receivedAt > MAX_AGE_MS || sample.receivedAt > now) {
                iterator.remove();
                continue;
            }
            Signature signature = new Signature(sample.data);
            Sample existing = candidates.get(signature);
            candidates.put(signature, existing == null ? sample : preferred(existing, sample));
        }
        if (candidates.size() != 1) {
            singleSignature = null;
            return new Selection(candidates.isEmpty() ? State.NONE : State.AMBIGUOUS, candidates.size(), null);
        }
        Map.Entry<Signature, Sample> candidate = candidates.entrySet().iterator().next();
        if (!candidate.getKey().equals(singleSignature)) {
            singleSignature = candidate.getKey();
            singleSince = now;
        }
        if (now - singleSince < SETTLE_MS) return new Selection(State.WAITING, 1, null);
        return new Selection(State.SINGLE, 1, candidate.getValue());
    }

    public void reset() {
        samples.clear();
        singleSignature = null;
        singleSince = 0;
    }

    private Sample preferred(Sample first, Sample second) {
        Sample newer = first.receivedAt >= second.receivedAt ? first : second;
        Sample older = newer == first ? second : first;
        // 只在短时间内优先使用盒内广播，避免旧盒盖状态覆盖刚取出的耳机。
        if (newer.data.lidOpen == null && older.data.lidOpen != null
                && newer.receivedAt - older.receivedAt <= CASE_CONTEXT_MS) return older;
        return newer;
    }

    /** 左右耳可能同时使用不同随机地址；按已归一化的公开字段归组。 */
    private static final class Signature {
        private final int model;
        private final int color;
        private final Integer left;
        private final Integer right;

        private Signature(AirPodsAdvertisement data) {
            model = data.modelId;
            color = data.color;
            left = data.leftBattery;
            right = data.rightBattery;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Signature)) return false;
            Signature signature = (Signature) other;
            return model == signature.model && color == signature.color
                    && Objects.equals(left, signature.left) && Objects.equals(right, signature.right);
        }

        @Override public int hashCode() { return Objects.hash(model, color, left, right); }
    }
}
