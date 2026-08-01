package ru.safeai.gateway.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Component
@EnableConfigurationProperties(
        RateLimitRedisKeyProperties.class
)
public class RateLimitKeyFactory {

    private static final String HMAC_ALGORITHM =
            "HmacSHA256";

    private static final String LOGIN_HASH_TAG =
            "{login}";

    private static final int ORGANIZATION_TAG_LENGTH = 32;

    private final RateLimitRedisKeyProperties properties;
    private final SecretKeySpec hmacKey;

    public RateLimitKeyFactory(
            RateLimitRedisKeyProperties properties
    ) {
        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );

        this.hmacKey = new SecretKeySpec(
                properties.hmacSecret()
                        .getBytes(StandardCharsets.UTF_8),
                HMAC_ALGORITHM
        );
    }

    /**
     * Все login-ключи используют hash tag {login}.
     * Поэтому email, IP и marker keys находятся в одном Redis Cluster slot.
     */
    public String loginEmail(
            String normalizedEmail
    ) {
        return withPrefix(
                "rate-limit:"
                        + LOGIN_HASH_TAG
                        + ":email:"
                        + hmac(
                                "login-email:",
                                requireIdentity(
                                        normalizedEmail
                                )
                        )
        );
    }

    public String loginIp(
            String normalizedIp
    ) {
        return withPrefix(
                "rate-limit:"
                        + LOGIN_HASH_TAG
                        + ":ip:"
                        + hmac(
                                "login-ip:",
                                requireIdentity(normalizedIp)
                        )
        );
    }

    /**
     * User и organization AI keys получают одинаковый
     * organization-scoped hash tag и попадают в один cluster slot.
     */
    public String aiMessageUser(
            UUID organizationId,
            UUID userId
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        return withPrefix(
                "rate-limit:"
                        + aiOrganizationHashTag(
                                organizationId
                        )
                        + ":user:"
                        + hmac(
                                "ai-user:",
                                userId.toString()
                        )
        );
    }

    public String aiMessageOrganization(
            UUID organizationId
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        return withPrefix(
                "rate-limit:"
                        + aiOrganizationHashTag(
                                organizationId
                        )
                        + ":organization"
        );
    }

    /**
     * Marker формируется в Java, но всегда передаётся Lua как отдельный KEYS.
     */
    public String exceededMarker(
            String counterKey
    ) {
        return requireKey(counterKey) + ":exceeded";
    }

    public String emailFingerprint(
            String normalizedEmail
    ) {
        return hmac(
                "audit-email:",
                requireIdentity(normalizedEmail)
        );
    }

    public String ipFingerprint(
            String normalizedIp
    ) {
        return hmac(
                "audit-ip:",
                requireIdentity(normalizedIp)
        );
    }

    private String aiOrganizationHashTag(
            UUID organizationId
    ) {
        String organizationDigest = hmac(
                "ai-organization:",
                organizationId.toString()
        );

        return "{ai-"
                + organizationDigest.substring(
                        0,
                        ORGANIZATION_TAG_LENGTH
                )
                + "}";
    }

    private String withPrefix(
            String key
    ) {
        return properties.keyPrefix()
                + ":"
                + properties.keyVersion()
                + ":"
                + key;
    }

    private String requireIdentity(
            String value
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "rate-limit identity не должна быть пустой"
            );
        }

        return value;
    }

    private String requireKey(
            String value
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "rate-limit key не должен быть пустым"
            );
        }

        return value;
    }

    private String hmac(
            String domain,
            String value
    ) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);

            byte[] hash = mac.doFinal(
                    (domain + value)
                            .getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    HMAC_ALGORITHM
                            + " algorithm is not available",
                    exception
            );
        }
    }
}
