package ru.safeai.gateway.organization.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationImpactQueryRepositoryTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Test
    void mapsAllImpactCountersIncludingPhysicalAttempts() {
        when(jdbcTemplate.queryForObject(
                anyString(),
                any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<OrganizationImpactQueryRepository.ImpactSnapshot>>any()
        )).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertThat(sql).contains("outcome = 'STARTED'");
            assertThat(sql).contains("'AMBIGUOUS', 'PROTOCOL_REJECTED'");
            assertThat(sql).contains("issued_organization_auth_version");
            assertThat(sql).contains("issued_token_version");
            RowMapper<OrganizationImpactQueryRepository.ImpactSnapshot> mapper =
                    invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("enabled_users")).thenReturn(10L);
            when(rs.getLong("administrators")).thenReturn(2L);
            when(rs.getLong("active_refresh_sessions")).thenReturn(4L);
            when(rs.getLong("active_chat_operations")).thenReturn(1L);
            when(rs.getLong("started_physical_attempts")).thenReturn(3L);
            when(rs.getLong("uncertain_physical_attempts")).thenReturn(5L);
            return mapper.mapRow(rs, 0);
        });

        OrganizationImpactQueryRepository.ImpactSnapshot result =
                new OrganizationImpactQueryRepository(jdbcTemplate).load(ORGANIZATION_ID);

        assertThat(result.enabledUsers()).isEqualTo(10L);
        assertThat(result.administrators()).isEqualTo(2L);
        assertThat(result.activeRefreshSessions()).isEqualTo(4L);
        assertThat(result.activeChatOperations()).isEqualTo(1L);
        assertThat(result.startedPhysicalAttempts()).isEqualTo(3L);
        assertThat(result.uncertainPhysicalAttempts()).isEqualTo(5L);
    }

    @Test
    void impactSnapshotRejectsNegativeCounters() {
        assertThatThrownBy(() ->
                new OrganizationImpactQueryRepository.ImpactSnapshot(-1L, 0L, 0L, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enabledUsers");
        assertThatThrownBy(() ->
                new OrganizationImpactQueryRepository.ImpactSnapshot(0L, 0L, 0L, 0L, -1L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startedPhysicalAttempts");
        assertThatThrownBy(() ->
                new OrganizationImpactQueryRepository.ImpactSnapshot(0L, 0L, 0L, 0L, 0L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uncertainPhysicalAttempts");
    }

    @Test
    void legacyShapeStillAvailableForCallers() {
        var value = new OrganizationImpactQueryRepository.ImpactSnapshot(2L, 1L, 3L, 4L);
        assertThat(value.startedPhysicalAttempts()).isZero();
        assertThat(value.uncertainPhysicalAttempts()).isZero();
    }
}
