package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.util.Objects;

/**
 * Shared request-metadata normalization for auth audit events.
 *
 * <p>This class deliberately contains no audit durability policy. It only
 * extracts bounded metadata from the current HTTP request so best-effort and
 * required audit paths use identical normalization rules.</p>
 */
final class AuthAuditRequestSupport {

    private static final int MAX_IP_LENGTH =
            128;

    private static final int MAX_USER_AGENT_LENGTH =
            512;

    private static final int MAX_REQUEST_ID_LENGTH =
            128;

    private static final String UNKNOWN_VALUE =
            "unknown";

    private AuthAuditRequestSupport() {
    }

    static String ipOrUnknown(
            ClientIpResolver clientIpResolver,
            HttpServletRequest request
    ) {
        Objects.requireNonNull(
                clientIpResolver,
                "clientIpResolver не должен быть null"
        );

        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        return truncateOrUnknown(
                clientIpResolver.resolve(
                        request
                ),
                MAX_IP_LENGTH
        );
    }

    static String userAgentOrUnknown(
            HttpServletRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        return truncateOrUnknown(
                request.getHeader(
                        "User-Agent"
                ),
                MAX_USER_AGENT_LENGTH
        );
    }

    /**
     * Returns only the server-generated request id.
     *
     * <p>The client-controlled X-Request-Id is intentionally not used as a
     * replacement for the server correlation id.</p>
     */
    static String requestIdOrUnknown(
            HttpServletRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Object attribute =
                request.getAttribute(
                        RequestIdFilter
                                .REQUEST_ID_ATTRIBUTE
                );

        if (attribute
                instanceof String value
                && !value.isBlank()) {

            return truncateOrUnknown(
                    value,
                    MAX_REQUEST_ID_LENGTH
            );
        }

        return UNKNOWN_VALUE;
    }

    static @Nullable String clientRequestIdOrNull(
            HttpServletRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Object attribute =
                request.getAttribute(
                        RequestIdFilter
                                .CLIENT_REQUEST_ID_ATTRIBUTE
                );

        if (!(attribute
                instanceof String value)
                || value.isBlank()) {

            return null;
        }

        return truncateNullable(
                value,
                MAX_REQUEST_ID_LENGTH
        );
    }

    static String truncateOrUnknown(
            @Nullable String value,
            int maxCodePoints
    ) {
        String normalized =
                truncateNullable(
                        value,
                        maxCodePoints
                );

        return normalized == null
                ? UNKNOWN_VALUE
                : normalized;
    }

    static @Nullable String truncateNullable(
            @Nullable String value,
            int maxCodePoints
    ) {
        if (value == null
                || value.isBlank()) {

            return null;
        }

        if (maxCodePoints <= 0) {
            throw new IllegalArgumentException(
                    "maxCodePoints должен быть положительным"
            );
        }

        String trimmed =
                value.trim();

        int codePointCount =
                trimmed.codePointCount(
                        0,
                        trimmed.length()
                );

        if (codePointCount
                <= maxCodePoints) {
            return trimmed;
        }

        int end =
                trimmed.offsetByCodePoints(
                        0,
                        maxCodePoints
                );

        return trimmed.substring(
                0,
                end
        );
    }
}
