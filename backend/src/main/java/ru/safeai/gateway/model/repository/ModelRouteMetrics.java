package ru.safeai.gateway.model.repository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Count committed inserts rather than attempted admissions or replayed decisions. */
@Component
public final class ModelRouteMetrics {
    private final Map<String, Counter> decisions = new HashMap<>();
    private final Map<String, Counter> exceeded = new HashMap<>();

    public ModelRouteMetrics(MeterRegistry registry) {
        for (var outcome : ModelRouteOutcome.values()) {
            String label = label(outcome);
            exceeded.put(label, registry.counter("safeai.model.route.budget.exceeded", "outcome", label));
            for (var reason : ModelRouteReason.values()) {
                decisions.put(label + ":" + label(reason), registry.counter("safeai.model.route.decisions",
                        "outcome", label, "reason", label(reason)));
            }
        }
    }

    void afterInsert(ModelRouteDecision decision) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        record(decision);
                    }
                });
            } else {
                record(decision);
            }
        } catch (RuntimeException ignored) {
            // The committed ledger is authoritative; metrics cannot affect admission.
        }
    }

    private void record(ModelRouteDecision decision) {
        try {
            String outcome = label(decision.outcome());
            decisions.get(outcome + ":" + label(decision.reason())).increment();
            if (decision.budgetExceeded()) exceeded.get(outcome).increment();
        } catch (RuntimeException ignored) {
            // Never propagate observation failures from an after-commit callback.
        }
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
