package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.dto.AuditEventCursorResponse;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AuditCursorPaginationIntegrationTest
        extends AbstractAuditPostgresIntegrationTest {

    @Autowired
    private AuditEventCursorService cursorService;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void cursorPaginationHasNoDuplicatesAndPreservesTenantIsolation() {
        UUID organizationId =
                UUID.randomUUID();

        UUID foreignOrganizationId =
                UUID.randomUUID();

        UUID adminId =
                UUID.randomUUID();

        Instant sameCreatedAt =
                Instant.parse(
                        "2026-07-30T08:00:00Z"
                );

        Set<UUID> expected =
                new HashSet<>();

        for (int index = 0;
             index < 3;
             index++) {

            UUID id =
                    UUID.randomUUID();

            expected.add(id);

            insertUserUpdatedAuditEvent(
                    id,
                    adminId,
                    organizationId,
                    "admin@test.com",
                    "Admin",
                    organizationId,
                    Map.of(
                            "index",
                            index
                    ),
                    sameCreatedAt
            );
        }

        insertUserUpdatedAuditEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                foreignOrganizationId,
                "foreign@test.com",
                "Foreign",
                foreignOrganizationId,
                Map.of(
                        "foreign",
                        true
                ),
                sameCreatedAt
                        .plusSeconds(1)
        );

        SafeAiUserPrincipal admin =
                SafeAiUserPrincipal
                        .accessTokenPrincipal(
                                adminId,
                                organizationId,
                                "admin@test.com",
                                0L,
                                0L,
                                List.of(
                                        new SimpleGrantedAuthority(
                                                "ROLE_ADMIN"
                                        )
                                )
                        );

        AuditEventCursorResponse first =
                cursorService.findAll(
                        admin,
                        emptyFilter(),
                        null,
                        2
                );

        assertThat(
                first.items()
        ).hasSize(2);

        assertThat(
                first.hasNext()
        ).isTrue();

        assertThat(
                first.nextCursor()
        ).isNotBlank();

        AuditEventCursorResponse second =
                cursorService.findAll(
                        admin,
                        emptyFilter(),
                        first.nextCursor(),
                        2
                );

        assertThat(
                second.items()
        ).hasSize(1);

        assertThat(
                second.hasNext()
        ).isFalse();

        assertThat(
                second.nextCursor()
        ).isNull();

        List<UUID> allIds =
                java.util.stream.Stream
                        .concat(
                                first.items()
                                        .stream(),
                                second.items()
                                        .stream()
                        )
                        .map(
                                AuditEventResponse::id
                        )
                        .toList();

        assertThat(allIds)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(
                        expected
                );

        assertThat(
                java.util.stream.Stream
                        .concat(
                                first.items()
                                        .stream(),
                                second.items()
                                        .stream()
                        )
                        .map(
                                AuditEventResponse
                                        ::organizationId
                        )
                        .toList()
        ).containsOnly(
                organizationId
        );
    }

    @SuppressWarnings({
            "SqlResolve",
            "SqlNoDataSourceInspection"
    })
    private void insertUserUpdatedAuditEvent(
            UUID eventId,
            UUID actorUserId,
            UUID actorOrganizationId,
            String actorEmail,
            String actorDisplayName,
            UUID targetOrganizationId,
            Map<String, Object> details,
            Instant createdAt
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
                actorEmail,
                "actorEmail не должен быть null"
        );

        Objects.requireNonNull(
                actorDisplayName,
                "actorDisplayName не должен быть null"
        );

        Objects.requireNonNull(
                targetOrganizationId,
                "targetOrganizationId не должен быть null"
        );

        Objects.requireNonNull(
                details,
                "details не должен быть null"
        );

        Objects.requireNonNull(
                createdAt,
                "createdAt не должен быть null"
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
                actorEmail,
                actorDisplayName,
                targetOrganizationId,
                AuditEventType
                        .USER_UPDATED
                        .name(),
                toJson(details),
                new SqlParameterValue(
                        Types.TIMESTAMP_WITH_TIMEZONE,
                        createdAt.atOffset(
                                ZoneOffset.UTC
                        )
                )
        );
    }

    private AuditEventFilter emptyFilter() {
        return new AuditEventFilter(
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}