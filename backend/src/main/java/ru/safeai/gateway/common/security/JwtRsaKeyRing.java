package ru.safeai.gateway.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Immutable in-process RSA JWT key ring.
 *
 * <p>Only the active key contains private key material in the signing source.
 * The verification source contains public JWKs for active and previous keys.
 * JWT header {@code kid} selects the matching verification key.</p>
 */
@Component
public final class JwtRsaKeyRing {

    private static final int MIN_RSA_BITS = 2048;

    private static final String PUBLIC_BEGIN =
            "-----BEGIN PUBLIC KEY-----";
    private static final String PUBLIC_END =
            "-----END PUBLIC KEY-----";
    private static final String PRIVATE_BEGIN =
            "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_END =
            "-----END PRIVATE KEY-----";

    private final JWKSource<SecurityContext> signingSource;
    private final JWKSource<SecurityContext> verificationSource;

    public JwtRsaKeyRing(JwtProperties properties) {
        Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );

        RSAKey activeSigningKey = null;
        List<JWK> publicKeys = new ArrayList<>();

        for (JwtProperties.KeyEntry entry : properties.keys()) {
            RSAPublicKey publicKey = parsePublicKey(
                    entry.publicKey(),
                    entry.id()
            );

            validateRsaStrength(publicKey, entry.id());

            RSAPrivateKey privateKey = null;

            if (entry.privateKey() != null) {
                privateKey = parsePrivateKey(
                        entry.privateKey(),
                        entry.id()
                );

                validateRsaStrength(privateKey, entry.id());

                if (!publicKey.getModulus().equals(
                        privateKey.getModulus()
                )) {
                    throw new IllegalStateException(
                            "JWT public/private key pair не совпадает для kid="
                                    + entry.id()
                    );
                }
            }

            RSAKey publicJwk = new RSAKey.Builder(publicKey)
                    .keyID(entry.id())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();

            publicKeys.add(publicJwk);

            if (entry.id().equals(properties.activeKeyId())) {
                if (privateKey == null) {
                    throw new IllegalStateException(
                            "Активный JWT key не содержит private key: "
                                    + entry.id()
                    );
                }

                activeSigningKey = new RSAKey.Builder(publicKey)
                        .privateKey(privateKey)
                        .keyID(entry.id())
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.RS256)
                        .build();
            }
        }

        if (activeSigningKey == null) {
            throw new IllegalStateException(
                    "Не удалось создать активный JWT signing key"
            );
        }

        JWKSet signingSet = new JWKSet(activeSigningKey);
        JWKSet verificationSet = new JWKSet(publicKeys);

        this.signingSource =
                (selector, context) -> selector.select(signingSet);

        this.verificationSource =
                (selector, context) -> selector.select(verificationSet);
    }

    JWKSource<SecurityContext> signingJwkSource() {
        return signingSource;
    }

    JWKSource<SecurityContext> verificationJwkSource() {
        return verificationSource;
    }

    private static RSAPublicKey parsePublicKey(
            String pem,
            String keyId
    ) {
        byte[] der = decodePem(
                pem,
                PUBLIC_BEGIN,
                PUBLIC_END,
                keyId,
                "public"
        );

        try {
            return (RSAPublicKey) rsaKeyFactory()
                    .generatePublic(
                            new X509EncodedKeySpec(der)
                    );
        } catch (InvalidKeySpecException
                 | ClassCastException exception) {
            throw new IllegalStateException(
                    "JWT public key должен быть RSA X.509 PUBLIC KEY, kid="
                            + keyId,
                    exception
            );
        }
    }

    private static RSAPrivateKey parsePrivateKey(
            String pem,
            String keyId
    ) {
        byte[] der = decodePem(
                pem,
                PRIVATE_BEGIN,
                PRIVATE_END,
                keyId,
                "private"
        );

        try {
            return (RSAPrivateKey) rsaKeyFactory()
                    .generatePrivate(
                            new PKCS8EncodedKeySpec(der)
                    );
        } catch (InvalidKeySpecException
                 | ClassCastException exception) {
            throw new IllegalStateException(
                    "JWT private key должен быть RSA PKCS#8 PRIVATE KEY, kid="
                            + keyId,
                    exception
            );
        }
    }

    private static byte[] decodePem(
            String raw,
            String beginMarker,
            String endMarker,
            String keyId,
            String keyType
    ) {
        String normalized = raw
                .replace("\\n", "\n")
                .trim();

        if (!normalized.contains(beginMarker)
                || !normalized.contains(endMarker)) {
            throw new IllegalStateException(
                    "JWT " + keyType + " key имеет неверный PEM format, kid="
                            + keyId
            );
        }

        String base64 = normalized
                .replace(beginMarker, "")
                .replace(endMarker, "")
                .replaceAll("\\s", "");

        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "JWT " + keyType + " key содержит некорректный Base64, kid="
                            + keyId,
                    exception
            );
        }
    }

    private static KeyFactory rsaKeyFactory() {
        try {
            return KeyFactory.getInstance("RSA");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "RSA KeyFactory недоступен",
                    exception
            );
        }
    }

    private static void validateRsaStrength(
            java.security.interfaces.RSAKey key,
            String keyId
    ) {
        int bits = key.getModulus().bitLength();

        if (bits < MIN_RSA_BITS) {
            throw new IllegalStateException(
                    "JWT RSA key должен быть минимум "
                            + MIN_RSA_BITS
                            + " бит; kid="
                            + keyId
                            + ", actualBits="
                            + bits
            );
        }
    }
}
