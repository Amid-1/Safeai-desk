package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.testsupport.UsagePostgresIntegrationTestSupport;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@SpringBootTest(properties = "safeai.usage.rollup.enabled=false")
@ActiveProfiles("test")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class UsagePricingCorrectnessIntegrationTest
        extends UsagePostgresIntegrationTestSupport {

    private static final String MODEL = "pricing-model";
    private static final Instant CREATED_AT =
            Instant.parse("2026-06-15T12:00:00Z");

    @Autowired
    private UsageQueryRepository repository;

    @Test
    void allPricedMessagesProduceExactKnownCost() {
        priced("0.100001", CREATED_AT);
        priced("1.400009", CREATED_AT.plusSeconds(1));

        UsageModelSummaryResponse result = result();

        assertThat(result.cost().knownCostUsd())
                .isEqualByComparingTo("1.500010");
        assertThat(result.cost().pricedMessages()).isEqualTo(2);
        assertThat(result.cost().pricingComplete()).isTrue();
    }

    @Test
    void allFreeMessagesHaveKnownZeroCostAndCompletePricing() {
        free(CREATED_AT);
        free(CREATED_AT.plusSeconds(1));

        UsageModelSummaryResponse result = result();

        assertThat(result.cost().knownCostUsd())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.cost().freeMessages()).isEqualTo(2);
        assertThat(result.cost().unpricedMessages()).isZero();
        assertThat(result.cost().pricingComplete()).isTrue();
    }

    @Test
    void allUnpricedMessagesRemainExplicitlyUnknown() {
        unpriced(CREATED_AT);
        unpriced(CREATED_AT.plusSeconds(1));

        UsageModelSummaryResponse result = result();

        assertThat(result.cost().knownCostUsd())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.cost().unpricedMessages()).isEqualTo(2);
        assertThat(result.cost().pricingComplete()).isFalse();
    }

    @Test
    void pricedAndUnpricedMessagesExposeKnownPartAndIncompleteCoverage() {
        priced("1.500000000000", CREATED_AT);
        unpriced(CREATED_AT.plusSeconds(1));

        UsageModelSummaryResponse result = result();

        assertThat(result.cost().knownCostUsd())
                .isEqualByComparingTo("1.500000000000");
        assertThat(result.cost().pricedMessages()).isEqualTo(1);
        assertThat(result.cost().unpricedMessages()).isEqualTo(1);
        assertThat(result.cost().pricingComplete()).isFalse();
    }

    @Test
    void calculationFailuresAreCountedSeparately() {
        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "COMPLETED",
                10,
                20,
                "AVAILABLE",
                null,
                "CALCULATION_FAILED",
                CREATED_AT
        );

        UsageModelSummaryResponse result = result();

        assertThat(result.cost().knownCostUsd())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.cost().pricingFailedMessages())
                .isEqualTo(1);
        assertThat(result.cost().unpricedMessages()).isZero();
        assertThat(result.cost().pricingComplete()).isFalse();
    }

    @Test
    void freePricingCannotCarryPositiveCost() {
        assertThatThrownBy(() -> insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "COMPLETED",
                10,
                20,
                "AVAILABLE",
                new BigDecimal("0.010000"),
                "FREE",
                CREATED_AT
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_chat_messages_free_zero");
    }

    @Test
    void pricedStatusCannotCarryZeroCost() {
        assertThatThrownBy(() -> insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "COMPLETED",
                10,
                20,
                "AVAILABLE",
                BigDecimal.ZERO,
                "PRICED",
                CREATED_AT
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_chat_messages_priced_positive");
    }

    @Test
    void nonUsdRowsAreRejectedBeforeTheyCanBeAggregated() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into public.chat_messages (
                            id,
                            session_id,
                            organization_id,
                            role,
                            content,
                            model,
                            input_tokens,
                            output_tokens,
                            cost_usd,
                            status,
                            created_at,
                            usage_status,
                            pricing_status,
                            currency,
                            pricing_version,
                            pricing_calculated_at,
                            provider_message_id,
                            ai_response_status,
                            finish_reason
                        ) values (
                            ?, ?, ?,
                            'ASSISTANT',
                            'EUR row must be rejected',
                            ?, 10, 20, 1.000000000000,
                            'COMPLETED', ?,
                            'AVAILABLE', 'PRICED', 'EUR',
                            'pricing-v1', ?, ?,
                            'COMPLETED', 'completed'
                        )
                        """,
                UUID.randomUUID(),
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT),
                UUID.randomUUID().toString()
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "chk_chat_messages_pricing_currency_usd"
                );
    }

    private UsageModelSummaryResponse result() {
        List<UsageModelSummaryResponse> rows = repository.findModels(
                criteria(
                        ORGANIZATION_A_ID,
                        null,
                        MODEL,
                        RANGE_FROM,
                        RANGE_TO
                ),
                livePlan(RANGE_FROM, RANGE_TO)
        );

        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private void priced(String amount, Instant createdAt) {
        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "COMPLETED",
                10,
                20,
                "AVAILABLE",
                new BigDecimal(amount),
                "PRICED",
                createdAt
        );
    }

    private void free(Instant createdAt) {
        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "COMPLETED",
                10,
                20,
                "AVAILABLE",
                BigDecimal.ZERO,
                "FREE",
                createdAt
        );
    }

    private void unpriced(Instant createdAt) {
        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "COMPLETED",
                10,
                20,
                "AVAILABLE",
                null,
                "UNPRICED",
                createdAt
        );
    }
}
