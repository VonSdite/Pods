package io.github.pods.core;

import java.security.GeneralSecurityException;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public final class AirPodsIdentity {
    public static final String SERVICE_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a";

    private AirPodsIdentity() {}

    public static boolean recognizes(String name, String[] services) {
        if (services != null) {
            for (String service : services) if (SERVICE_UUID.equalsIgnoreCase(service)) return true;
        }
        return name != null && name.toLowerCase(Locale.ROOT).contains("airpods");
    }

    public static boolean matches(String connectedAddress, String broadcastAddress, byte[] identityKey) {
        if (connectedAddress == null || broadcastAddress == null) return false;
        if (connectedAddress.equalsIgnoreCase(broadcastAddress)) return true;
        return resolves(broadcastAddress, identityKey);
    }

    // 使用蓝牙规范的 ah 函数验证随机地址；密钥来自当前耳机，不按距离猜测归属。
    @SuppressWarnings("GetInstance")
    public static boolean resolves(String address, byte[] identityKey) {
        if (identityKey == null || identityKey.length != 16 || address == null
                || !address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) return false;
        String[] parts = address.split(":");
        byte[] bytes = new byte[6];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        if ((bytes[0] & 0xC0) != 0x40) return false;
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) key[i] = identityKey[key.length - 1 - i];
        byte[] input = new byte[16];
        System.arraycopy(bytes, 0, input, 13, 3);
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            byte[] output = cipher.doFinal(input);
            return output[13] == bytes[3] && output[14] == bytes[4] && output[15] == bytes[5];
        } catch (GeneralSecurityException error) {
            return false;
        }
    }
}
