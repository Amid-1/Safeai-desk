package ru.safeai.gateway.model.repository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
class ModelRouteMetricsTest {
    @Test
    void countsOnlyCommittedDecisionsAndPreservesBudgetReason() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new ModelRouteMetrics(registry);
            var decision = mock(ModelRouteDecision.class);
            when(decision.outcome()).thenReturn(ModelRouteOutcome.DENIED);
            when(decision.reason()).thenReturn(ModelRouteReason.MONTHLY_BUDGET_EXCEEDED);
            when(decision.budgetExceeded()).thenReturn(true);
            var count = registry.get("safeai.model.route.decisions")
                    .tags("outcome", "denied", "reason", "monthly_budget_exceeded").counter();
            var exceeded = registry.get("safeai.model.route.budget.exceeded").tag("outcome", "denied").counter();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            metrics.afterInsert(decision);
            assertThat(count.count()).isZero();
            for (var callback : TransactionSynchronizationManager.getSynchronizations()) {
                callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            TransactionSynchronizationManager.clearSynchronization();
            assertThat(count.count()).isZero();
            TransactionSynchronizationManager.initSynchronization();
            metrics.afterInsert(decision);
            assertThat(count.count()).isZero();
            for (var callback : TransactionSynchronizationManager.getSynchronizations()) {
                callback.afterCommit();
                callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
            assertThat(count.count()).isEqualTo(1);
            assertThat(exceeded.count()).isEqualTo(1);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
            registry.close();
        }
    }
}
