package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.chat.config.ChatQuotaProperties;
import ru.safeai.gateway.chat.exception.ChatQuotaExceededException;
import ru.safeai.gateway.chat.quota.ChatQuotaConsumption;
import ru.safeai.gateway.chat.quota.ChatQuotaPolicy;
import ru.safeai.gateway.chat.quota.ChatQuotaReservation;
import ru.safeai.gateway.chat.repository.ChatQuotaRepository;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Quota reservation tests for the V67 route-bound contract.
 *
 * <p>No test calls deprecated five-argument reserve(), or the legacy
 * insertReservation(..., ChatQuotaProperties, ...). All verification of
 * overloaded insertReservation() uses an explicit ChatQuotaReservation type.</p>
 */
@ExtendWith(MockitoExtension.class)
class ChatQuotaServiceTest {

    private static final UUID ROUTE_DECISION_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
    );

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 8, 1);

    private static final ChatQuotaReservation ROUTE_RESERVATION =
            new ChatQuotaReservation(
                    ROUTE_DECISION_ID,
                    100L,
                    50L,
                    new BigDecimal("0.500000000000"),
                    true
            );

    @Mock
    private ChatQuotaRepository repository;

    private ChatQuotaService service;

    @BeforeEach
    void setUp() {
        ChatQuotaProperties properties = new ChatQuotaProperties(
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
                ChatTestFixtures.USER_ID,
                ROUTE_RESERVATION
        );

        verifyNoInteractions(repository);
    }

    @Test
    void reservationLocksOrganizationThenUserAndInsertsRouteBoundLedgerRow() {
        stubVerifiedPricingScheme();
        stubUnlimited();

        reserve(ROUTE_RESERVATION);

        InOrder order = inOrder(repository);
        order.verify(repository).lockOrganizationPolicy(ChatTestFixtures.ORGANIZATION_ID);
        order.verify(repository).lockUserPolicy(ChatTestFixtures.USER_ID);
        order.verify(repository).insertReservation(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.ORGANIZATION_ID,
                ChatTestFixtures.USER_ID,
                PERIOD_START,
                ROUTE_RESERVATION,
                ChatTestFixtures.NOW
        );

        verify(repository).hasVerifiedRoutePricingScheme(ROUTE_DECISION_ID);
    }

    @Test
    void requestLimitIncludesNewReservation() {
        stubVerifiedPricingScheme();
        when(repository.lockOrganizationPolicy(any(UUID.class)))
                .thenReturn(new ChatQuotaPolicy(true, 10L, null, null, null));
        when(repository.lockUserPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.organizationConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(consumption(10L, 0L, "0", false, false));

        assertThatThrownBy(this::reserve)
                .isInstanceOf(ChatQuotaExceededException.class)
                .hasMessageContaining("monthlyRequests");

        verifyNoRouteReservationInserted();
    }

    @Test
    void inputTokenLimitUsesSelectedRouteRatherThanStaticProperties() {
        stubVerifiedPricingScheme();
        when(repository.lockOrganizationPolicy(any(UUID.class)))
                .thenReturn(new ChatQuotaPolicy(true, null, 1_000L, 1_000L, null));
        when(repository.lockUserPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.organizationConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(consumption(0L, 950L, "0", false, false));

        assertThatThrownBy(this::reserve)
                .isInstanceOf(ChatQuotaExceededException.class)
                .hasMessageContaining("monthlyInputTokens");

        verifyNoRouteReservationInserted();
    }

    @Test
    void costLimitUsesVerifiedRouteUpperBound() {
        stubVerifiedPricingScheme();
        when(repository.lockOrganizationPolicy(any(UUID.class)))
                .thenReturn(new ChatQuotaPolicy(
                        true, null, null, null, new BigDecimal("1.000000000000")
                ));
        when(repository.lockUserPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.organizationConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(consumption(0L, 0L, "0.600000000000", false, false));

        assertThatThrownBy(this::reserve)
                .isInstanceOf(ChatQuotaExceededException.class)
                .hasMessageContaining("monthlyCostUsd");

        verifyNoRouteReservationInserted();
    }

    @Test
    void unverifiablePreviousUsageFailsClosedForTokenLimit() {
        stubVerifiedPricingScheme();
        when(repository.lockOrganizationPolicy(any(UUID.class)))
                .thenReturn(new ChatQuotaPolicy(true, null, 1_000L, null, null));
        when(repository.lockUserPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.organizationConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(consumption(0L, 0L, "0", true, false));

        assertThatThrownBy(this::reserve)
                .isInstanceOf(ChatQuotaExceededException.class)
                .hasMessageContaining("monthlyTokensUnverifiable");

        verifyNoRouteReservationInserted();
    }

    @Test
    void unverifiablePreviousCostFailsClosedForMoneyLimit() {
        stubVerifiedPricingScheme();
        when(repository.lockOrganizationPolicy(any(UUID.class)))
                .thenReturn(new ChatQuotaPolicy(
                        true, null, null, null, new BigDecimal("10.000000000000")
                ));
        when(repository.lockUserPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.organizationConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(consumption(0L, 0L, "0", false, true));

        assertThatThrownBy(this::reserve)
                .isInstanceOf(ChatQuotaExceededException.class)
                .hasMessageContaining("monthlyCostUnverifiable");

        verifyNoRouteReservationInserted();
    }

    @Test
    void unsupportedPricingSchemeCannotPassMoneyLimit() {
        // Default Mockito boolean=false means the scheme has NOT been verified.
        when(repository.lockOrganizationPolicy(any(UUID.class)))
                .thenReturn(new ChatQuotaPolicy(
                        true, null, null, null, new BigDecimal("10.000000000000")
                ));
        when(repository.lockUserPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.organizationConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(emptyConsumption());

        assertThatThrownBy(this::reserve)
                .isInstanceOf(ChatQuotaExceededException.class)
                .hasMessageContaining("monthlyCostUnverifiable");

        verify(repository).hasVerifiedRoutePricingScheme(ROUTE_DECISION_ID);
        verifyNoRouteReservationInserted();
    }

    @Test
    void unpricedRouteCannotPassHardMoneyLimit() {
        ChatQuotaReservation unpriced = new ChatQuotaReservation(
                ROUTE_DECISION_ID, 100L, 50L, null, false
        );
        when(repository.lockOrganizationPolicy(any(UUID.class)))
                .thenReturn(new ChatQuotaPolicy(
                        true, null, null, null, new BigDecimal("10.000000000000")
                ));
        when(repository.lockUserPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.organizationConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(emptyConsumption());

        assertThatThrownBy(() -> reserve(unpriced))
                .isInstanceOf(ChatQuotaExceededException.class)
                .hasMessageContaining("monthlyCostUnverifiable");

        verify(repository, never()).hasVerifiedRoutePricingScheme(any(UUID.class));
        verifyNoRouteReservationInserted();
    }

    @Test
    void unpricedRouteCanBeRecordedAsUnknownWhenNoHardMoneyLimitExists() {
        ChatQuotaReservation unpriced = new ChatQuotaReservation(
                ROUTE_DECISION_ID, 100L, 50L, null, false
        );
        stubUnlimited();

        reserve(unpriced);

        verify(repository).insertReservation(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.ORGANIZATION_ID,
                ChatTestFixtures.USER_ID,
                PERIOD_START,
                unpriced,
                ChatTestFixtures.NOW
        );
        verify(repository, never()).hasVerifiedRoutePricingScheme(any(UUID.class));
    }

    @Test
    void userRequestLimitRejectsAfterOrganizationPolicyPasses() {
        stubVerifiedPricingScheme();
        when(repository.lockOrganizationPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.lockUserPolicy(any(UUID.class)))
                .thenReturn(new ChatQuotaPolicy(true, 10L, null, null, null));
        when(repository.organizationConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(emptyConsumption());
        when(repository.userConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(consumption(10L, 0L, "0", false, false));

        assertThatThrownBy(this::reserve)
                .isInstanceOf(ChatQuotaExceededException.class)
                .hasMessageContaining("user.monthlyRequests");

        verifyNoRouteReservationInserted();
    }

    @Test
    void userCostUnverifiableFailsClosedAfterOrganizationPolicyPasses() {
        stubVerifiedPricingScheme();
        when(repository.lockOrganizationPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.lockUserPolicy(any(UUID.class)))
                .thenReturn(new ChatQuotaPolicy(
                        true, null, null, null, new BigDecimal("10.000000000000")
                ));
        when(repository.organizationConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(emptyConsumption());
        when(repository.userConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(consumption(0L, 0L, "0", false, true));

        assertThatThrownBy(this::reserve)
                .isInstanceOf(ChatQuotaExceededException.class)
                .hasMessageContaining("user.monthlyCostUnverifiable");

        verifyNoRouteReservationInserted();
    }

    @Test
    void userTokenUsageUnverifiableFailsClosedAfterOrganizationPolicyPasses() {
        stubVerifiedPricingScheme();
        when(repository.lockOrganizationPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.lockUserPolicy(any(UUID.class)))
                .thenReturn(new ChatQuotaPolicy(true, null, 1_000L, null, null));
        when(repository.organizationConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(emptyConsumption());
        when(repository.userConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(consumption(0L, 0L, "0", true, false));

        assertThatThrownBy(this::reserve)
                .isInstanceOf(ChatQuotaExceededException.class)
                .hasMessageContaining("user.monthlyTokensUnverifiable");

        verifyNoRouteReservationInserted();
    }

    @Test
    void unpricedRouteCannotPassUserHardMoneyLimit() {
        ChatQuotaReservation unpriced = new ChatQuotaReservation(
                ROUTE_DECISION_ID, 100L, 50L, null, false
        );
        when(repository.lockOrganizationPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.lockUserPolicy(any(UUID.class)))
                .thenReturn(new ChatQuotaPolicy(
                        true, null, null, null, new BigDecimal("10.000000000000")
                ));
        when(repository.organizationConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(emptyConsumption());
        when(repository.userConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(emptyConsumption());

        assertThatThrownBy(() -> reserve(unpriced))
                .isInstanceOf(ChatQuotaExceededException.class)
                .hasMessageContaining("user.monthlyCostUnverifiable");

        verify(repository, never()).hasVerifiedRoutePricingScheme(any(UUID.class));
        verifyNoRouteReservationInserted();
    }

    @Test
    void outputTokenLimitUsesSelectedRouteRatherThanStaticProperties() {
        stubVerifiedPricingScheme();
        when(repository.lockOrganizationPolicy(any(UUID.class)))
                .thenReturn(new ChatQuotaPolicy(true, null, null, 1_000L, null));
        when(repository.lockUserPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.organizationConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(new ChatQuotaConsumption(
                        0L, 0L, 975L, BigDecimal.ZERO, false, false
                ));

        assertThatThrownBy(this::reserve)
                .isInstanceOf(ChatQuotaExceededException.class)
                .hasMessageContaining("organization.monthlyOutputTokens");

        verifyNoRouteReservationInserted();
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
        reserve(ROUTE_RESERVATION);
    }

    private void reserve(ChatQuotaReservation reservation) {
        service.reserve(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.ORGANIZATION_ID,
                ChatTestFixtures.USER_ID,
                reservation
        );
    }

    private void stubVerifiedPricingScheme() {
        when(repository.hasVerifiedRoutePricingScheme(ROUTE_DECISION_ID))
                .thenReturn(true);
    }

    private void stubUnlimited() {
        when(repository.lockOrganizationPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.lockUserPolicy(any(UUID.class)))
                .thenReturn(ChatQuotaPolicy.unlimited());
        when(repository.organizationConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(emptyConsumption());
        when(repository.userConsumption(any(UUID.class), any(LocalDate.class)))
                .thenReturn(emptyConsumption());
    }

    private static ChatQuotaConsumption emptyConsumption() {
        return consumption(0L, 0L, "0", false, false);
    }

    private static ChatQuotaConsumption consumption(
            long requests,
            long inputTokens,
            String costUsd,
            boolean usageUnverifiable,
            boolean costUnverifiable
    ) {
        return new ChatQuotaConsumption(
                requests,
                inputTokens,
                0L,
                new BigDecimal(costUsd),
                usageUnverifiable,
                costUnverifiable
        );
    }

    private void verifyNoRouteReservationInserted() {
        // Explicit typed matcher selects the V67 overload, not the deprecated
        // insertReservation(..., ChatQuotaProperties, ...).
        verify(repository, never()).insertReservation(
                any(UUID.class),
                any(UUID.class),
                any(UUID.class),
                any(LocalDate.class),
                any(ChatQuotaReservation.class),
                any(Instant.class)
        );
    }
}
