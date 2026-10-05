package ru.safeai.gateway.model.repository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelRouteDecisionRepositoryContractTest {

    @Test
    void monthlySnapshotKeepsActualReservedAndUnknownLiabilitySeparate() {
        var snapshot = new ModelRouteDecisionRepository.MonthlyCostSnapshot(
                new BigDecimal("7.500000000000"),
                2L,
                new BigDecimal("5.000000000000"),
                new BigDecimal("2.500000000000")
        );

        assertThat(snapshot.committedCostUsd()).isEqualByComparingTo("7.500000000000");
        assertThat(snapshot.actualCostUsd()).isEqualByComparingTo("5.000000000000");
        assertThat(snapshot.reservedCostUsd()).isEqualByComparingTo("2.500000000000");
        assertThat(snapshot.unknownCommittedCostCount()).isEqualTo(2L);
    }

    @Test
    void legacyTwoArgumentSnapshotConstructorRemainsBackwardCompatible() {
        var snapshot = new ModelRouteDecisionRepository.MonthlyCostSnapshot(
                new BigDecimal("3.000000000000"),
                1L
        );

        assertThat(snapshot.committedCostUsd()).isEqualByComparingTo("3.000000000000");
        assertThat(snapshot.actualCostUsd()).isEqualByComparingTo("3.000000000000");
        assertThat(snapshot.reservedCostUsd()).isZero();
        assertThat(snapshot.unknownCommittedCostCount()).isEqualTo(1L);
    }

    @Test
    void inconsistentOrNegativeSnapshotComponentsAreRejected() {
        assertThatThrownBy(() -> new ModelRouteDecisionRepository.MonthlyCostSnapshot(
                new BigDecimal("3.000000000000"),
                0L,
                new BigDecimal("2.000000000000"),
                new BigDecimal("2.000000000000")
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ModelRouteDecisionRepository.MonthlyCostSnapshot(
                BigDecimal.ZERO,
                -1L,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ModelRouteDecisionRepository.MonthlyCostSnapshot(
                BigDecimal.ZERO,
                0L,
                new BigDecimal("-0.000000000001"),
                new BigDecimal("0.000000000001")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidMonthlyWindowFailsBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ModelRouteDecisionRepository repository = new ModelRouteDecisionRepository(jdbc);
        Instant now = Instant.parse("2026-10-03T12:00:00Z");

        assertThatThrownBy(() -> repository.loadCommittedMonthlyCostSnapshot(
                UUID.randomUUID(), now, now
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive duration");

        verifyNoInteractions(jdbc);
    }

    @Test
    void budgetLockUsesTransactionScopedNamespacedAdvisoryLock() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID organizationId = UUID.randomUUID();

        try (Connection connection = mock(Connection.class);
             PreparedStatement statement = mock(PreparedStatement.class)) {
            when(connection.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?, 0))"
            )).thenReturn(statement);

            doAnswer(invocation -> {
                ConnectionCallback<?> callback = invocation.getArgument(0);
                return callback.doInConnection(connection);
            }).when(jdbc).execute(
                    org.mockito.ArgumentMatchers.<ConnectionCallback<Object>>any()
            );

            ModelRouteDecisionRepository repository =
                    new ModelRouteDecisionRepository(jdbc);
            repository.lockOrganizationBudget(organizationId);

            verify(statement).setString(
                    1,
                    "safeai:model-budget:" + organizationId
            );
            verify(statement).execute();
            verify(statement).close();
        }
    }

    @Test
    void transactionSensitiveRepositoryMethodsAreFailFastAnnotated() throws Exception {
        Method lock = ModelRouteDecisionRepository.class.getMethod(
                "lockOrganizationBudget", UUID.class
        );
        Method validate = ModelRouteDecisionRepository.class.getMethod(
                "validateAllowedTurnLinkBeforeExternalSideEffects"
        );

        Transactional lockTx = lock.getAnnotation(Transactional.class);
        Transactional validationTx = validate.getAnnotation(Transactional.class);

        assertThat(lockTx).isNotNull();
        assertThat(lockTx.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(validationTx).isNotNull();
        assertThat(validationTx.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void deferredTurnValidationUsesTheExactNamedConstraint() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ModelRouteDecisionRepository repository = new ModelRouteDecisionRepository(jdbc);

        repository.validateAllowedTurnLinkBeforeExternalSideEffects();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(sql.capture());

        String expected = String.join(
                " ",
                "set",
                "constraints",
                "ctrg_model_route_decision_turn_v45",
                "immediate"
        );
        assertThat(sql.getValue()).isEqualTo(expected);
    }

    @Test
    void nullBudgetLockIdentityIsRejectedBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ModelRouteDecisionRepository repository = new ModelRouteDecisionRepository(jdbc);

        assertThatThrownBy(() -> repository.lockOrganizationBudget(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("organizationId");
        verifyNoInteractions(jdbc);
    }
}
