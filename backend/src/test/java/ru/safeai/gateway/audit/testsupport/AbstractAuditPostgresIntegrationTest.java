package ru.safeai.gateway.audit.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

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
         * Test-only cleanup. TRUNCATE avoids the misleading IntelliJ
         * "DELETE without WHERE" inspection and is faster for integration
         * test isolation.
         */
        jdbcTemplate.execute(
                """
                truncate table
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
                    "Role not found: " + roleName
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

        return value == null ? 0 : value;
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

    @SuppressWarnings("SqlSourceToSinkFlow")
    protected UUID queryUuid(
            String sql,
            Object... args
    ) {
        return jdbcTemplate.queryForObject(
                sql,
                UUID.class,
                args
        );
    }

    protected String toJson(
            Map<String, Object> value
    ) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "Unable to serialize test JSON",
                    exception
            );
        }
    }
}