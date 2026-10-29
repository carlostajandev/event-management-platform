package com.nequi.ticketing.infrastructure.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over the request fields that define the operation. Stored with the idempotency key so a retry can be
 * told apart from the same key reused for a different request.
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(Object... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object field : fields) {
                digest.update(String.valueOf(field).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
