package ru.safeai.gateway.audit.repository;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AuditEmailPrefixIndexIntegrationTest
        extends AbstractAuditPostgresIntegrationTest {

    private static final int NOISE_EVENT_COUNT = 2_000;

    private static final Instant BASE_CREATED_AT =
            Instant.parse(
                    "2026-07-30T08:00:00Z"
            );

    private static final String MATCHING_EMAIL =
            "prefix-admin@test.com";

    private static final String MATCHING_DISPLAY_NAME =
            "Admin";

    private static final String EMAIL_PREFIX_PATTERN =
            "prefix%";

    private static final String EXPECTED_INDEX_NAME =
            "idx_audit_events_org_actor_email_pattern_created_id";

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void canonicalEmailPrefixQueryCanUsePatternOpsIndex() {
        UUID organizationId = UUID.randomUUID();

        insertMatchingEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                organizationId,
                organizationId,
                Map.of()
        );

        insertNoiseEvents(
                organizationId,
                BASE_CREATED_AT.plusSeconds(1)
        );

        jdbcTemplate.execute(
                "analyze public.audit_events"
        );

        String plan =
                explainCanonicalEmailPrefixQuery(
                        organizationId
                );

        assertThat(plan)
                .isNotNull()
                .contains(EXPECTED_INDEX_NAME);
    }

    private String explainCanonicalEmailPrefixQuery(
            UUID organizationId
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        TransactionTemplate transaction =
                new TransactionTemplate(
                        transactionManager
                );

        return transaction.execute(status -> {
            jdbcTemplate.execute(
                    "set local enable_seqscan = off"
            );

            List<String> lines = jdbcTemplate.query(
                    """
                    explain (costs off)
                    select id,
                           actor_email,
                           created_at
                    from public.audit_events
                    where organization_id = ?
                      and actor_email like ? escape '\\'
                    """,
                    (resultSet, rowNumber) ->
                            resultSet.getString(1),
                    organizationId,
                    EMAIL_PREFIX_PATTERN
            );

            return String.join(
                    System.lineSeparator(),
                    lines
            );
        });
    }

    @SuppressWarnings({
            "SqlResolve",
            "SqlNoDataSourceInspection"
    })
    private void insertMatchingEvent(
            UUID eventId,
            UUID actorUserId,
            UUID actorOrganizationId,
            UUID targetOrganizationId,
            Map<String, Object> details
    ) {
        Objects.requireNonNull(
                eventId,
                "eventId не должен быть null"
        );

        Objects.requireNonNull(
                actorUserId,
                "actorUserId не должен быть null"
        );

        Objects.requireNonNull(
                actorOrganizationId,
                "actorOrganizationId не должен быть null"
        );

        Objects.requireNonNull(
                targetOrganizationId,
                "targetOrganizationId не должен быть null"
        );

        Objects.requireNonNull(
                details,
                "details не должен быть null"
        );

        jdbcTemplate.update(
                """
                insert into public.audit_events (
                    id,
                    user_id,
                    actor_user_id,
                    actor_organization_id,
                    actor_email,
                    actor_display_name,
                    organization_id,
                    event_type,
                    details,
                    created_at
                )
                values (
                    ?,
                    null,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    cast(? as jsonb),
                    ?
                )
                """,
                eventId,
                actorUserId,
                actorOrganizationId,
                MATCHING_EMAIL,
                MATCHING_DISPLAY_NAME,
                targetOrganizationId,
                AuditEventType.USER_UPDATED.name(),
                toJson(details),
                timestampParameter(BASE_CREATED_AT)
        );
    }

    @SuppressWarnings({
            "SqlResolve",
            "SqlNoDataSourceInspection"
    })
    private void insertNoiseEvents(
            UUID organizationId,
            Instant firstCreatedAt
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        Objects.requireNonNull(
                firstCreatedAt,
                "firstCreatedAt не должен быть null"
        );

        jdbcTemplate.update(
                """
                insert into public.audit_events (
                    id,
                    user_id,
                    actor_user_id,
                    actor_organization_id,
                    actor_email,
                    actor_display_name,
                    organization_id,
                    event_type,
                    details,
                    created_at
                )
                select
                    gen_random_uuid(),
                    null,
                    gen_random_uuid(),
                    ?,
                    'noise-'
                        || lpad(
                            series_number::text,
                            6,
                            '0'
                        )
                        || '@test.com',
                    'Noise Actor',
                    ?,
                    ?,
                    '{}'::jsonb,
                    ? + make_interval(
                        secs => series_number
                    )
                from generate_series(
                    1,
                    ?
                ) as series_number
                """,
                organizationId,
                organizationId,
                AuditEventType.USER_UPDATED.name(),
                timestampParameter(firstCreatedAt),
                NOISE_EVENT_COUNT
        );
    }

    private SqlParameterValue timestampParameter(
            Instant instant
    ) {
        Objects.requireNonNull(
                instant,
                "instant не должен быть null"
        );

        return new SqlParameterValue(
                Types.TIMESTAMP_WITH_TIMEZONE,
                instant.atOffset(
                        ZoneOffset.UTC
                )
        );
    }
}