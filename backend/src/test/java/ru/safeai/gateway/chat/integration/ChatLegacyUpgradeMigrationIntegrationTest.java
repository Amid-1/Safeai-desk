package ru.safeai.gateway.chat.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет реальный upgrade path:
 * схема V1..V31 с legacy-сообщениями обновляется
 * до chat-turn state machine.
 * Такой тест обнаруживает дефекты миграции данных,
 * которые невозможно увидеть при миграции пустой базы.
 */
@Testcontainers
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
class ChatLegacyUpgradeMigrationIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("safeai_chat_upgrade")
                    .withUsername("safeai")
                    .withPassword("safeai_password");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "91000000-0000-0000-0000-000000000001"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "92000000-0000-0000-0000-000000000001"
            );

    private static final UUID SESSION_ID =
            UUID.fromString(
                    "93000000-0000-0000-0000-000000000001"
            );

    private static final UUID SUCCEEDED_USER_ID =
            UUID.fromString(
                    "94000000-0000-0000-0000-000000000001"
            );

    private static final UUID SUCCEEDED_ASSISTANT_ID =
            UUID.fromString(
                    "95000000-0000-0000-0000-000000000001"
            );

    private static final UUID FAILED_USER_ID =
            UUID.fromString(
                    "96000000-0000-0000-0000-000000000001"
            );

    private static final UUID FAILED_ASSISTANT_ID =
            UUID.fromString(
                    "97000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORPHAN_USER_ID =
            UUID.fromString(
                    "98000000-0000-0000-0000-000000000001"
            );

    private static final UUID FAILED_CLIENT_REQUEST_ID =
            UUID.fromString(
                    "99000000-0000-0000-0000-000000000001"
            );

    private static final String ORGANIZATION_NAME =
            "Legacy Chat Organization";

    private static final String NORMALIZED_ORGANIZATION_NAME =
            "legacy chat organization";

    private static final String LEGACY_SUCCEEDED_CONTENT =
            "line1\r\nline2";

    private static final String NORMALIZED_SUCCEEDED_CONTENT =
            "line1\nline2";

    private static final Instant BASE_TIME =
            Instant.parse("2026-06-12T12:00:00Z");

    @BeforeAll
    static void setUpDatabase() throws Exception {
        POSTGRES.start();

        flyway(
                MigrationVersion.fromVersion("31")
        ).migrate();

        seedLegacyRows();

        flyway(
                MigrationVersion.LATEST
        ).migrate();
    }

    @AfterAll
    static void tearDownDatabase() {
        POSTGRES.stop();
    }

    @Test
    void completedLegacyPairBecomesSucceededTurnAndRemainsHistoryEligible()
            throws Exception {
        try (
                Connection connection = connection();
                PreparedStatement statement =
                        connection.prepareStatement("""
                                select
                                    turn.state,
                                    turn.client_request_id,
                                    turn.request_content_hash,
                                    turn.provider_operation_id,
                                    turn.user_message_id,
                                    turn.assistant_message_id,
                                    turn.provider,
                                    turn.requested_model,
                                    turn.resolved_model,
                                    turn.outcome_ambiguous,
                                    message.client_request_id
                                        as message_client_request_id
                                from chat_turns turn
                                join chat_messages message
                                  on message.id = turn.user_message_id
                                where turn.id = ?
                                """)
        ) {
            statement.setObject(
                    1,
                    SUCCEEDED_USER_ID
            );

            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next())
                        .isTrue();

                assertThat(
                        result.getString("state")
                ).isEqualTo("SUCCEEDED");

                assertThat(
                        result.getObject(
                                "client_request_id",
                                UUID.class
                        )
                ).isEqualTo(SUCCEEDED_USER_ID);

                assertThat(
                        result.getObject(
                                "provider_operation_id",
                                UUID.class
                        )
                ).isEqualTo(SUCCEEDED_USER_ID);

                assertThat(
                        result.getObject(
                                "user_message_id",
                                UUID.class
                        )
                ).isEqualTo(SUCCEEDED_USER_ID);

                assertThat(
                        result.getObject(
                                "assistant_message_id",
                                UUID.class
                        )
                ).isEqualTo(SUCCEEDED_ASSISTANT_ID);

                assertThat(
                        result.getString("provider")
                ).isEqualTo("legacy");

                assertThat(
                        result.getString("requested_model")
                ).isEqualTo("mock-safeai");

                assertThat(
                        result.getString("resolved_model")
                ).isEqualTo("mock-safeai");

                assertThat(
                        result.getBoolean("outcome_ambiguous")
                ).isFalse();

                assertThat(
                        result.getObject(
                                "message_client_request_id",
                                UUID.class
                        )
                ).isEqualTo(SUCCEEDED_USER_ID);
            }
        }
    }

    @Test
    void normalizedLegacyContentHashMatchesRuntimeSha256()
            throws Exception {
        String expectedHash =
                normalizedSucceededContentHash();

        try (
                Connection connection = connection();
                PreparedStatement statement =
                        connection.prepareStatement("""
                                select request_content_hash
                                from chat_turns
                                where id = ?
                                """)
        ) {
            statement.setObject(
                    1,
                    SUCCEEDED_USER_ID
            );

            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next())
                        .isTrue();

                assertThat(result.getString(1))
                        .isEqualTo(expectedHash);
            }
        }
    }

    @Test
    void legacyFailedReplyBecomesStableFailedTurn()
            throws Exception {
        assertState(
                FAILED_USER_ID,
                "FAILED",
                false,
                "LEGACY_PROVIDER_FAILURE"
        );
    }

    @Test
    void legacyOrphanUserBecomesAmbiguousAndIsNeverAutoRetried()
            throws Exception {
        assertState(
                ORPHAN_USER_ID,
                "AMBIGUOUS",
                true,
                "LEGACY_ORPHAN_USER_MESSAGE"
        );
    }

    @Test
    void everyLegacyCompletedUserMessageHasExactlyOneTurn()
            throws Exception {
        try (
                Connection connection = connection();
                Statement statement =
                        connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        select
                            (
                                select count(*)
                                from chat_messages
                                where role = 'USER'
                                  and status = 'COMPLETED'
                            ) as user_count,
                            (
                                select count(*)
                                from chat_turns
                            ) as turn_count
                        """)
        ) {
            assertThat(result.next())
                    .isTrue();

            assertThat(
                    result.getLong("turn_count")
            ).isEqualTo(
                    result.getLong("user_count")
            );
        }
    }

    private static Flyway flyway(
            MigrationVersion target
    ) {
        return Flyway.configure()
                .configuration(
                        Map.of(
                                "flyway.postgresql.transactional.lock",
                                "false"
                        )
                )
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .locations(
                        "classpath:db/migration"
                )
                .target(target)
                .validateMigrationNaming(true)
                .load();
    }

    private static void seedLegacyRows()
            throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);

            insertOrganization(connection);
            insertUser(connection);
            insertSession(connection);

            insertUserMessage(
                    connection,
                    SUCCEEDED_USER_ID,
                    null,
                    LEGACY_SUCCEEDED_CONTENT,
                    BASE_TIME
            );

            insertCompletedAssistant(
                    connection,
                    BASE_TIME.plusSeconds(1)
            );

            insertUserMessage(
                    connection,
                    FAILED_USER_ID,
                    FAILED_CLIENT_REQUEST_ID,
                    "failed question",
                    BASE_TIME.plusSeconds(2)
            );

            insertFailedAssistant(
                    connection,
                    BASE_TIME.plusSeconds(3)
            );

            insertUserMessage(
                    connection,
                    ORPHAN_USER_ID,
                    null,
                    "orphan question",
                    BASE_TIME.plusSeconds(4)
            );

            connection.commit();
        }
    }

    private static void insertOrganization(
            Connection connection
    ) throws Exception {
        try (
                PreparedStatement statement =
                        connection.prepareStatement("""
                                insert into organizations (
                                    id,
                                    name,
                                    normalized_name,
                                    enabled,
                                    created_at,
                                    updated_at,
                                    version
                                ) values (
                                    ?,
                                    ?,
                                    ?,
                                    true,
                                    ?,
                                    ?,
                                    0
                                )
                                """)
        ) {
            statement.setObject(
                    1,
                    ORGANIZATION_ID
            );

            statement.setString(
                    2,
                    ORGANIZATION_NAME
            );

            statement.setString(
                    3,
                    NORMALIZED_ORGANIZATION_NAME
            );

            statement.setTimestamp(
                    4,
                    Timestamp.from(BASE_TIME)
            );

            statement.setTimestamp(
                    5,
                    Timestamp.from(BASE_TIME)
            );

            statement.executeUpdate();
        }
    }

    private static void insertUser(
            Connection connection
    ) throws Exception {
        try (
                PreparedStatement statement =
                        connection.prepareStatement("""
                                insert into users (
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
                                ) values (
                                    ?,
                                    ?,
                                    ?,
                                    ?,
                                    ?,
                                    true,
                                    null,
                                    ?,
                                    ?,
                                    0,
                                    0
                                )
                                """)
        ) {
            statement.setObject(
                    1,
                    USER_ID
            );

            statement.setObject(
                    2,
                    ORGANIZATION_ID
            );

            statement.setString(
                    3,
                    "legacy-chat@test.example"
            );

            statement.setString(
                    4,
                    "encoded-password"
            );

            statement.setString(
                    5,
                    "Legacy Chat User"
            );

            statement.setTimestamp(
                    6,
                    Timestamp.from(BASE_TIME)
            );

            statement.setTimestamp(
                    7,
                    Timestamp.from(BASE_TIME)
            );

            statement.executeUpdate();
        }

        try (
                PreparedStatement statement =
                        connection.prepareStatement("""
                                insert into user_roles (
                                    user_id,
                                    role_id
                                ) values (
                                    ?,
                                    '22222222-2222-2222-2222-222222222222'
                                        ::uuid
                                )
                                """)
        ) {
            statement.setObject(
                    1,
                    USER_ID
            );

            statement.executeUpdate();
        }
    }

    private static void insertSession(
            Connection connection
    ) throws Exception {
        try (
                PreparedStatement statement =
                        connection.prepareStatement("""
                                insert into chat_sessions (
                                    id,
                                    user_id,
                                    organization_id,
                                    title,
                                    created_at,
                                    updated_at,
                                    version
                                ) values (
                                    ?,
                                    ?,
                                    ?,
                                    ?,
                                    ?,
                                    ?,
                                    0
                                )
                                """)
        ) {
            statement.setObject(
                    1,
                    SESSION_ID
            );

            statement.setObject(
                    2,
                    USER_ID
            );

            statement.setObject(
                    3,
                    ORGANIZATION_ID
            );

            statement.setString(
                    4,
                    "Legacy Chat"
            );

            statement.setTimestamp(
                    5,
                    Timestamp.from(BASE_TIME)
            );

            statement.setTimestamp(
                    6,
                    Timestamp.from(BASE_TIME)
            );

            statement.executeUpdate();
        }
    }

    private static void insertUserMessage(
            Connection connection,
            UUID id,
            UUID clientRequestId,
            String content,
            Instant createdAt
    ) throws Exception {
        try (
                PreparedStatement statement =
                        connection.prepareStatement("""
                                insert into chat_messages (
                                    id,
                                    session_id,
                                    organization_id,
                                    role,
                                    content,
                                    client_request_id,
                                    created_at,
                                    status,
                                    usage_status,
                                    pricing_status
                                ) values (
                                    ?,
                                    ?,
                                    ?,
                                    'USER',
                                    ?,
                                    ?,
                                    ?,
                                    'COMPLETED',
                                    'NOT_APPLICABLE',
                                    'NOT_APPLICABLE'
                                )
                                """)
        ) {
            statement.setObject(
                    1,
                    id
            );

            statement.setObject(
                    2,
                    SESSION_ID
            );

            statement.setObject(
                    3,
                    ORGANIZATION_ID
            );

            statement.setString(
                    4,
                    content
            );

            statement.setObject(
                    5,
                    clientRequestId
            );

            statement.setTimestamp(
                    6,
                    Timestamp.from(createdAt)
            );

            statement.executeUpdate();
        }
    }

    private static void insertCompletedAssistant(
            Connection connection,
            Instant createdAt
    ) throws Exception {
        try (
                PreparedStatement statement =
                        connection.prepareStatement("""
                                insert into chat_messages (
                                    id,
                                    session_id,
                                    organization_id,
                                    role,
                                    content,
                                    reply_to_message_id,
                                    model,
                                    provider_message_id,
                                    ai_response_status,
                                    finish_reason,
                                    input_tokens,
                                    output_tokens,
                                    usage_status,
                                    cost_usd,
                                    pricing_status,
                                    currency,
                                    pricing_version,
                                    pricing_calculated_at,
                                    created_at,
                                    status
                                ) values (
                                    ?,
                                    ?,
                                    ?,
                                    'ASSISTANT',
                                    'legacy answer',
                                    ?,
                                    'mock-safeai',
                                    'legacy-provider-message',
                                    'COMPLETED',
                                    'completed',
                                    10,
                                    20,
                                    'AVAILABLE',
                                    0,
                                    'FREE',
                                    'USD',
                                    'mock-2026-01',
                                    ?,
                                    ?,
                                    'COMPLETED'
                                )
                                """)
        ) {
            statement.setObject(
                    1,
                    SUCCEEDED_ASSISTANT_ID
            );

            statement.setObject(
                    2,
                    SESSION_ID
            );

            statement.setObject(
                    3,
                    ORGANIZATION_ID
            );

            statement.setObject(
                    4,
                    SUCCEEDED_USER_ID
            );

            statement.setTimestamp(
                    5,
                    Timestamp.from(createdAt)
            );

            statement.setTimestamp(
                    6,
                    Timestamp.from(createdAt)
            );

            statement.executeUpdate();
        }
    }

    private static void insertFailedAssistant(
            Connection connection,
            Instant createdAt
    ) throws Exception {
        try (
                PreparedStatement statement =
                        connection.prepareStatement("""
                                insert into chat_messages (
                                    id,
                                    session_id,
                                    organization_id,
                                    role,
                                    content,
                                    reply_to_message_id,
                                    created_at,
                                    status,
                                    usage_status,
                                    pricing_status
                                ) values (
                                    ?,
                                    ?,
                                    ?,
                                    'ASSISTANT',
                                    'provider failed',
                                    ?,
                                    ?,
                                    'FAILED',
                                    'NOT_APPLICABLE',
                                    'NOT_APPLICABLE'
                                )
                                """)
        ) {
            statement.setObject(
                    1,
                    FAILED_ASSISTANT_ID
            );

            statement.setObject(
                    2,
                    SESSION_ID
            );

            statement.setObject(
                    3,
                    ORGANIZATION_ID
            );

            statement.setObject(
                    4,
                    FAILED_USER_ID
            );

            statement.setTimestamp(
                    5,
                    Timestamp.from(createdAt)
            );

            statement.executeUpdate();
        }
    }

    private static void assertState(
            UUID turnId,
            String expectedState,
            boolean expectedAmbiguous,
            String expectedFailureCode
    ) throws Exception {
        try (
                Connection connection = connection();
                PreparedStatement statement =
                        connection.prepareStatement("""
                                select
                                    state,
                                    outcome_ambiguous,
                                    failure_code,
                                    processing_token,
                                    lease_until
                                from chat_turns
                                where id = ?
                                """)
        ) {
            statement.setObject(
                    1,
                    turnId
            );

            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next())
                        .isTrue();

                assertThat(
                        result.getString("state")
                ).isEqualTo(expectedState);

                assertThat(
                        result.getBoolean("outcome_ambiguous")
                ).isEqualTo(expectedAmbiguous);

                assertThat(
                        result.getString("failure_code")
                ).isEqualTo(expectedFailureCode);

                assertThat(
                        result.getObject("processing_token")
                ).isNull();

                assertThat(
                        result.getObject("lease_until")
                ).isNull();
            }
        }
    }

    private static String normalizedSucceededContentHash()
            throws Exception {
        byte[] contentBytes =
                NORMALIZED_SUCCEEDED_CONTENT.getBytes(
                        StandardCharsets.UTF_8
                );

        byte[] digest = MessageDigest
                .getInstance("SHA-256")
                .digest(contentBytes);

        return HexFormat.of()
                .formatHex(digest);
    }

    private static Connection connection()
            throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }
}