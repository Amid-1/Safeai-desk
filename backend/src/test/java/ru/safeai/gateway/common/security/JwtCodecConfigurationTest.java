package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtCodecConfigurationTest {

    private static final Instant NOW =
            Instant.parse("2026-06-12T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final JwtProperties properties = new JwtProperties(
            Base64.getEncoder().encodeToString(new byte[32]),
            15,
            "https://safeai.example.com",
            "safeai-api"
    );

    private final JwtCodecConfiguration configuration =
            new JwtCodecConfiguration();
    private final JwtEncoder encoder = configuration.jwtEncoder(
            properties.secretKey()
    );
    private final JwtDecoder decoder = configuration.jwtDecoder(
            properties.secretKey(),
            properties,
            CLOCK
    );

    @Test
    void issuedTokenPassesSignatureTimeIssuerAudienceAndTypeValidation() {
        JwtService service = new JwtService(encoder, properties, CLOCK);
        AccessTokenSubject subject = new AccessTokenSubject(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "user@example.com",
                4,
                Set.of("USER")
        );

        var jwt = decoder.decode(service.generateToken(subject));

        assertThat(jwt.getHeaders().get("typ")).isEqualTo("JWT");
        assertThat(jwt.getIssuer()).hasToString(
                "https://safeai.example.com"
        );
        assertThat(jwt.getAudience()).containsExactly("safeai-api");
        assertThat(jwt.getClaimAsString("email"))
                .isEqualTo("user@example.com");
    }

    @Test
    void tokenWithWrongAudienceIsRejected() {
        assertRejected(token(
                properties.issuer(),
                List.of("other-api"),
                NOW,
                NOW.plusSeconds(300),
                "JWT"
        ));
    }

    @Test
    void tokenWithWrongIssuerIsRejected() {
        assertRejected(token(
                "https://attacker.example.com",
                List.of(properties.audience()),
                NOW,
                NOW.plusSeconds(300),
                "JWT"
        ));
    }

    @Test
    void expiredTokenIsRejected() {
        assertRejected(token(
                properties.issuer(),
                List.of(properties.audience()),
                NOW.minusSeconds(600),
                NOW.minusSeconds(60),
                "JWT"
        ));
    }

    @Test
    void tokenWithoutExpiryIsRejected() {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .issuedAt(NOW)
                .subject(UUID.randomUUID().toString())
                .build();

        String rawToken = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();

        assertRejected(rawToken);
    }

    @Test
    void tokenWithUnexpectedTypeIsRejected() {
        assertRejected(token(
                properties.issuer(),
                List.of(properties.audience()),
                NOW,
                NOW.plusSeconds(300),
                "at+jwt"
        ));
    }

    private String token(
            String issuer,
            List<String> audience,
            Instant issuedAt,
            Instant expiresAt,
            String type
    ) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(audience)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(UUID.randomUUID().toString())
                .build();

        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type(type).build(),
                claims
        )).getTokenValue();
    }

    private void assertRejected(String rawToken) {
        assertThatThrownBy(() -> decoder.decode(rawToken))
                .isInstanceOf(JwtException.class);
    }
}
