package com.ubp.rgd.proxy.rps;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;

public final class HashUtils {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private HashUtils() {
        // Classe utilitaire
    }

    public static String md5(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "La chaÃ®ne Ã  hacher ne peut pas Ãªtre null."
            );
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("MD5", "BC");
            byte[] hash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return toHex(hash);

        } catch (NoSuchAlgorithmException | java.security.NoSuchProviderException e) {
            throw new IllegalStateException(
                    "Impossible de calculer le hash MD5 avec BouncyCastle.",
                    e
            );
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);

        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }

        return result.toString();
    }
}

