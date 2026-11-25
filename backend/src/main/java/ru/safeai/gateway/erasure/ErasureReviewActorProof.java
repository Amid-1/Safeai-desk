// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ErasureReviewActorProof.java
package ru.safeai.gateway.erasure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Binds the authenticated application principal to one PostgreSQL transaction,
 * case, action, version and idempotency key. The database independently verifies
 * HMAC using a key stored outside the safeai_app role's privileges.
 *
 * Never log the key or signature. Configuring only one side must fail closed.
 */
@Component
public final class ErasureReviewActorProof {
    private static final String SESSION_PROPERTY = "safeai.erasure.review.actor_proof_v105";
    private final JdbcTemplate jdbc;
    private final String keyHex;

    public ErasureReviewActorProof(JdbcTemplate jdbc,
            @Value("${safeai.erasure.review.actor-proof-key:}") String keyHex) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.keyHex = keyHex;
    }

    public void bind(UUID actor, UUID organization, UUID request, String action,
            long newVersion, String actionKey) {
        if (keyHex == null || !keyHex.matches("[a-fA-F0-9]{64}")) {
            throw new ForbiddenOperationException(
                    "Действия рассмотрения недоступны: не настроена проверка автора в БД.");
        }
        Objects.requireNonNull(actor);
        Objects.requireNonNull(organization);
        Objects.requireNonNull(request);
        Objects.requireNonNull(action);
        Objects.requireNonNull(actionKey);
        Long transactionId = jdbc.queryForObject("SELECT txid_current()", Long.class);
        if (transactionId == null || transactionId <= 0) {
            throw new IllegalStateException("PostgreSQL не вернул идентификатор транзакции.");
        }
        String payload = "v1|" + transactionId + "|" + actor + "|" + organization
                + "|" + request + "|" + action + "|" + newVersion + "|" + actionKey;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(keyHex), "HmacSHA256"));
            String signature = HexFormat.of().formatHex(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            jdbc.queryForObject("SELECT set_config(?, ?, true)", String.class,
                    SESSION_PROPERTY, signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 не поддерживается runtime Java.", e);
        }
    }
}
