package ru.safeai.gateway.audit.service;

import ru.safeai.gateway.audit.dto.AuditIntegrityStatus;
import ru.safeai.gateway.audit.dto.AuditIntegrityStatus.ArchiveStatus;
import ru.safeai.gateway.audit.dto.AuditIntegrityStatus.State;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Status file is an untrusted input until its independent
 * Ed25519 signature is verified.
 */
final class AuditIntegrityReport {

    private static final int MAX_BYTES = 4096;
    private static final long FRESH_SECONDS = 900;
    private static final long MAX_COUNT = 10_000_000;
    private static final String KEY_PREFIX = "302a300506032b6570032100";

    private AuditIntegrityReport() {
    }

    static AuditIntegrityStatus read(
            String path,
            String dataset,
            String key,
            Instant now
    ) {
        if (path.isBlank() && dataset.isBlank() && key.isBlank()) {
            return AuditIntegrityStatus.unknown(
                    State.NOT_CONFIGURED, null
            );
        }

        try {
            if (path.isBlank()
                    || !UUID.fromString(dataset).toString().equals(dataset)) {
                return AuditIntegrityStatus.unknown(State.INVALID, null);
            }

            byte[] der = decode(key, 44);

            if (!HexFormat.of().formatHex(der).startsWith(KEY_PREFIX)) {
                return AuditIntegrityStatus.unknown(State.INVALID, null);
            }

            Path file = Path.of(path);

            BasicFileAttributes attributes = Files.readAttributes(
                    file,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );

            if (!attributes.isRegularFile()) {
                return AuditIntegrityStatus.unknown(State.INVALID, null);
            }

            byte[] bytes;

            try (var input = Files.newInputStream(
                    file,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }

            if (bytes.length > MAX_BYTES) {
                return AuditIntegrityStatus.unknown(State.INVALID, null);
            }

            return parse(bytes, dataset, der, now);

        } catch (IOException e) {
            return AuditIntegrityStatus.unknown(State.UNAVAILABLE, null);

        } catch (GeneralSecurityException | RuntimeException e) {
            return AuditIntegrityStatus.unknown(State.INVALID, null);
        }
    }

    private static AuditIntegrityStatus parse(
            byte[] bytes,
            String dataset,
            byte[] der,
            Instant now
    ) throws GeneralSecurityException {

        for (byte value : bytes) {
            if (value < 0) {
                return AuditIntegrityStatus.unknown(State.INVALID, null);
            }
        }

        String[] fields = new String(
                bytes,
                StandardCharsets.US_ASCII
        ).split("\n", -1);

        if (fields.length != 10
                || !fields[9].isEmpty()
                || !fields[0].equals("safeai-audit-status-v1")
                || !fields[1].equals(dataset)) {
            return AuditIntegrityStatus.unknown(State.INVALID, null);
        }

        Signature verifier = Signature.getInstance("Ed25519");

        verifier.initVerify(
                KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(der))
        );

        verifier.update(
                bytes,
                0,
                bytes.length - fields[8].length() - 1
        );

        if (!verifier.verify(decode(fields[8], 64))) {
            return AuditIntegrityStatus.unknown(State.INVALID, null);
        }

        if (!fields[2].matches("[1-9][0-9]{0,11}")) {
            return AuditIntegrityStatus.unknown(State.INVALID, null);
        }

        Instant checkedAt = Instant.ofEpochSecond(
                Long.parseLong(fields[2])
        );

        if (checkedAt.isAfter(now)) {
            return AuditIntegrityStatus.unknown(State.INVALID, null);
        }

        Instant expires = checkedAt.plusSeconds(FRESH_SECONDS);

        State state = State.valueOf(fields[3]);

        if (state == State.UNAVAILABLE) {
            if (!fields[4].equals("-1")
                    || !fields[5].equals("-1")
                    || !fields[6].equals("-1")
                    || !fields[7].equals("UNKNOWN")) {
                return AuditIntegrityStatus.unknown(State.INVALID, null);
            }

            if (!now.isBefore(expires)) {
                return AuditIntegrityStatus.unknown(State.STALE, checkedAt);
            }

            return AuditIntegrityStatus.unknown(
                    State.UNAVAILABLE,
                    checkedAt
            );
        }

        long checked = count(fields[4]);
        long violations = count(fields[5]);
        long pending = count(fields[6]);

        State expected = violations > 0
                ? State.VIOLATION
                : pending > 0
                        ? State.PENDING
                        : State.VERIFIED;

        if (state != expected
                || violations > checked + pending
                || !fields[7].equals("AVAILABLE")) {
            return AuditIntegrityStatus.unknown(State.INVALID, null);
        }

        if (!now.isBefore(expires)) {
            return AuditIntegrityStatus.unknown(State.STALE, checkedAt);
        }

        return new AuditIntegrityStatus(
                state,
                checked,
                violations,
                pending,
                checkedAt,
                expires,
                ArchiveStatus.AVAILABLE
        );
    }

    private static long count(String field) {
        if (!field.matches("0|[1-9][0-9]{0,7}")) {
            throw new IllegalArgumentException("invalid count");
        }

        long value = Long.parseLong(field);

        if (value > MAX_COUNT) {
            throw new IllegalArgumentException("count exceeds bound");
        }

        return value;
    }

    private static byte[] decode(String field, int size) {
        byte[] bytes = Base64.getDecoder().decode(field);

        if (bytes.length != size
                || !Base64.getEncoder().encodeToString(bytes).equals(field)) {
            throw new IllegalArgumentException("invalid encoding");
        }

        return bytes;
    }
}
