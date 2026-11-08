package io.github.pods.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AirPodsProtocol {
    public static final int LISTENING_MODE = 0x0D;
    public static final int ALLOW_OFF = 0x34;
    public static final int LEFT = 4;
    public static final int RIGHT = 2;
    public static final int CASE = 8;
    private static final byte[] HEADER = {4, 0, 4, 0};

    private AirPodsProtocol() {}

    public static byte[] handshake() {
        return new byte[]{0, 0, 4, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    }

    public static byte[] features() {
        return new byte[]{4, 0, 4, 0, 0x4D, 0, (byte) 0xD7, 0, 0, 0, 0, 0, 0, 0};
    }

    public static byte[] notifications() {
        return new byte[]{4, 0, 4, 0, 0x0F, 0, -1, -1, -1, -1};
    }

    public static byte[] requestIdentityKey() {
        return new byte[]{4, 0, 4, 0, 0x30, 0, 1, 0};
    }

    public static byte[] identityKey(byte[] frame) {
        if (!isFrame(frame, 0x31) || keyFrameSize(frame, 0) != frame.length) return null;
        int offset = 7;
        for (int i = 0; i < (frame[6] & 255); i++) {
            int length = (frame[offset + 2] & 255) | ((frame[offset + 3] & 255) << 8);
            if (frame[offset] == 1 && length == 16) return Arrays.copyOfRange(frame, offset + 4, offset + 20);
            offset += 4 + length;
        }
        return null;
    }

    // 返回零表示数据未收齐，负数表示格式无效，避免把密钥内容当成报文头。
    private static int keyFrameSize(byte[] bytes, int offset) {
        if (offset + 7 > bytes.length) return 0;
        int count = bytes[offset + 6] & 255;
        if (count < 1 || count > 8) return -1;
        int cursor = offset + 7;
        for (int i = 0; i < count; i++) {
            if (cursor + 4 > bytes.length) return 0;
            int length = (bytes[cursor + 2] & 255) | ((bytes[cursor + 3] & 255) << 8);
            if (length < 1 || length > 64) return -1;
            cursor += 4 + length;
            if (cursor > bytes.length) return 0;
        }
        return cursor - offset;
    }

    public static byte[] control(int identifier, int value) {
        if (identifier < 0 || identifier > 255 || value < 0 || value > 255) {
            throw new IllegalArgumentException("Control value is out of range");
        }
        return new byte[]{4, 0, 4, 0, 9, 0, (byte) identifier, (byte) value, 0, 0, 0};
    }

    public static final class Battery {
        public final int component;
        public final Integer level;
        public final boolean charging;

        public Battery(int component, Integer level, boolean charging) {
            this.component = component;
            this.level = level;
            this.charging = charging;
        }
    }

    public static List<Battery> batteries(byte[] frame) {
        List<Battery> result = new ArrayList<>();
        if (!isFrame(frame, 4) || frame.length < 7) return result;
        int count = frame[6] & 0xFF;
        if (count < 1 || count > 8 || frame.length != 7 + count * 5) return result;
        for (int i = 0; i < count; i++) {
            int start = 7 + i * 5;
            int component = frame[start] & 0xFF;
            int value = frame[start + 2] & 0xFF;
            int status = frame[start + 3] & 0xFF;
            if (component != LEFT && component != RIGHT && component != CASE) continue;
            Integer level = value <= 100 && status != 4 ? value : null;
            result.add(new Battery(component, level, status == 1 || status == 5));
        }
        return result;
    }

    public static boolean isFrame(byte[] frame, int opcode) {
        return frame != null && frame.length >= 6 && headerAt(frame, 0)
                && (frame[4] & 0xFF) == opcode && frame[5] == 0;
    }

    private static boolean headerAt(byte[] bytes, int offset) {
        if (offset + 4 > bytes.length) return false;
        for (int i = 0; i < HEADER.length; i++) {
            if (bytes[offset + i] != HEADER[i]) return false;
        }
        return true;
    }

    // 蓝牙读取可能拆分或合并报文；未知指令不作为已知状态处理。
    public static final class Decoder {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public List<byte[]> feed(byte[] bytes) {
            buffer.write(bytes, 0, bytes.length);
            byte[] data = buffer.toByteArray();
            List<byte[]> result = new ArrayList<>();
            int cursor = 0;
            while (cursor + 6 <= data.length) {
                if (!headerAt(data, cursor) || data[cursor + 5] != 0) {
                    cursor++;
                    continue;
                }
                int opcode = data[cursor + 4] & 0xFF;
                int size;
                if (opcode == 9) size = 11;
                else if (opcode == 6) size = 8;
                else if (opcode == 4) {
                    if (cursor + 7 > data.length) break;
                    int count = data[cursor + 6] & 0xFF;
                    if (count < 1 || count > 8) { cursor++; continue; }
                    size = 7 + count * 5;
                } else if (opcode == 0x31) {
                    size = keyFrameSize(data, cursor);
                    if (size == 0) break;
                    if (size < 0) { cursor++; continue; }
                } else {
                    cursor++;
                    continue;
                }
                if (cursor + size > data.length) break;
                result.add(Arrays.copyOfRange(data, cursor, cursor + size));
                cursor += size;
            }
            buffer.reset();
            buffer.write(data, cursor, data.length - cursor);
            return result;
        }
    }
}
