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
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Test
    void mapsAllImpactCounters() {
        when(
                jdbcTemplate.queryForObject(
                        anyString(),
                        any(SqlParameterSource.class),
                        ArgumentMatchers.<RowMapper<
                                OrganizationImpactQueryRepository
                                        .ImpactSnapshot
                                >>any()
                )
        ).thenAnswer(invocation -> {
            RowMapper<OrganizationImpactQueryRepository.ImpactSnapshot>
                    rowMapper = invocation.getArgument(2);

            ResultSet resultSet = mock(ResultSet.class);

            when(resultSet.getLong("enabled_users"))
                    .thenReturn(10L);
            when(resultSet.getLong("administrators"))
                    .thenReturn(2L);
            when(resultSet.getLong("active_refresh_sessions"))
                    .thenReturn(4L);
            when(resultSet.getLong("active_chat_operations"))
                    .thenReturn(1L);

            return rowMapper.mapRow(resultSet, 0);
        });

        OrganizationImpactQueryRepository.ImpactSnapshot result =
                new OrganizationImpactQueryRepository(
                        jdbcTemplate
                ).load(ORGANIZATION_ID);

        assertThat(result.enabledUsers()).isEqualTo(10L);
        assertThat(result.administrators()).isEqualTo(2L);
        assertThat(result.activeRefreshSessions()).isEqualTo(4L);
        assertThat(result.activeChatOperations()).isEqualTo(1L);
    }

    @Test
    void impactSnapshotRejectsNegativeCounters() {
        assertThatThrownBy(() ->
                new OrganizationImpactQueryRepository.ImpactSnapshot(
                        -1L,
                        0L,
                        0L,
                        0L
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enabledUsers");
    }
}
