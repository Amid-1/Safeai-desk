package ru.safeai.gateway.ai.provider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Operator-attested terms, scoped to one immutable physical connection.
 * Never infer contractual assurances from a provider/model name or store=false.
 */
public record ProviderDataEvidence(
        String source, Instant verifiedAt, Instant expiresAt, String connectionSha256,
        boolean noTraining, boolean zeroDataRetention
) {
    public ProviderDataEvidence {
        if (source == null || !source.matches("(?:CONTRACT|DPA|DOC|TICKET)-[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
                || verifiedAt == null || expiresAt == null
                || !expiresAt.isAfter(verifiedAt)
                || Duration.between(verifiedAt, expiresAt).compareTo(Duration.ofDays(90)) > 0
                || connectionSha256 == null || !connectionSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid provider data evidence");
        }
    }

    public Status evaluate(Instant now, String connection, boolean retentionCompatible) {
        if (!MessageDigest.isEqual(connectionSha256.getBytes(StandardCharsets.US_ASCII),
                    connection.getBytes(StandardCharsets.US_ASCII))) {
            return Status.UNKNOWN;
        }
        if (now.isBefore(verifiedAt) || !now.isBefore(expiresAt)) {
            return new Status(false, false, source, verifiedAt, expiresAt);
        }
        return new Status(noTraining, zeroDataRetention && retentionCompatible,
                source, verifiedAt, expiresAt);
    }

    @Override
    public String toString() {
        return "ProviderDataEvidence[connection=REDACTED]";
    }

    /** Domain-separated, length-prefixed binding; credentials are never returned or logged. */
    public static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = java.util.Objects.requireNonNull(part).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Safe public projection. No connection binding, keys or endpoint. */
    public record Status(boolean noTraining, boolean zeroDataRetention, String source,
                         Instant verifiedAt, Instant expiresAt) {
        public static final Status UNKNOWN = new Status(false, false, null, null, null);
        public static final Status LOCAL_MOCK = new Status(true, true, "LOCAL_MOCK", null, null);
    }
}
