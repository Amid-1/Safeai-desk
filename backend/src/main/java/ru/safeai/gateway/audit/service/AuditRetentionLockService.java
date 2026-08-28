package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.persistence.PostgresAdvisoryLockExecutor;

import java.util.Objects;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class AuditRetentionLockService {

    private static final long RETENTION_LOCK_KEY =
            6_302_026_071_901L;

    private static final String LOCK_DESCRIPTION =
            "audit retention";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Выполняет действие только если текущему экземпляру удалось получить
     * session-level PostgreSQL advisory lock.
     *
     * <p>Lock, action и unlock выполняются внутри одного
     * {@link ConnectionCallback}, поэтому используют один и тот же JDBC
     * connection и одну PostgreSQL session.</p>
     *
     * <p>Низкоуровневая lock/unlock/abort semantics централизована в
     * {@link PostgresAdvisoryLockExecutor}. Если unlock завершается ошибкой,
     * потенциально небезопасный pooled connection аварийно закрывается
     * best-effort до повторного использования.</p>
     */
    public <T> LockExecution<T> tryExecute(
            Supplier<T> action
    ) {
        Objects.requireNonNull(
                action,
                "action не должен быть null"
        );

        ConnectionCallback<LockExecution<T>> callback =
                connection -> {
                    PostgresAdvisoryLockExecutor
                            .LockExecution<T> execution =
                            PostgresAdvisoryLockExecutor.tryExecute(
                                    connection,
                                    RETENTION_LOCK_KEY,
                                    LOCK_DESCRIPTION,
                                    action
                            );

                    if (!execution.acquired()) {
                        return LockExecution.notAcquired();
                    }

                    return LockExecution.acquired(
                            execution.result()
                    );
                };

        LockExecution<T> result =
                jdbcTemplate.execute(
                        callback
                );

        return Objects.requireNonNull(
                result,
                "JdbcTemplate returned null lock result"
        );
    }

    public record LockExecution<T>(
            boolean acquired,
            T result
    ) {

        public static <T> LockExecution<T> notAcquired() {
            return new LockExecution<>(
                    false,
                    null
            );
        }

        public static <T> LockExecution<T> acquired(
                T result
        ) {
            return new LockExecution<>(
                    true,
                    result
            );
        }
    }
}