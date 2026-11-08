package io.github.pods.core;

public final class AirPodsAdvertisement {
    public final int modelId;
    public final String model;
    public final boolean knownModel;
    public final int color;
    public final Integer leftBattery;
    public final Integer rightBattery;
    public final Integer caseBattery;
    public final boolean leftCharging;
    public final boolean rightCharging;
    public final boolean caseCharging;
    public final boolean leftInEar;
    public final boolean rightInEar;
    public final Boolean leftMicrophone;
    public final Boolean rightMicrophone;
    public final Boolean lidOpen;
    public final int connectionState;

    private AirPodsAdvertisement(byte[] data) {
        modelId = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
        String name = modelName(modelId);
        knownModel = name != null;
        model = knownModel ? name : "AirPods · 型号待确认";
        color = data[9] & 255;
        int status = data[5] & 0xFF;
        int pods = data[6] & 0xFF;
        int flags = (data[7] & 0xFF) >> 4;
        boolean primaryLeft = (status & 0x20) != 0;
        boolean primaryInCase = (status & 0x40) != 0;
        boolean flipEar = !primaryLeft ^ primaryInCase;
        leftBattery = decodeBattery(primaryLeft ? pods & 15 : pods >> 4);
        rightBattery = decodeBattery(primaryLeft ? pods >> 4 : pods & 15);
        caseBattery = decodeBattery(data[7] & 15);
        leftCharging = (flags & (primaryLeft ? 1 : 2)) != 0;
        rightCharging = (flags & (primaryLeft ? 2 : 1)) != 0;
        caseCharging = (flags & 4) != 0;
        // 入盒过渡广播可能保留旧入耳位；明确充电或双耳入盒时不显示佩戴。
        boolean bothInCase = (status & 4) != 0;
        leftInEar = !bothInCase && !leftCharging && (status & (flipEar ? 8 : 2)) != 0;
        rightInEar = !bothInCase && !rightCharging && (status & (flipEar ? 2 : 8)) != 0;
        // 主麦克风所在侧沿用公开状态位；充电或双耳入盒时不推断麦克风角色。
        boolean microphoneLeft = primaryLeft ^ primaryInCase;
        leftMicrophone = bothInCase || leftCharging ? null : microphoneLeft;
        rightMicrophone = bothInCase || rightCharging ? null : !microphoneLeft;
        // 盒外耳机可能沿用旧盒盖字段，只接受盒内耳机或双耳入盒的广播。
        lidOpen = primaryInCase || (status & 4) != 0 ? (data[8] & 8) == 0 : null;
        connectionState = data[10] & 255;
    }

    public static AirPodsAdvertisement parse(byte[] data) {
        if (data == null) return null;
        int offset = 0;
        while (offset + 2 <= data.length) {
            int length = data[offset + 1] & 255;
            if (length == 0 || offset + 2 + length > data.length) return null;
            if (data[offset] == 7 && length == 25 && data[offset + 2] == 1) {
                return new AirPodsAdvertisement(java.util.Arrays.copyOfRange(data, offset, offset + 27));
            }
            offset += 2 + length;
        }
        return null;
    }

    public boolean supportsAdaptive() {
        return modelId == 0x1420 || modelId == 0x2420 || modelId == 0x1B20 || modelId == 0x2720;
    }

    private static Integer decodeBattery(int nibble) {
        return nibble == 15 ? null : Math.min(100, nibble * 10);
    }

    private static String modelName(int modelId) {
        return switch (modelId) {
            case 0x0220 -> "AirPods";
            case 0x0F20 -> "AirPods 2";
            case 0x1320 -> "AirPods 3";
            case 0x1920 -> "AirPods 4";
            case 0x1B20 -> "AirPods 4 · 降噪款";
            case 0x3620 -> "AirPods 5";
            case 0x3020 -> "AirPods 5 · 无线充电款";
            case 0x0E20 -> "AirPods Pro";
            case 0x1420 -> "AirPods Pro 2";
            case 0x2420 -> "AirPods Pro 2 · USB-C";
            case 0x2720 -> "AirPods Pro 3";
            case 0x0A20 -> "AirPods Max";
            case 0x1F20 -> "AirPods Max · USB-C";
            default -> null;
        };
    }
}
