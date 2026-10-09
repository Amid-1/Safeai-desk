package ru.safeai.gateway.audit.details;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Security audit details for refresh-token reuse.
 *
 * <p>User-Agent is attacker-controlled text. The raw header is therefore not
 * persisted in immutable audit evidence. A SHA-256 fingerprint is enough for
 * correlation without copying arbitrary header content into the audit log.</p>
 */
public record SecurityRefreshReuseAuditDetails(
        UUID tokenFamilyId,
        String ip,
        String userAgent,
        String requestId
) implements AuditDetails {

    public SecurityRefreshReuseAuditDetails {
        Objects.requireNonNull(
                tokenFamilyId,
                "tokenFamilyId не должен быть null"
        );

        ip = normalize(ip);
        userAgent = normalize(userAgent);
        requestId = normalize(requestId);
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put(
                "tokenFamilyId",
                tokenFamilyId
        );

        put(
                result,
                "ip",
                ip
        );

        if (userAgent != null) {
            result.put(
                    "userAgentSha256",
                    sha256(userAgent)
            );
        }

        put(
                result,
                "requestId",
                requestId
        );

        return Map.copyOf(result);
    }

    private static String sha256(
            String value
    ) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest
                            .getInstance("SHA-256")
                            .digest(
                                    value.getBytes(
                                            StandardCharsets.UTF_8
                                    )
                            )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 недоступен",
                    exception
            );
        }
    }

    private static void put(
            Map<String, Object> target,
            String key,
            Object value
    ) {
        if (value != null) {
            target.put(
                    key,
                    value
            );
        }
    }

    private static String normalize(
            String value
    ) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}
