package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.chat.config.ChatQuotaProperties;
import ru.safeai.gateway.chat.exception.ChatQuotaExceededException;
import ru.safeai.gateway.chat.quota.ChatQuotaConsumption;
import ru.safeai.gateway.chat.quota.ChatQuotaPolicy;
import ru.safeai.gateway.chat.repository.ChatQuotaRepository;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatQuotaServiceTest {

    @Mock ChatQuotaRepository repository;

    private ChatQuotaProperties properties;
    private ChatQuotaService service;

    @BeforeEach
    void setUp() {
        properties = new ChatQuotaProperties(
                true,
                100L,
                50L,
                new BigDecimal("0.500000000000"),
                "UTC"
        );
        service = new ChatQuotaService(
                repository,
                properties,
                ChatTestFixtures.CLOCK
        );
    }

    @Test
    void disabledQuotaDoesNothing() {
        ChatQuotaService disabled = new ChatQuotaService(
                repository,
                new ChatQuotaProperties(false, null, null, null, "UTC"),
                ChatTestFixtures.CLOCK
        );

        disabled.reserve(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.ORGANIZATION_ID,
                ChatTestFixtures.USER_ID
        );

        verifyNoInteractions(repository);
    }

    @Test
    void reservationLocksOrganizationThenUserAndInsertsLedgerRow() {
        stubUnlimited();

        service.reserve(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.ORGANIZATION_ID,
                ChatTestFixtures.USER_ID
        );

        verify(repository).lockOrganizationPolicy(
                ChatTestFixtures.ORGANIZATION_ID
        );
        verify(repository).lockUserPolicy(ChatTestFixtures.USER_ID);
        verify(repository).insertReservation(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.ORGANIZATION_ID,
                ChatTestFixtures.USER_ID,
                LocalDate.of(2026, 8, 1),
                properties,
                ChatTestFixtures.NOW
        );
    }

    @Test
    void requestLimitIncludesNewReservation() {
        when(repository.lockOrganizationPolicy(anyOrg()))
                .thenReturn(new ChatQuotaPolicy(true, 10L, null, null, null));
        when(repository.lockUserPolicy(anyUser()))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.organizationConsumption(anyOrg(), anyPeriod()))
                .thenReturn(new ChatQuotaConsumption(
                        10, 0, 0, BigDecimal.ZERO
                ));
        when(repository.userConsumption(anyUser(), anyPeriod()))
                .thenReturn(new ChatQuotaConsumption(
                        0, 0, 0, BigDecimal.ZERO
                ));

        assertThatThrownBy(this::reserve)
                .isInstanceOf(ChatQuotaExceededException.class)
                .hasMessageContaining("monthlyRequests");

        verify(repository, never()).insertReservation(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void tokenAndCostReservationsAreConservative() {
        when(repository.lockOrganizationPolicy(anyOrg()))
                .thenReturn(new ChatQuotaPolicy(
                        true,
                        null,
                        1_000L,
                        1_000L,
                        new BigDecimal("1.000000000000")
                ));
        when(repository.lockUserPolicy(anyUser()))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.organizationConsumption(anyOrg(), anyPeriod()))
                .thenReturn(new ChatQuotaConsumption(
                        0,
                        950,
                        0,
                        new BigDecimal("0.600000000000")
                ));
        when(repository.userConsumption(anyUser(), anyPeriod()))
                .thenReturn(new ChatQuotaConsumption(
                        0, 0, 0, BigDecimal.ZERO
                ));

        assertThatThrownBy(this::reserve)
                .isInstanceOf(ChatQuotaExceededException.class);
    }

    @Test
    void successfulUnpricedResponseIsDelegatedWithoutZeroCostAssumption() {
        service.settleSuccess(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.unpricedResponse(),
                ChatTestFixtures.NOW
        );

        verify(repository).settleSuccess(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.unpricedResponse(),
                ChatTestFixtures.NOW
        );
    }

    @Test
    void ambiguousOperationKeepsConservativeReservation() {
        service.markAmbiguous(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.NOW
        );

        verify(repository).markAmbiguous(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.NOW
        );
    }

    private void reserve() {
        service.reserve(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.ORGANIZATION_ID,
                ChatTestFixtures.USER_ID
        );
    }

    private void stubUnlimited() {
        when(repository.lockOrganizationPolicy(anyOrg()))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.lockUserPolicy(anyUser()))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.organizationConsumption(anyOrg(), anyPeriod()))
                .thenReturn(new ChatQuotaConsumption(
                        0, 0, 0, BigDecimal.ZERO
                ));
        when(repository.userConsumption(anyUser(), anyPeriod()))
                .thenReturn(new ChatQuotaConsumption(
                        0, 0, 0, BigDecimal.ZERO
                ));
    }

    private static java.util.UUID anyOrg() {
        return org.mockito.ArgumentMatchers.any(java.util.UUID.class);
    }

    private static java.util.UUID anyUser() {
        return org.mockito.ArgumentMatchers.any(java.util.UUID.class);
    }

    private static LocalDate anyPeriod() {
        return org.mockito.ArgumentMatchers.any(LocalDate.class);
    }
}
