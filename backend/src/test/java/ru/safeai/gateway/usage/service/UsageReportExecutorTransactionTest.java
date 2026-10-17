package ru.safeai.gateway.usage.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.usage.config.UsageProperties;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageReportExecutorTransactionTest {

    @Mock PlatformTransactionManager manager;
    @Mock JdbcTemplate jdbc;

    private UsageReportExecutor executor;

    @BeforeEach
    void setUp() {
        UsageProperties properties = new UsageProperties(
                Duration.ofDays(30), Duration.ofDays(366), Duration.ofDays(31),
                200, Duration.ofSeconds(10), Duration.ofSeconds(2),
                200, 1,
                new UsageProperties.Rollup(true, "0 */10 * * * *", 3, 31,
                        8_026_001L, Duration.ofMinutes(2))
        );
        executor = new UsageReportExecutor(properties, new SimpleMeterRegistry(), manager, jdbc);
    }

    @Test
    void obtainsReadOnlyRepeatableReadTransactionAndGlobalSlotBeforeAction() {
        AtomicBoolean enteredAction = new AtomicBoolean();
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> {
                    TransactionDefinition d = invocation.getArgument(0);
                    assertThat(d.isReadOnly()).isTrue();
                    assertThat(d.getIsolationLevel())
                            .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    assertThat(d.getPropagationBehavior())
                            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    return new SimpleTransactionStatus(true);
                });
        when(jdbc.queryForObject(eq("select pg_try_advisory_xact_lock(?)"),
                eq(Boolean.class), anyLong()))
                .thenAnswer(invocation -> {
                    assertThat(enteredAction.get()).isFalse();
                    return true;
                });

        assertThat(executor.execute("summary", () -> {
            enteredAction.set(true);
            return 123;
        })).isEqualTo(123);

        assertThat(enteredAction.get()).isTrue();
        var order = inOrder(manager, jdbc);
        order.verify(manager).getTransaction(any(TransactionDefinition.class));
        order.verify(jdbc).queryForObject(eq("select pg_try_advisory_xact_lock(?)"),
                eq(Boolean.class), anyLong());
        order.verify(manager).commit(any());
    }

    @Test
    void exhaustedGlobalSlotsRejectWithoutExecutingSupplier() {
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus(true));
        when(jdbc.queryForObject(eq("select pg_try_advisory_xact_lock(?)"),
                eq(Boolean.class), anyLong())).thenReturn(false);
        AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> executor.execute("summary", () -> {
            ran.set(true);
            return "should never run";
        }))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex)
                        .getStatusCode().value()).isEqualTo(429));

        assertThat(ran.get()).isFalse();
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
    }
}
