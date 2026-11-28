package ru.safeai.gateway.audit.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Общие инструменты интеграционных тестов аудита на настоящем PostgreSQL.
 *
 * <p>Очистка выполняется только в тестовой базе. Транзакции, внешние ключи,
 * неизменяемость событий и работа outbox не отключаются.</p>
 */
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
public abstract class AbstractAuditPostgresIntegrationTest
        extends AbstractPostgresIntegrationTest {

    private static final String TEST_PASSWORD_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoO5xQ2T8n9lS7XrD8r/1JQm7V7XvM2l6K";

    private final JsonMapper jsonMapper =
            JsonMapper.builder()
                    .findAndAddModules()
                    .build();

    @BeforeEach
    protected void cleanAuditStorage() {
        /*
         * Изоляция тестов: очищаются только таблицы аудита в тестовой БД.
         * После V70 таблица связей с пользователями зависит от audit_events,
         * поэтому все три таблицы очищаются одним TRUNCATE.
         * CASCADE намеренно отсутствует: затрагивать другие таблицы нельзя.
         */
        jdbcTemplate.execute(
                """
                truncate table
                    public.audit_event_user_links,
                    public.audit_outbox,
                    public.audit_events
                """
        );
    }

    protected void insertTestUser(
            UUID userId,
            UUID organizationId,
            String email,
            String fullName,
            String roleName
    ) {
        Object roleId = jdbcTemplate.queryForObject(
                """
                select id
                from public.roles
                where name = ?
                """,
                (resultSet, rowNumber) ->
                        resultSet.getObject(1),
                roleName
        );

        if (roleId == null) {
            throw new IllegalStateException(
                    "Не найдена роль для тестового пользователя: " + roleName
            );
        }

        TransactionTemplate transaction =
                new TransactionTemplate(
                        transactionManager
                );

        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    insert into public.users (
                        id,
                        organization_id,
                        email,
                        password_hash,
                        full_name,
                        enabled,
                        token_version,
                        version,
                        created_at,
                        updated_at
                    )
                    values (
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        true,
                        0,
                        0,
                        current_timestamp,
                        current_timestamp
                    )
                    """,
                    userId,
                    organizationId,
                    email,
                    TEST_PASSWORD_HASH,
                    fullName
            );

            jdbcTemplate.update(
                    """
                    insert into public.user_roles (
                        user_id,
                        role_id
                    )
                    values (?, ?)
                    """,
                    userId,
                    roleId
            );
        });
    }

    protected void deleteTestUser(
            UUID userId
    ) {
        TransactionTemplate transaction =
                new TransactionTemplate(
                        transactionManager
                );

        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    delete from public.user_roles
                    where user_id = ?
                    """,
                    userId
            );

            jdbcTemplate.update(
                    """
                    delete from public.users
                    where id = ?
                    """,
                    userId
            );
        });
    }

    protected int countAuditOutbox() {
        return queryInt(
                "select count(*) from public.audit_outbox"
        );
    }

    protected int countAuditEvents() {
        return queryInt(
                "select count(*) from public.audit_events"
        );
    }

    @SuppressWarnings("SqlSourceToSinkFlow")
    protected int queryInt(
            String sql,
            Object... args
    ) {
        Integer value = jdbcTemplate.queryForObject(
                sql,
                Integer.class,
                args
        );

        return Objects.requireNonNull(value,
                "PostgreSQL вернул null вместо обязательного целого результата");
    }

    @SuppressWarnings("SqlSourceToSinkFlow")
    protected String queryString(
            String sql,
            Object... args
    ) {
        return jdbcTemplate.queryForObject(
                sql,
                String.class,
                args
        );
    }

    /**
     * Возвращает ID единственного намерения аудита заданной организации.
     *
     * <p>SQL фиксирован и параметризован: тесты не передают произвольную строку
     * запроса. Отсутствие или несколько записей считаются ошибкой фикстуры,
     * а не успешным получением пустого идентификатора.</p>
     */
    protected UUID auditOutboxIdForOrganization(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId не должен быть null");
        UUID id = jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM public.audit_outbox
                WHERE organization_id = ?
                """,
                UUID.class,
                organizationId
        );
        return Objects.requireNonNull(id, "PostgreSQL вернул null вместо ID audit_outbox");
    }

    protected String toJson(
            Map<String, Object> value
    ) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "Не удалось сериализовать JSON тестовых данных",
                    exception
            );
        }
    }
}
