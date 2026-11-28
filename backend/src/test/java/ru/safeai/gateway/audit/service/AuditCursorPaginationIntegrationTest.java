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
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные проверки курсорной пагинации неизменяемых событий аудита.
 *
 * <p>Используется настоящий PostgreSQL из тестовой инфраструктуры проекта.
 * Проверяются отсутствие пропусков и повторов, устойчивый порядок при
 * одинаковом времени событий, изоляция организаций и запреты доступа.
 * Проверка не подменяет Spring-контекст и не отключает Flyway.</p>
 */
@SpringBootTest(classes = ru.safeai.gateway.SafeaiBackendApplication.class)
@ActiveProfiles("test")
class AuditCursorPaginationIntegrationTest
        extends AbstractAuditPostgresIntegrationTest {

    private static final Instant FIXED_CREATED_AT =
            Instant.parse("2026-07-30T08:00:00Z");

    @Autowired
    private AuditEventCursorService cursorService;

    // Исключаем только фоновый запуск доставки; аудит читается через реальные сервисы.
    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void cursorPaginationHasNoDuplicatesAndPreservesTenantIsolation() {
        UUID organizationId = UUID.randomUUID();
        UUID foreignOrganizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Set<UUID> expected = new HashSet<>();

        // Одинаковое время намеренно проверяет вторичную сортировку по UUID.
        for (int index = 0; index < 3; index++) {
            UUID eventId = UUID.randomUUID();
            expected.add(eventId);
            insertUserUpdatedAuditEvent(
                    eventId, adminId, organizationId,
                    "admin@test.com", "Admin", organizationId,
                    Map.of("index", index), FIXED_CREATED_AT);
        }

        // Даже более новое событие чужой организации не должно попасть в выдачу.
        UUID foreignEventId = UUID.randomUUID();
        insertUserUpdatedAuditEvent(
                foreignEventId, UUID.randomUUID(), foreignOrganizationId,
                "foreign@test.com", "Foreign", foreignOrganizationId,
                Map.of("foreign", true), FIXED_CREATED_AT.plusSeconds(1));

        SafeAiUserPrincipal admin = principal(adminId, organizationId, "ROLE_ADMIN");
        AuditEventCursorResponse first = cursorService.findAll(
                admin, emptyFilter(), null, 2);

        assertThat(first.items()).hasSize(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();

        AuditEventCursorResponse second = cursorService.findAll(
                admin, emptyFilter(), first.nextCursor(), 2);

        assertThat(second.items()).hasSize(1);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.nextCursor()).isNull();

        List<AuditEventResponse> all = new ArrayList<>(first.items());
        all.addAll(second.items());

        assertThat(all)
                .extracting(AuditEventResponse::id)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(expected)
                .doesNotContain(foreignEventId);
        assertThat(all)
                .extracting(AuditEventResponse::organizationId)
                .containsOnly(organizationId);
        assertThat(all)
                .extracting(AuditEventResponse::userId)
                .containsOnly(adminId);
        assertThat(all)
                .extracting(AuditEventResponse::createdAt)
                .containsOnly(FIXED_CREATED_AT);
    }

    @Test
    void paginationAcrossManyPagesReturnsEveryEventExactlyOnce() {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Set<UUID> expected = new HashSet<>();

        // Неравномерное число событий проверяет неполную последнюю страницу.
        // Все события имеют одинаковое время: курсор не может опираться только на дату.
        for (int index = 0; index < 17; index++) {
            UUID eventId = UUID.randomUUID();
            expected.add(eventId);
            insertUserUpdatedAuditEvent(
                    eventId, adminId, organizationId,
                    "paged@test.com", "Paged Admin", organizationId,
                    Map.of("sequence", index), FIXED_CREATED_AT);
        }

        SafeAiUserPrincipal admin = principal(adminId, organizationId, "ROLE_ADMIN");
        Set<UUID> actual = new HashSet<>();
        String cursor = null;
        int pages = 0;

        do {
            AuditEventCursorResponse page = cursorService.findAll(
                    admin, emptyFilter(), cursor, 3);
            pages++;
            assertThat(pages).as("Защита от бесконечного повторения курсора")
                    .isLessThanOrEqualTo(7);
            assertThat(page.items()).isNotEmpty().hasSizeLessThanOrEqualTo(3);

            for (AuditEventResponse event : page.items()) {
                assertThat(event.organizationId()).isEqualTo(organizationId);
                assertThat(actual.add(event.id()))
                        .as("Повтор события при переходе между страницами: %s", event.id())
                        .isTrue();
            }

            if (page.hasNext()) {
                assertThat(page.nextCursor()).isNotBlank().isNotEqualTo(cursor);
                cursor = page.nextCursor();
            } else {
                assertThat(page.nextCursor()).isNull();
                break;
            }
        } while (true);

        assertThat(pages).isEqualTo(6);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void emptyTenantHasNoEventsAndNoContinuationCursor() {
        UUID organizationId = UUID.randomUUID();
        SafeAiUserPrincipal admin = principal(
                UUID.randomUUID(), organizationId, "ROLE_ADMIN");

        AuditEventCursorResponse response = cursorService.findAll(
                admin, emptyFilter(), null, 2);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void ordinaryUserCannotReadAuditThroughService() {
        UUID organizationId = UUID.randomUUID();
        SafeAiUserPrincipal user = principal(
                UUID.randomUUID(), organizationId, "ROLE_USER");

        // Проверяем сервисную авторизацию, а не только HTTP-аннотации контроллера.
        assertThatThrownBy(() -> cursorService.findAll(
                user, emptyFilter(), null, 2))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void tenantAdminCannotExplicitlyRequestAnotherOrganization() {
        UUID ownOrganization = UUID.randomUUID();
        UUID foreignOrganization = UUID.randomUUID();
        SafeAiUserPrincipal admin = principal(
                UUID.randomUUID(), ownOrganization, "ROLE_ADMIN");
        AuditEventFilter foreignFilter = new AuditEventFilter(
                null, null, null, null, null, foreignOrganization);

        assertThatThrownBy(() -> cursorService.findAll(
                admin, foreignFilter, null, 2))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void malformedCursorIsRejectedRatherThanRestartingPagination() {
        UUID organizationId = UUID.randomUUID();
        SafeAiUserPrincipal admin = principal(
                UUID.randomUUID(), organizationId, "ROLE_ADMIN");

        // Невалидный курсор не должен молча превращаться в первую страницу.
        assertThatThrownBy(() -> cursorService.findAll(
                admin, emptyFilter(), "invalid-cursor!", 2))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * Добавляет событие напрямую в конечную таблицу аудита.
     * Снимок автора не зависит от наличия активной строки пользователя.
     */
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
        Objects.requireNonNull(eventId, "eventId не должен быть null");
        Objects.requireNonNull(actorUserId, "actorUserId не должен быть null");
        Objects.requireNonNull(actorOrganizationId,
                "actorOrganizationId не должен быть null");
        Objects.requireNonNull(actorEmail, "actorEmail не должен быть null");
        Objects.requireNonNull(actorDisplayName,
                "actorDisplayName не должен быть null");
        Objects.requireNonNull(targetOrganizationId,
                "targetOrganizationId не должен быть null");
        Objects.requireNonNull(details, "details не должен быть null");
        Objects.requireNonNull(createdAt, "createdAt не должен быть null");

        // V68 хранит неизменяемый снимок автора; старого столбца user_id здесь нет.
        jdbcTemplate.update("""
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
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?
                )
                """,
                eventId,
                actorUserId,
                actorOrganizationId,
                actorEmail,
                actorDisplayName,
                targetOrganizationId,
                AuditEventType.USER_UPDATED.name(),
                toJson(details),
                new SqlParameterValue(
                        Types.TIMESTAMP_WITH_TIMEZONE,
                        createdAt.atOffset(ZoneOffset.UTC))
        );
    }

    private SafeAiUserPrincipal principal(
            UUID userId, UUID organizationId, String role
    ) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                userId,
                organizationId,
                0L,
                0L,
                List.of(new SimpleGrantedAuthority(role)));
    }

    private AuditEventFilter emptyFilter() {
        return new AuditEventFilter(null, null, null, null, null, null);
    }
}
