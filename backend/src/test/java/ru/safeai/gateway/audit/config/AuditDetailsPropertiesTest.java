package ru.safeai.gateway.audit.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditDetailsPropertiesTest {

    @Test
    void defaultsLeaveHeadroomBeforeDatabaseJsonLimit() {
        AuditDetailsProperties properties =
                new AuditDetailsProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        assertThat(properties.maxJsonBytes())
                .isEqualTo(60 * 1_024);
        assertThat(properties.maxJsonBytes())
                .isLessThan(65_536);
    }

    @Test
    void applicationCannotBeConfiguredAboveDatabaseSafeBudget() {
        assertThatThrownBy(() ->
                new AuditDetailsProperties(
                        4,
                        100,
                        500,
                        24_000,
                        1_024,
                        65_536,
                        256
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "max-json-bytes"
                );
    }
}
