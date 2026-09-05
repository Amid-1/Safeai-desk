package ru.safeai.gateway.common.security;

import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JWT configuration for asymmetric RS256 signing and verification.
 *
 * <p>The active key must contain both public and private key material.
 * Previous keys may contain public material only and remain in the key ring
 * only while already-issued access tokens can still be valid.</p>
 */
@NullUnmarked
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        long expirationMinutes,
        String issuer,
        String audience,
        String activeKeyId,
        List<KeyEntry> keys
) {

    private static final long MAX_EXPIRATION_MINUTES = 60;

    private static final Pattern KEY_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /**
     * Explicit canonical constructor keeps validation deterministic and avoids
     * IDE warnings caused by parameter reassignment in a compact constructor.
     */
    public JwtProperties(
            long expirationMinutes,
            String issuer,
            String audience,
            String activeKeyId,
            List<KeyEntry> keys
    ) {
        if (expirationMinutes < 1L
                || expirationMinutes > MAX_EXPIRATION_MINUTES) {
            throw new IllegalStateException(
                    "JWT expirationMinutes должен быть в диапазоне 1–60"
            );
        }

        String normalizedIssuer =
                validateIssuer(
                        requireText(
                                issuer,
                                "JWT issuer не задан. Укажите app.security.jwt.issuer"
                        )
                );

        String normalizedAudience =
                requireText(
                        audience,
                        "JWT audience не задан. Укажите app.security.jwt.audience"
                );

        if (normalizedAudience.length() > 255) {
            throw new IllegalStateException(
                    "JWT audience не должен превышать 255 символов"
            );
        }

        String normalizedActiveKeyId =
                requireKeyId(
                        activeKeyId,
                        "app.security.jwt.active-key-id"
                );

        List<KeyEntry> normalizedKeys =
                normalizeKeys(
                        keys
                );

        KeyEntry activeKey =
                normalizedKeys.stream()
                        .filter(
                                key ->
                                        key.id().equals(
                                                normalizedActiveKeyId
                                        )
                        )
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Активный JWT key id не найден "
                                                        + "в app.security.jwt.keys: "
                                                        + normalizedActiveKeyId
                                        )
                        );

        if (activeKey.privateKey() == null
                || activeKey.privateKey().isBlank()) {
            throw new IllegalStateException(
                    "Активный JWT key должен содержать private-key: "
                            + normalizedActiveKeyId
            );
        }

        this.expirationMinutes =
                expirationMinutes;

        this.issuer =
                normalizedIssuer;

        this.audience =
                normalizedAudience;

        this.activeKeyId =
                normalizedActiveKeyId;

        this.keys =
                normalizedKeys;
    }

    /**
     * RSA key material must never be exposed through the record-generated
     * toString().
     */
    @Override
    public String toString() {
        return "JwtProperties["
                + "expirationMinutes="
                + expirationMinutes
                + ", issuer="
                + issuer
                + ", audience="
                + audience
                + ", activeKeyId="
                + activeKeyId
                + ", keys=<redacted:"
                + keys.size()
                + ">"
                + "]";
    }

    private static List<KeyEntry> normalizeKeys(
            List<KeyEntry> source
    ) {
        if (source == null
                || source.isEmpty()) {
            throw new IllegalStateException(
                    "app.security.jwt.keys должен содержать хотя бы один RSA key"
            );
        }

        List<KeyEntry> result =
                new ArrayList<>(
                        source.size()
                );

        Set<String> ids =
                new HashSet<>();

        for (KeyEntry key : source) {
            if (key == null) {
                throw new IllegalStateException(
                        "app.security.jwt.keys не должен содержать null"
                );
            }

            if (!ids.add(
                    key.id()
            )) {
                throw new IllegalStateException(
                        "Повторяющийся JWT key id: "
                                + key.id()
                );
            }

            result.add(
                    key
            );
        }

        return List.copyOf(
                result
        );
    }

    private static String validateIssuer(
            String value
    ) {
        try {
            URI uri =
                    new URI(
                            value
                    );

            String scheme =
                    uri.getScheme() == null
                            ? ""
                            : uri.getScheme()
                                    .toLowerCase(
                                            Locale.ROOT
                                    );

            if (!("http".equals(
                    scheme
            ) || "https".equals(
                    scheme
            ))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalStateException(
                        "JWT issuer должен быть абсолютным HTTP(S) URI "
                                + "без query/fragment/userInfo"
                );
            }

            return value;

        } catch (URISyntaxException exception) {
            throw new IllegalStateException(
                    "JWT issuer имеет некорректный URI",
                    exception
            );
        }
    }

    private static String requireKeyId(
            String value,
            String propertyName
    ) {
        String normalized =
                requireText(
                        value,
                        propertyName
                                + " не задан"
                );

        if (!KEY_ID_PATTERN
                .matcher(
                        normalized
                )
                .matches()) {
            throw new IllegalStateException(
                    propertyName
                            + " должен соответствовать "
                            + "[A-Za-z0-9._-]{1,64}"
            );
        }

        return normalized;
    }

    private static String requireText(
            String value,
            String message
    ) {
        if (value == null
                || value.isBlank()) {
            throw new IllegalStateException(
                    message
            );
        }

        String normalized =
                value.trim();

        if (!normalized.equals(
                value
        )) {
            throw new IllegalStateException(
                    message
                            + ": значение содержит внешние пробелы"
            );
        }

        return normalized;
    }

    public record KeyEntry(
            String id,
            String publicKey,
            @Nullable String privateKey
    ) {

        /**
         * Explicit canonical constructor avoids parameter-reassignment
         * inspections and keeps nullable private-key normalization explicit.
         */
        public KeyEntry(
                String id,
                String publicKey,
                @Nullable String privateKey
        ) {
            String normalizedId =
                    requireKeyId(
                            id,
                            "app.security.jwt.keys[].id"
                    );

            String normalizedPublicKey =
                    requireText(
                            publicKey,
                            "app.security.jwt.keys["
                                    + normalizedId
                                    + "].public-key не задан"
                    );

            String normalizedPrivateKey =
                    null;

            if (privateKey != null) {
                String candidate =
                        privateKey.trim();

                if (!candidate.isEmpty()) {
                    normalizedPrivateKey =
                            candidate;
                }
            }

            this.id =
                    normalizedId;

            this.publicKey =
                    normalizedPublicKey;

            this.privateKey =
                    normalizedPrivateKey;
        }

        /**
         * Both public and private RSA material are deliberately redacted.
         *
         * <p>The public key is not a secret cryptographically, but there is no
         * operational benefit in dumping complete PEM material into logs.</p>
         */
        @Override
        public String toString() {
            return "KeyEntry["
                    + "id="
                    + id
                    + ", publicKey=<redacted>"
                    + ", privateKey=<redacted>"
                    + "]";
        }
    }
}