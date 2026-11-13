package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;
import ru.safeai.gateway.common.exception.BadRequestException;
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

/**
 * Проверяет разграничение доступа к событиям аудита между организациями.
 *
 * <p>Тест использует настоящий PostgreSQL и реальные правила запросов аудита.
 * Отсутствие чужих событий в результате проверяется отдельно от отказа
 * в доступе при явном указании чужой организации.</p>
 *
 * <p>Планировщик доставки подменён только для исключения фоновой конкуренции:
 * сервис авторизации, репозиторий и PostgreSQL остаются настоящими.</p>
 */
@SpringBootTest(
        classes = ru.safeai.gateway.SafeaiBackendApplication.class
)
@ActiveProfiles("test")
class AuditTenantIsolationIntegrationTest
        extends AbstractAuditPostgresIntegrationTest {

    @Autowired
    private AuditEventQueryService queryService;

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
                        fixture.foreignOrganizationId()
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
                        fixture.foreignOrganizationId()
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
    void superAdminCanReadGlobalAuditAcrossOrganizations() {
        Fixture fixture =
                fixture();

        Page<AuditEventResponse> page =
                queryService.findAll(
                        fixture.superAdminPrincipal(),
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
                        AuditEventResponse::id
                )
                .containsExactly(
                        fixture.foreignEventId(),
                        fixture.adminEventId()
                );

        assertThat(
                page.getContent()
        )
                .extracting(
                        AuditEventResponse::organizationId
                )
                .containsExactly(
                        fixture.foreignOrganizationId(),
                        fixture.adminOrganizationId()
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

    @Test
    void ordinaryUserCannotReadAuditEvenInOwnOrganization() {
        // Прямая служебная операция не должна обходить роль ADMIN/SUPER_ADMIN.
        Fixture fixture = fixture();
        SafeAiUserPrincipal ordinaryUser = principal(
                fixture.adminActorId(), fixture.adminOrganizationId(), "ROLE_USER");

        assertThatThrownBy(() -> queryService.findAll(
                ordinaryUser, emptyFilter(), PageRequest.of(0, 50)))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("ADMIN");

        assertThatThrownBy(() -> queryService.findByUserId(
                fixture.adminActorId(), ordinaryUser, PageRequest.of(0, 50)))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void unknownAuditAuthorityCannotGrantAuditReadAccess() {
        // Неизвестную роль необходимо отклонить при создании principal.
        // Сервис аудита не должен получать principal с неподдерживаемой ролью:
        // SecurityIdentityValidator использует строгий SystemRole.parse().
        Fixture fixture = fixture();
        for (String invalidRole : List.of(
                "ROLE_AUDIT_VIEWER",
                "ROLE_ADMINISTRATOR",
                "ROLE_SUPER_ADMIN_EXTRA",
                "ROLE_")) {
            assertThatThrownBy(() -> principal(
                    fixture.adminActorId(), fixture.adminOrganizationId(), invalidRole))
                    .as("Неподдерживаемая роль %s должна отвергаться до доступа к аудиту", invalidRole)
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unknown role: " + invalidRole);
        }

        // Дополнительно проверяем обе сервисные точки входа с реальной
        // минимально привилегированной системной ролью.
        SafeAiUserPrincipal ordinaryUser = principal(
                fixture.adminActorId(), fixture.adminOrganizationId(), "ROLE_USER");
        assertThatThrownBy(() -> queryService.findAll(
                ordinaryUser, emptyFilter(), PageRequest.of(0, 50)))
                .isExactlyInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("ADMIN");
        assertThatThrownBy(() -> queryService.findByUserId(
                fixture.adminActorId(), ordinaryUser, PageRequest.of(0, 50)))
                .isExactlyInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void principalRejectsMultipleRolesBeforeAuditAccess() {
        // Защита principal не должна допускать смешивание ROLE_USER и ROLE_ADMIN
        // в одном access token: поддерживается ровно одна системная роль.
        Fixture fixture = fixture();
        assertThatThrownBy(() -> SafeAiUserPrincipal.accessTokenPrincipal(
                fixture.adminActorId(), fixture.adminOrganizationId(), 0L, 0L,
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Должна быть ровно одна системная роль");
    }

    @Test
    void sameActorInDifferentTenantsDoesNotBypassOrganizationScope() {
        // Даже совпадающий actor_user_id не должен объединять события организаций.
        Fixture fixture = fixture();
        UUID foreignEventId = UUID.randomUUID();
        insertAuditEventDirect(
                foreignEventId,
                fixture.adminActorId(),
                fixture.adminOrganizationId(),
                "shared-prefix-admin@test.com",
                "Admin",
                fixture.foreignOrganizationId(),
                Map.of("scenario", "same-actor-foreign-tenant"),
                Instant.parse("2026-07-30T08:00:02Z"));

        Page<AuditEventResponse> adminResults = queryService.findByUserId(
                fixture.adminActorId(), fixture.adminPrincipal(), PageRequest.of(0, 50));
        assertThat(adminResults.getContent())
                .extracting(AuditEventResponse::id)
                .containsExactly(fixture.adminEventId());
        assertThat(adminResults.getTotalElements()).isEqualTo(1L);

        Page<AuditEventResponse> platformResults = queryService.findByUserId(
                fixture.adminActorId(), fixture.superAdminPrincipal(), PageRequest.of(0, 50));
        assertThat(platformResults.getContent())
                .extracting(AuditEventResponse::id)
                .containsExactly(foreignEventId, fixture.adminEventId());
    }

    @Test
    void emailPrefixFilterTreatsWildcardsAsLiteralCharacters() {
        // Пользовательский префикс с '%' или '_' не должен расширять SQL LIKE.
        Fixture fixture = fixture();
        for (String rawPrefix : List.of("shared%", "shared_")) {
            AuditEventFilter filter = new AuditEventFilter(
                    null, rawPrefix, null, null, null, null);
            Page<AuditEventResponse> page = queryService.findAll(
                    fixture.adminPrincipal(), filter, PageRequest.of(0, 50));
            assertThat(page.getContent())
                    .as("Префикс %s должен экранироваться как литерал", rawPrefix)
                    .isEmpty();
        }
    }

    @Test
    void targetOrganizationDeterminesScopeNotActorOrganization() {
        // Доказательство принадлежит организации события, а не организации автора.
        Fixture fixture = fixture();
        UUID crossOrganizationEventId = UUID.randomUUID();
        insertAuditEventDirect(
                crossOrganizationEventId,
                fixture.foreignActorId(),
                fixture.foreignOrganizationId(),
                "external-actor@test.com",
                "External Actor",
                fixture.adminOrganizationId(),
                Map.of("scenario", "foreign-actor-own-target"),
                Instant.parse("2026-07-30T08:00:03Z"));

        Page<AuditEventResponse> adminResults = queryService.findAll(
                fixture.adminPrincipal(), emptyFilter(), PageRequest.of(0, 50));
        assertThat(adminResults.getContent())
                .extracting(AuditEventResponse::id)
                .containsExactly(crossOrganizationEventId, fixture.adminEventId());
        assertThat(adminResults.getContent())
                .extracting(AuditEventResponse::organizationId)
                .containsOnly(fixture.adminOrganizationId());
    }

    @Test
    void adminMayExplicitlyFilterHisOwnOrganization() {
        // Явное указание своей организации разрешено и не раскрывает чужие события.
        Fixture fixture = fixture();
        AuditEventFilter filter = new AuditEventFilter(
                null, null, null, null, null, fixture.adminOrganizationId());

        Page<AuditEventResponse> result = queryService.findAll(
                fixture.adminPrincipal(), filter, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent())
                .extracting(AuditEventResponse::id)
                .containsExactly(fixture.adminEventId());
    }

    @Test
    void adminCannotUsePaginationToReadEventsFromAnotherTenant() {
        // В БД существуют два события, но администратора касается только одно.
        // Проверяем отдельно содержимое первой страницы и пустоту следующих:
        // containsOnly() нельзя применять к пустой странице, так как оно
        // дополнительно требует присутствия указанного элемента.
        Fixture fixture = fixture();
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            Page<AuditEventResponse> page = queryService.findAll(
                    fixture.adminPrincipal(), emptyFilter(),
                    PageRequest.of(pageNumber, 1));

            assertThat(page.getNumber()).isEqualTo(pageNumber);
            assertThat(page.getSize()).isEqualTo(1);
            assertThat(page.getTotalElements())
                    .as("Число доступных событий не должно раскрывать чужую организацию")
                    .isEqualTo(1L);
            assertThat(page.getTotalPages()).isEqualTo(1);

            // Даже при пустом результате не допускается появление чужого события.
            assertThat(page.getContent()).allSatisfy(event -> {
                assertThat(event.organizationId())
                        .isEqualTo(fixture.adminOrganizationId());
                assertThat(event.id()).isNotEqualTo(fixture.foreignEventId());
            });

            if (pageNumber == 0) {
                assertThat(page.getContent())
                        .extracting(AuditEventResponse::id)
                        .containsExactly(fixture.adminEventId());
                assertThat(page.hasNext()).isFalse();
            } else {
                assertThat(page.getContent())
                        .as("Страница %s после единственного доступного события пуста", pageNumber)
                        .isEmpty();
                assertThat(page.hasContent()).isFalse();
                assertThat(page.hasNext()).isFalse();
            }
        }
    }

    @Test
    void arbitrarySortPropertyIsRejectedBeforeDatabaseAccess() {
        // Недоверенное поле сортировки не должно передаваться в JPA/SQL.
        Fixture fixture = fixture();
        assertThatThrownBy(() -> queryService.findAll(
                fixture.adminPrincipal(), emptyFilter(),
                PageRequest.of(0, 10, Sort.by("actorEmail"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Сортировка по полю не разрешена");
    }

    /**
     * Создаёт принципал с указанной ролью для негативных проверок.
     * Корректная ROLE_USER должна быть отвергнута политикой аудита, а
     * неразрешённая ROLE_AUDIT_VIEWER — валидатором principal ещё до обращения
     * к сервису. Тесты явно проверяют обе независимые границы защиты.
     */
    private static SafeAiUserPrincipal principal(
            UUID userId, UUID organizationId, String authority) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                userId, organizationId, 0L, 0L,
                List.of(new SimpleGrantedAuthority(authority)));
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
        // actor_* хранит исторический снимок автора и не требует живой строки users.
        // organization_id всегда обозначает организацию самого события.
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
