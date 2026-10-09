package ru.safeai.gateway.audit.model;

import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.validation.UnicodeValidationSupport;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record AuditActor(
        UUID userId,
        UUID organizationId,
        String email,
        String displayName
) {
    private static final int MAX_IDENTITY_CODE_POINTS =
            255;

    public AuditActor {
        email =
                normalizeEmail(
                        email
                );

        displayName =
                normalizeIdentity(
                        displayName,
                        "displayName"
                );

        if (userId != null) {
            Objects.requireNonNull(
                    organizationId,
                    "organizationId actor-а не должен быть null"
            );
        }

        validateLength(
                email,
                "email"
        );

        validateLength(
                displayName,
                "displayName"
        );
    }

    public static AuditActor fromPrincipal(
            SafeAiUserPrincipal principal
    ) {
        return fromPrincipal(
                principal,
                null
        );
    }

    public static AuditActor fromPrincipal(
            SafeAiUserPrincipal principal,
            String displayName
    ) {
        Objects.requireNonNull(
                principal,
                "principal не должен быть null"
        );

        return new AuditActor(
                principal.getId(),
                principal.getOrganizationId(),
                principal.getEmail(),
                displayName
        );
    }

    public static AuditActor system() {
        return new AuditActor(
                null,
                null,
                null,
                "SYSTEM"
        );
    }

    private static String normalizeEmail(
            String value
    ) {
        String normalized =
                normalizeIdentity(
                        value,
                        "email"
                );

        return normalized == null
                ? null
                : normalized.toLowerCase(
                        Locale.ROOT
                );
    }

    /**
     * Identity snapshots intentionally use NFC, not NFKC.
     *
     * <p>NFC preserves compatibility distinctions that should not be folded in
     * immutable actor identity evidence. UTF-16 well-formedness is shared via
     * {@link UnicodeValidationSupport}; normalization remains local to this
     * domain contract.</p>
     */
    private static String normalizeIdentity(
            String value,
            String field
    ) {
        if (value == null
                || value.isBlank()) {
            return null;
        }

        UnicodeValidationSupport
                .requireWellFormedUtf16(
                        value,
                        field
                );

        String normalized =
                Normalizer.normalize(
                                value,
                                Normalizer.Form.NFC
                        )
                        .trim();

        if (normalized.isBlank()) {
            return null;
        }

        for (int offset = 0;
             offset < normalized.length();) {

            int codePoint =
                    normalized.codePointAt(
                            offset
                    );

            if (Character.isISOControl(
                    codePoint
            )) {
                throw new IllegalArgumentException(
                        field
                                + " содержит управляющие символы"
                );
            }

            offset +=
                    Character.charCount(
                            codePoint
                    );
        }

        return normalized;
    }

    private static void validateLength(
            String value,
            String field
    ) {
        if (value == null) {
            return;
        }

        int length =
                value.codePointCount(
                        0,
                        value.length()
                );

        if (length
                > MAX_IDENTITY_CODE_POINTS) {
            throw new IllegalArgumentException(
                    field
                            + " должен быть не длиннее "
                            + MAX_IDENTITY_CODE_POINTS
                            + " символов"
            );
        }
    }
}
