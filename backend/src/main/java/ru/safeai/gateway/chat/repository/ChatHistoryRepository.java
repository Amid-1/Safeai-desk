package ru.safeai.gateway.chat.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class ChatHistoryRepository {

    @SuppressWarnings("SqlResolve")
    private static final String FIND_NEWEST_SUCCEEDED_TURNS_SQL = """
            select
                ct.id as turn_id,
                user_message.content as user_content,
                assistant_message.content as assistant_content
            from chat_turns ct
            join chat_messages user_message
              on user_message.id = ct.user_message_id
             and user_message.session_id = ct.session_id
             and user_message.organization_id = ct.organization_id
             and user_message.role = 'USER'
            join chat_messages assistant_message
              on assistant_message.id = ct.assistant_message_id
             and assistant_message.session_id = ct.session_id
             and assistant_message.organization_id = ct.organization_id
             and assistant_message.role = 'ASSISTANT'
             and assistant_message.status = 'COMPLETED'
             and assistant_message.reply_to_message_id = user_message.id
            where ct.session_id = ?
              and ct.organization_id = ?
              and ct.user_id = ?
              and ct.state = 'SUCCEEDED'
            order by ct.created_at desc, ct.id desc
            limit ?
            """;

    private static final RowMapper<ChatHistoryTurn>
            HISTORY_TURN_ROW_MAPPER =
            (resultSet, rowNumber) ->
                    new ChatHistoryTurn(
                            resultSet.getObject(
                                    "turn_id",
                                    UUID.class
                            ),
                            resultSet.getString(
                                    "user_content"
                            ),
                            resultSet.getString(
                                    "assistant_content"
                            )
                    );

    private final JdbcTemplate jdbcTemplate;

    public ChatHistoryRepository(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate не должен быть null"
        );
    }

    public List<ChatHistoryTurn> findNewestSucceededTurns(
            UUID sessionId,
            UUID organizationId,
            UUID userId,
            int limit
    ) {
        Objects.requireNonNull(
                sessionId,
                "sessionId не должен быть null"
        );
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        if (limit < 1) {
            throw new IllegalArgumentException(
                    "limit должен быть положительным"
            );
        }

        return jdbcTemplate.query(
                FIND_NEWEST_SUCCEEDED_TURNS_SQL,
                preparedStatement -> {
                    preparedStatement.setObject(
                            1,
                            sessionId
                    );
                    preparedStatement.setObject(
                            2,
                            organizationId
                    );
                    preparedStatement.setObject(
                            3,
                            userId
                    );
                    preparedStatement.setInt(
                            4,
                            limit
                    );
                },
                HISTORY_TURN_ROW_MAPPER
        );
    }
}
