package ru.safeai.gateway.usage.config;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class UsageJdbcClientsTest {

    @Test
    void queryAndRollupClientsUseIndependentConfiguredTimeouts() {
        UsageProperties properties = new UsageProperties(
                Duration.ofDays(30),
                Duration.ofDays(366),
                Duration.ofDays(31),
                200,
                Duration.ofMillis(1500),
                Duration.ofSeconds(1),
                321,
                4,
                new UsageProperties.Rollup(
                        true,
                        null,
                        3,
                        31,
                        null,
                        Duration.ofMillis(2500)
                )
        );

        UsageJdbcClients clients = new UsageJdbcClients(
                mock(DataSource.class),
                properties
        );

        assertThat(clients.query().getJdbcTemplate().getQueryTimeout())
                .isEqualTo(2);
        assertThat(clients.query().getJdbcTemplate().getFetchSize())
                .isEqualTo(321);
        assertThat(clients.rollup().getQueryTimeout())
                .isEqualTo(3);
    }

    @Test
    void requiredDependenciesAreValidated() {
        UsageProperties properties = new UsageProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> new UsageJdbcClients(null, properties))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("dataSource");
        assertThatThrownBy(() -> new UsageJdbcClients(
                mock(DataSource.class),
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("properties");
    }
}
