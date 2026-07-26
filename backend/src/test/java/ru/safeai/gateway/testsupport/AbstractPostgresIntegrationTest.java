package ru.safeai.gateway.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
public abstract class AbstractPostgresIntegrationTest {

    public static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID ADMIN_ROLE_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID USER_ROLE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID SUPER_ADMIN_ROLE_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    /**
     * Один PostgreSQL-контейнер запускается на весь JVM-процесс
     * выполнения интеграционных тестов.
     */
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("safeai_test")
                    .withUsername("safeai")
                    .withPassword("safeai_password");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () ->
                "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () ->
                "classpath:db/migration");
        registry.add(
                "spring.flyway.postgresql.transactional-lock",
                () -> "false"
        );

        registry.add("app.security.jwt.secret", () ->
                "safeai-test-secret-key-with-at-least-thirty-two-bytes-123456");
        registry.add("app.security.jwt.issuer", () ->
                "http://safeai-test");
        registry.add("app.security.jwt.audience", () ->
                "safeai-test-api");

        registry.add("safeai.ai.provider", () -> "mock");
        registry.add("safeai.security.user-status-cache.enabled", () ->
                "false");
        registry.add("safeai.security.user-status-cache.ttl", () ->
                "60s");
        registry.add("safeai.security.user-status-cache.key-prefix", () ->
                "safeai:test:user-status:");
        registry.add(
                "safeai.user-management.permanent-deletion-retention",
                () -> "0s"
        );
        registry.add("safeai.audit.outbox.batch-size", () -> "100");
        registry.add("safeai.audit.outbox.poll-delay-ms", () ->
                "3600000");
        registry.add("safeai.audit.retention.enabled", () -> "false");
        registry.add("safeai.auth.cookies.secure", () -> "false");
        registry.add("safeai.auth.cookies.same-site", () -> "Lax");
        registry.add("safeai.rate-limit.login.enabled", () -> "false");
        registry.add("safeai.rate-limit.ai-messages.enabled", () ->
                "false");
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                truncate table
                    public.audit_outbox,
                    public.audit_events,
                    public.refresh_tokens,
                    public.chat_messages,
                    public.chat_sessions,
                    public.usage_daily_user_model_rollups,
                    public.usage_daily_org_model_rollups,
                    public.user_ai_quotas,
                    public.organization_ai_quotas,
                    public.user_roles,
                    public.users,
                    public.organizations
                cascade
                """);

        insertOrganization(
                PLATFORM_ORGANIZATION_ID,
                "SafeAI Platform",
                true
        );
    }

    @SuppressWarnings("SameParameterValue")
    protected void insertOrganization(
            UUID id,
            String name,
            boolean enabled
    ) {
        jdbcTemplate.update("""
                        insert into public.organizations (
                            id,
                            name,
                            enabled,
                            created_at,
                            updated_at,
                            version
                        ) values (?, ?, ?, current_timestamp, current_timestamp, 0)
                        """,
                id,
                name,
                enabled
        );
    }

    protected void insertUser(
            UUID id,
            UUID organizationId,
            String email,
            boolean enabled,
            String role,
            Instant createdAt
    ) {
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            Instant disabledAt = enabled
                    ? null
                    : createdAt.minus(Duration.ofDays(1));

            jdbcTemplate.update("""
                            insert into public.users (
                                id,
                                organization_id,
                                email,
                                password_hash,
                                full_name,
                                enabled,
                                disabled_at,
                                created_at,
                                updated_at,
                                token_version,
                                version
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)
                            """,
                    id,
                    organizationId,
                    email,
                    "encoded-password",
                    "Test User " + id,
                    enabled,
                    timestamp(disabledAt),
                    timestamp(createdAt),
                    timestamp(createdAt)
            );

            jdbcTemplate.update("""
                            insert into public.user_roles (user_id, role_id)
                            values (?, ?)
                            """,
                    id,
                    roleId(role)
            );
        });
    }

    @SuppressWarnings("SameParameterValue")
    protected void insertActiveRefreshToken(
            UUID userId,
            long issuedTokenVersion
    ) {
        Instant createdAt =
                Instant.now().minus(
                        Duration.ofHours(1)
                );

        Instant expiresAt =
                Instant.now().plus(
                        Duration.ofDays(1)
                );

        Instant familyExpiresAt =
                Instant.now().plus(
                        Duration.ofDays(30)
                );

        Long issuedOrganizationAuthVersion =
                jdbcTemplate.queryForObject("""
                                select organization.auth_version
                                from public.users app_user
                                join public.organizations organization
                                  on organization.id =
                                     app_user.organization_id
                                where app_user.id = ?
                                """,
                        Long.class,
                        userId
                );

        if (issuedOrganizationAuthVersion == null) {
            throw new IllegalStateException(
                    "Не удалось определить "
                            + "organization auth version "
                            + "для пользователя " + userId
            );
        }

        jdbcTemplate.update("""
                        insert into public.refresh_tokens (
                            id,
                            user_id,
                            token_hash,
                            token_family_id,
                            replaced_by_token_id,
                            expires_at,
                            revoked_at,
                            last_used_at,
                            created_at,
                            created_by_ip,
                            user_agent,
                            issued_token_version,
                            issued_organization_auth_version,
                            family_created_at,
                            family_expires_at,
                            revocation_reason
                        ) values (
                            ?, ?, ?, ?, null,
                            ?, null, null,
                            ?, ?, ?,
                            ?, ?,
                            ?, ?,
                            null
                        )
                        """,
                UUID.randomUUID(),
                userId,
                randomSha256Hex(),
                UUID.randomUUID(),
                timestamp(expiresAt),
                timestamp(createdAt),
                "127.0.0.1",
                "integration-test",
                issuedTokenVersion,
                issuedOrganizationAuthVersion,
                timestamp(createdAt),
                timestamp(familyExpiresAt)
        );
    }

    protected void insertChatSession(
            UUID sessionId,
            UUID userId,
            UUID organizationId
    ) {
        jdbcTemplate.update("""
                        insert into public.chat_sessions (
                            id,
                            user_id,
                            organization_id,
                            title,
                            created_at,
                            updated_at,
                            version
                        ) values (?, ?, ?, ?, current_timestamp, current_timestamp, 0)
                        """,
                sessionId,
                userId,
                organizationId,
                "Integration chat"
        );
    }

    protected void insertUsageRollup(
            UUID userId,
            UUID organizationId
    ) {
        jdbcTemplate.update("""
                        insert into public.usage_daily_user_model_rollups (
                            usage_date,
                            organization_id,
                            user_id,
                            model,
                            input_tokens,
                            output_tokens,
                            total_tokens,
                            cost_usd,
                            assistant_message_count,
                            failed_message_count,
                            created_at,
                            updated_at
                        ) values (?, ?, ?, ?, 10, 5, 15, 0, 1, 0,
                                  current_timestamp, current_timestamp)
                        """,
                Date.valueOf(LocalDate.now()),
                organizationId,
                userId,
                "mock-safeai"
        );
    }

    protected void insertUserQuota(UUID userId) {
        jdbcTemplate.update("""
                        insert into public.user_ai_quotas (
                            user_id,
                            enabled,
                            created_at,
                            updated_at,
                            version
                        ) values (?, true, current_timestamp, current_timestamp, 0)
                        """,
                userId
        );
    }

    protected void insertAuditDependency(
            UUID userId,
            UUID organizationId,
            String email
    ) {
        jdbcTemplate.update("""
                        insert into public.audit_events (
                            id,
                            user_id,
                            actor_user_id,
                            actor_email,
                            actor_display_name,
                            organization_id,
                            event_type,
                            details,
                            created_at
                        ) values (?, ?, ?, ?, ?, ?, 'USER_CREATED',
                                  '{}'::jsonb, current_timestamp)
                        """,
                UUID.randomUUID(),
                userId,
                userId,
                email,
                "Test User",
                organizationId
        );
    }

    protected void insertAuditOutboxDependency(
            UUID userId,
            UUID organizationId,
            String email
    ) {
        jdbcTemplate.update("""
                        insert into public.audit_outbox (
                            id,
                            actor_user_id,
                            actor_email,
                            actor_display_name,
                            organization_id,
                            event_type,
                            details,
                            created_at
                        ) values (?, ?, ?, ?, ?, 'USER_CREATED',
                                  '{}'::jsonb, current_timestamp)
                        """,
                UUID.randomUUID(),
                userId,
                email,
                "Test User",
                organizationId
        );
    }

    protected long activeAdminCount(UUID organizationId) {
        Long count = jdbcTemplate.queryForObject("""
                        select count(*)
                        from public.users app_user
                        join public.user_roles user_role
                          on user_role.user_id = app_user.id
                        join public.roles role
                          on role.id = user_role.role_id
                        where app_user.organization_id = ?
                          and app_user.enabled = true
                          and role.name = 'ADMIN'
                        """,
                Long.class,
                organizationId
        );

        return count == null ? 0L : count;
    }

    protected boolean userExists(UUID userId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                        select exists (
                            select 1
                            from public.users
                            where id = ?
                        )
                        """,
                Boolean.class,
                userId
        );

        return Boolean.TRUE.equals(exists);
    }

    protected long tokenVersion(UUID userId) {
        Long version = jdbcTemplate.queryForObject("""
                        select token_version
                        from public.users
                        where id = ?
                        """,
                Long.class,
                userId
        );

        return version == null ? -1L : version;
    }

    protected boolean userEnabled(UUID userId) {
        Boolean enabled = jdbcTemplate.queryForObject("""
                        select enabled
                        from public.users
                        where id = ?
                        """,
                Boolean.class,
                userId
        );

        return Boolean.TRUE.equals(enabled);
    }

    protected UUID roleId(String role) {
        return switch (role) {
            case "ADMIN" -> ADMIN_ROLE_ID;
            case "USER" -> USER_ROLE_ID;
            case "SUPER_ADMIN" -> SUPER_ADMIN_ROLE_ID;
            default -> throw new IllegalArgumentException(
                    "Unknown role: " + role
            );
        };
    }

    protected static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private String randomSha256Hex() {
        String value = UUID.randomUUID()
                .toString()
                .replace("-", "");
        return value + value;
    }
}