package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import ru.safeai.gateway.usage.config.UsageJdbcClients;
import ru.safeai.gateway.usage.dto.UsageProviderRow;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Regression at the actual Spring JDBC named-parameter parsing boundary, without Docker. */
class UsagePhysicalSpendRepositorySqlTest {

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = FROM.plusSeconds(86400);
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @Test
    void providerSqlBindsEveryFilterCombinationWithoutMergingParameterAndGroupBy() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var repository = repository(jdbc);

        for (UUID tenant : new UUID[]{null, TENANT}) {
            for (String model : new String[]{null, "logical-model"}) {
                for (UUID actor : new UUID[]{null, ACTOR}) {
                    doAnswer(invocation -> {
                        String sql = invocation.getArgument(0);
                        SqlParameterSource params = invocation.getArgument(1);
                        String bound = assertBound(sql, params, tenant, 6);
                        assertThat(bound).contains("p.requested_model = ?", "t.user_id = ?");
                        assertThat(bound).containsPattern(tenant == null
                                ? "t\\.user_id = \\?\\)\\s+group by"
                                : "p\\.organization_id = \\?\\s+group by");
                        assertThat(params.getValue("model")).isEqualTo(model);
                        assertThat(params.getValue("userId")).isEqualTo(actor);
                        return List.of();
                    }).when(jdbc).query(anyString(), any(SqlParameterSource.class),
                            ArgumentMatchers.<RowMapper<UsageProviderRow>>any());
                    assertThat(repository.findProviders(FROM, TO, tenant, model, actor)).isEmpty();
                }
            }
        }
    }

    @Test
    void coverageAndReconciliationBindTenantWithoutChangingInterval() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var repository = repository(jdbc);
        for (UUID tenant : new UUID[]{null, TENANT}) {
            doAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                SqlParameterSource params = invocation.getArgument(1);
                String bound = assertBound(sql, params, tenant, 2);
                if (tenant != null && sql.contains("with selected_plans")) {
                    assertThat(bound).containsPattern("p\\.organization_id = \\?\\s+\\), per_plan");
                }
                RowMapper<Object> mapper = invocation.getArgument(2);
                return mapper.mapRow(mock(ResultSet.class), 0);
            }).when(jdbc).queryForObject(anyString(), any(SqlParameterSource.class),
                    ArgumentMatchers.<RowMapper<Object>>any());
            assertThat(repository.findCoverage(FROM, TO, tenant).organizationId()).isEqualTo(tenant);
            assertThat(repository.reconcile(FROM, TO, tenant).organizationId()).isEqualTo(tenant);
        }
    }

    @Test
    void invalidIntervalsNeverReachJdbc() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var repository = repository(jdbc);
        assertThatThrownBy(() -> repository.findProviders(TO, FROM, TENANT, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findCoverage(FROM, FROM, TENANT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.reconcile(TO, FROM, TENANT))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    private static UsagePhysicalSpendRepository repository(NamedParameterJdbcTemplate jdbc) {
        var clients = mock(UsageJdbcClients.class);
        when(clients.query()).thenReturn(jdbc);
        return new UsagePhysicalSpendRepository(clients);
    }

    private static String assertBound(String sql, SqlParameterSource params, UUID tenant, int count) {
        var parsed = NamedParameterUtils.parseSqlStatement(sql);
        var values = NamedParameterUtils.buildValueArray(parsed, params, null);
        assertThat(values).hasSize(count + (tenant == null ? 0 : 1));
        assertThat(params.getValue("from")).isEqualTo(Timestamp.from(FROM));
        assertThat(params.getValue("to")).isEqualTo(Timestamp.from(TO));
        String bound = NamedParameterUtils.substituteNamedParameters(parsed, params);
        assertThat(bound).contains("a.started_at >= ?", "a.started_at < ?");
        if (tenant == null) {
            assertThat(params.hasValue("organizationId")).isFalse();
            assertThat(bound).doesNotContain("p.organization_id = ?");
        } else {
            assertThat(params.getValue("organizationId")).isEqualTo(tenant);
            assertThat(bound).contains("and p.organization_id = ?");
        }
        return bound;
    }
}
