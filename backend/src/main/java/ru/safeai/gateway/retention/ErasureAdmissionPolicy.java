// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/retention/ErasureAdmissionPolicy.java
package ru.safeai.gateway.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.safeai.gateway.common.exception.ConflictException;

import javax.sql.DataSource;

/**
 * The caller's existing acceptance transaction holds a SHARE lock until commit.
 * Pausing admission serializes with acceptance, not with execution of existing jobs.
 */
public final class ErasureAdmissionPolicy {
    private ErasureAdmissionPolicy() { }

    public enum Scope {
        CHAT,
        KNOWLEDGE
    }

    public static boolean allowed(JdbcTemplate jdbc, Scope scope) {
        return read(jdbc, scope, false);
    }

    public static void requireAllowed(JdbcTemplate jdbc, Scope scope) {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("Erasure admission requires an active write transaction on the policy datasource");
        }
        if (!read(jdbc, scope, true)) {
            throw new ConflictException("Новые запросы на удаление приостановлены политикой платформы.");
        }
    }

    private static boolean read(JdbcTemplate jdbc, Scope scope, boolean lock) {
        String column = switch (scope) {
            case CHAT -> "chat_allowed";
            case KNOWLEDGE -> "knowledge_allowed";
        };
        String sql = "SELECT " + column + " FROM public.platform_erasure_policy_v102 WHERE singleton";
        return Boolean.TRUE.equals(jdbc.queryForObject(sql + (lock ? " FOR SHARE" : ""), Boolean.class));
    }
}
