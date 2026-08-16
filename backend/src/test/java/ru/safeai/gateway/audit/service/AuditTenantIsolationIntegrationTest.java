package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AuditTenantIsolationIntegrationTest
        extends AbstractAuditPostgresIntegrationTest {

    @Autowired
    private AuditEventQueryService queryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void adminReadsOnlyEventsOfOwnOrganization() {
        Fixture fixture =
                fixture();

        Page<AuditEventResponse> page =
                queryService.findAll(
                        fixture.adminPrincipal(),
                        emptyFilter(),
                        PageRequest.of(
                                0,
                                50
                        )
                );

        assertThat(
                page.getContent()
        )
                .extracting(
                        AuditEventResponse
                                ::organizationId
                )
                .containsOnly(
                        fixture
                                .adminOrganizationId()
                );

        assertThat(
                page.getContent()
        )
                .extracting(
                        AuditEventResponse::id
                )
                .containsExactly(
                        fixture.adminEventId()
                );
    }

    @Test
    void adminCannotBypassTenantIsolationThroughForeignUserId() {
        Fixture fixture =
                fixture();

        Page<AuditEventResponse> page =
                queryService.findByUserId(
                        fixture.foreignActorId(),
                        fixture.adminPrincipal(),
                        PageRequest.of(
                                0,
                                50
                        )
                );

        assertThat(
                page.getContent()
        ).isEmpty();
    }

    @Test
    void adminCannotBypassTenantIsolationThroughOrganizationFilter() {
        Fixture fixture =
                fixture();

        AuditEventFilter filter =
                new AuditEventFilter(
                        null,
                        null,
                        null,
                        null,
                        null,
                        fixture
                                .foreignOrganizationId()
                );

        assertThatThrownBy(() ->
                queryService.findAll(
                        fixture.adminPrincipal(),
                        filter,
                        PageRequest.of(
                                0,
                                50
                        )
                )
        )
                .isInstanceOf(
                        ForbiddenOperationException.class
                )
                .hasMessageContaining(
                        "Нельзя фильтровать аудит "
                                + "другой организации"
                );
    }

    @Test
    void superAdminCanFilterAnyTargetOrganization() {
        Fixture fixture =
                fixture();

        AuditEventFilter filter =
                new AuditEventFilter(
                        null,
                        null,
                        null,
                        null,
                        null,
                        fixture
                                .foreignOrganizationId()
                );

        Page<AuditEventResponse> page =
                queryService.findAll(
                        fixture
                                .superAdminPrincipal(),
                        filter,
                        PageRequest.of(
                                0,
                                50
                        )
                );

        assertThat(
                page.getContent()
        )
                .extracting(
                        AuditEventResponse::id
                )
                .containsExactly(
                        fixture.foreignEventId()
                );

        assertThat(
                page.getContent()
        )
                .extracting(
                        AuditEventResponse
                                ::organizationId
                )
                .containsOnly(
                        fixture
                                .foreignOrganizationId()
                );
    }

    @Test
    void adminEmailPrefixFilterCannotExposeForeignTenant() {
        Fixture fixture =
                fixture();

        AuditEventFilter filter =
                new AuditEventFilter(
                        null,
                        "shared-prefix",
                        null,
                        null,
                        null,
                        null
                );

        Page<AuditEventResponse> page =
                queryService.findAll(
                        fixture.adminPrincipal(),
                        filter,
                        PageRequest.of(
                                0,
                                50
                        )
                );

        assertThat(
                page.getContent()
        )
                .extracting(
                        AuditEventResponse::id
                )
                .containsExactly(
                        fixture.adminEventId()
                );
    }

    private Fixture fixture() {
        UUID adminOrganizationId =
                UUID.randomUUID();

        UUID foreignOrganizationId =
                UUID.randomUUID();

        UUID platformOrganizationId =
                UUID.randomUUID();

        UUID adminActorId =
                UUID.randomUUID();

        UUID foreignActorId =
                UUID.randomUUID();

        UUID superAdminId =
                UUID.randomUUID();

        UUID adminEventId =
                UUID.randomUUID();

        UUID foreignEventId =
                UUID.randomUUID();

        Instant createdAt =
                Instant.parse(
                        "2026-07-30T08:00:00Z"
                );

        insertAuditEventDirect(
                adminEventId,
                adminActorId,
                adminOrganizationId,
                "shared-prefix-admin@test.com",
                "Admin",
                adminOrganizationId,
                Map.of(
                        "tenant",
                        "admin"
                ),
                createdAt
        );

        insertAuditEventDirect(
                foreignEventId,
                foreignActorId,
                foreignOrganizationId,
                "shared-prefix-foreign@test.com",
                "Foreign Admin",
                foreignOrganizationId,
                Map.of(
                        "tenant",
                        "foreign"
                ),
                createdAt.plusSeconds(1)
        );

        return new Fixture(
                adminOrganizationId,
                foreignOrganizationId,
                platformOrganizationId,
                adminActorId,
                foreignActorId,
                superAdminId,
                adminEventId,
                foreignEventId
        );
    }

    @SuppressWarnings({
            "SqlResolve",
            "SqlNoDataSourceInspection"
    })
    private void insertAuditEventDirect(
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

    private record Fixture(
            UUID adminOrganizationId,
            UUID foreignOrganizationId,
            UUID platformOrganizationId,
            UUID adminActorId,
            UUID foreignActorId,
            UUID superAdminId,
            UUID adminEventId,
            UUID foreignEventId
    ) {

        SafeAiUserPrincipal adminPrincipal() {
            return SafeAiUserPrincipal
                    .accessTokenPrincipal(
                            adminActorId,
                            adminOrganizationId,
                            0L,
                            0L,
                            List.of(
                                    new SimpleGrantedAuthority(
                                            "ROLE_ADMIN"
                                    )
                            )
                    );
        }

        SafeAiUserPrincipal superAdminPrincipal() {
            return SafeAiUserPrincipal
                    .accessTokenPrincipal(
                            superAdminId,
                            platformOrganizationId,
                            0L,
                            0L,
                            List.of(
                                    new SimpleGrantedAuthority(
                                            "ROLE_SUPER_ADMIN"
                                    )
                            )
                    );
        }
    }
}