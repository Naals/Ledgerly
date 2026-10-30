package com.project.paymentservice.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** A stable SHA-256 fingerprint of the meaningful parts of a request. */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    /** Hex SHA-256 of the parts, each length-prefixed so ("ab", "c") differs from ("a", "bc"). */
    public static String of(String... parts) {
        MessageDigest digest = sha256();
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}