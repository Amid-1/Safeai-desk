package ru.safeai.gateway.chat.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatConfigurationPropertiesTest {

    @Test
    void chatDefaultsAreBoundedProductionValues() {
        ChatProperties properties =
                new ChatProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        assertThat(properties.detailsMessageLimit())
                .isEqualTo(50);

        assertThat(properties.historyTurnLimit())
                .isEqualTo(50);

        assertThat(properties.maxChatPageSize())
                .isEqualTo(100);

        assertThat(properties.maxMessagePageSize())
                .isEqualTo(100);

        assertThat(properties.maxMessageChars())
                .isEqualTo(16_000);

        assertThat(properties.processingLease())
                .isEqualTo(Duration.ofMinutes(3));

        assertThat(properties.leaseRenewalThreads())
                .isEqualTo(4);

        assertThat(properties.maxActiveLeaseWatchdogs())
                .isEqualTo(1_000);
    }

    @Test
    void processingLeaseMustLeaveEnoughTimeForWatchdog() {
        assertThatThrownBy(() ->
                new ChatProperties(
                        50,
                        50,
                        100,
                        100,
                        16_000,
                        Duration.ofSeconds(29),
                        4,
                        1_000
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "processing-lease"
                );
    }

    @Test
    void pageSizeCannotExceedPublicApiMaximum() {
        assertThatThrownBy(() ->
                new ChatProperties(
                        50,
                        50,
                        101,
                        100,
                        16_000,
                        Duration.ofMinutes(3),
                        4,
                        1_000
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "max-chat-page-size"
                );
    }

    @Test
    void lockPrefixIsNormalizedAndOwnerWatchdogIsBounded() {
        ChatLockProperties properties =
                new ChatLockProperties(
                        " safeai:test:chat-lock::: ",
                        Duration.ofMinutes(4),
                        4,
                        1_000,
                        Duration.ofSeconds(5)
                );

        assertThat(properties.keyPrefix())
                .isEqualTo(
                        "safeai:test:chat-lock"
                );

        assertThat(properties.renewalInterval())
                .isEqualTo(
                        Duration.ofSeconds(80)
                );
    }

    @Test
    void lockConfigurationRejectsUnsafeTtlAndEmptyPrefix() {
        assertThatThrownBy(() ->
                new ChatLockProperties(
                        " ",
                        Duration.ofMinutes(4),
                        4,
                        1_000,
                        Duration.ofSeconds(5)
                )
        ).isInstanceOf(
                IllegalStateException.class
        );

        assertThatThrownBy(() ->
                new ChatLockProperties(
                        "safeai:test",
                        Duration.ofSeconds(10),
                        4,
                        1_000,
                        Duration.ofSeconds(5)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining("ttl");
    }

    @Test
    void quotaDefaultsReserveConservativelyInUtc() {
        ChatQuotaProperties properties =
                new ChatQuotaProperties(
                        null,
                        null,
                        null,
                        null,
                        null
                );

        assertThat(properties.isEnabled())
                .isTrue();

        assertThat(properties.reservationInputTokens())
                .isEqualTo(16_000);

        assertThat(properties.reservationOutputTokens())
                .isEqualTo(2_048);

        assertThat(properties.reservationCostUsd())
                .isEqualByComparingTo(
                        "1.000000000000"
                );

        assertThat(properties.periodZone())
                .isEqualTo("UTC");

        assertThat(properties.effectivePeriodZone())
                .isEqualTo(
                        ZoneId.of("UTC")
                );
    }

    @Test
    void quotaCostRejectsNegativeAndScaleAboveDatabaseContract() {
        assertThatThrownBy(() ->
                new ChatQuotaProperties(
                        true,
                        1L,
                        1L,
                        new BigDecimal("-0.01"),
                        "UTC"
                )
        ).isInstanceOf(
                IllegalStateException.class
        );

        assertThatThrownBy(() ->
                new ChatQuotaProperties(
                        true,
                        1L,
                        1L,
                        new BigDecimal(
                                "0.0000000000001"
                        ),
                        "UTC"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining("scale");
    }

    @Test
    void invalidQuotaTimezoneFailsAtStartup() {
        assertThatThrownBy(() ->
                new ChatQuotaProperties(
                        true,
                        1L,
                        1L,
                        BigDecimal.ZERO,
                        "Mars/Olympus"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "safeai.chat.quota.period-zone"
                )
                .hasMessageContaining(
                        "неизвестную временную зону"
                )
                .hasMessageContaining(
                        "Mars/Olympus"
                )
                .hasCauseInstanceOf(
                        DateTimeException.class
                );
    }

    @Test
    void recoveryConfigurationHasBoundedBatchAndCadence() {
        ChatRecoveryProperties properties =
                new ChatRecoveryProperties(
                        null,
                        null,
                        null,
                        null
                );

        assertThat(properties.isEnabled())
                .isTrue();

        assertThat(properties.batchSize())
                .isEqualTo(100);

        assertThat(properties.maxBatchesPerRun())
                .isEqualTo(20);

        assertThat(properties.cron())
                .isEqualTo(
                        "0 * * * * *"
                );
    }

    @Test
    void recoveryCannotCreateUnboundedDatabaseWork() {
        assertThatThrownBy(() ->
                new ChatRecoveryProperties(
                        true,
                        1_001,
                        20,
                        "0 * * * * *"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "batch-size"
                );

        assertThatThrownBy(() ->
                new ChatRecoveryProperties(
                        true,
                        100,
                        1_001,
                        "0 * * * * *"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "max-batches-per-run"
                );
    }
}