package ru.safeai.gateway.chat.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.chat.entity.ChatTurnState;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class ChatTurnRecoveryRepository {

    @SuppressWarnings("SqlResolve")
    private static final String RECOVER_EXPIRED_TURNS_SQL = """
            with stale_turns as (
                select
                    ct.id,
                    ct.provider_call_started_at
                from chat_turns ct
                where ct.state = 'PROCESSING'
                  and ct.lease_until <= ?
                order by ct.lease_until, ct.id
                for update skip locked
                limit ?
            )
            update chat_turns ct
               set state = case
                       when stale_turns.provider_call_started_at is null
                           then 'FAILED'
                       else 'AMBIGUOUS'
                   end,
                   provider_error_type = coalesce(
                       ct.provider_error_type,
                       case
                           when stale_turns.provider_call_started_at is null
                               then 'PROVIDER_CALL_NOT_STARTED'
                           else 'STALE_LEASE'
                       end
                   ),
                   failure_code = coalesce(
                       ct.failure_code,
                       case
                           when stale_turns.provider_call_started_at is null
                               then 'STALE_BEFORE_PROVIDER_CALL'
                           else 'STALE_PROCESSING_LEASE'
                       end
                   ),
                   processing_token = null,
                   lease_until = null,
                   outcome_ambiguous =
                       stale_turns.provider_call_started_at is not null,
                   completed_at = ?,
                   updated_at = ?,
                   version = ct.version + 1
              from stale_turns
             where ct.id = stale_turns.id
            returning
                ct.id,
                ct.organization_id,
                ct.session_id,
                ct.client_request_id,
                ct.state,
                ct.failure_code,
                ct.outcome_ambiguous
            """;

    private final JdbcTemplate jdbcTemplate;

    public ChatTurnRecoveryRepository(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate не должен быть null"
        );
    }

    public List<RecoveredChatTurn> recoverExpired(
            Instant now,
            int batchSize
    ) {
        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "batchSize должен быть положительным"
            );
        }

        Timestamp timestamp = Timestamp.from(now);

        return jdbcTemplate.query(
                RECOVER_EXPIRED_TURNS_SQL,
                (resultSet, rowNumber) ->
                        new RecoveredChatTurn(
                                resultSet.getObject(
                                        "id",
                                        UUID.class
                                ),
                                resultSet.getObject(
                                        "organization_id",
                                        UUID.class
                                ),
                                resultSet.getObject(
                                        "session_id",
                                        UUID.class
                                ),
                                resultSet.getObject(
                                        "client_request_id",
                                        UUID.class
                                ),
                                ChatTurnState.valueOf(
                                        resultSet.getString(
                                                "state"
                                        )
                                ),
                                resultSet.getString(
                                        "failure_code"
                                ),
                                resultSet.getBoolean(
                                        "outcome_ambiguous"
                                )
                        ),
                timestamp,
                batchSize,
                timestamp,
                timestamp
        );
    }
}
