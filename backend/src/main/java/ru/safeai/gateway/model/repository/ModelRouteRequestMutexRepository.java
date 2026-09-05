package ru.safeai.gateway.model.repository;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Objects;
import java.util.UUID;

/** Transaction-scoped serialization for one route idempotency identity. */
@Repository
public class ModelRouteRequestMutexRepository {

    private final JdbcTemplate jdbc;

    public ModelRouteRequestMutexRepository(
            JdbcTemplate jdbc
    ) {
        this.jdbc = Objects.requireNonNull(
                jdbc,
                "jdbc не должен быть null"
        );
    }

    public void lock(
            UUID chatId,
            UUID clientRequestId
    ) {
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        Objects.requireNonNull(
                clientRequestId,
                "clientRequestId не должен быть null"
        );

        String lockKey =
                "safeai:model-route:"
                        + chatId
                        + ":"
                        + clientRequestId;

        jdbc.execute(
                (ConnectionCallback<Void>) connection -> {
                    try (
                            PreparedStatement statement =
                                    connection.prepareStatement(
                                            "select pg_advisory_xact_lock("
                                                    + "hashtextextended(?, 0))"
                                    )
                    ) {
                        statement.setString(1, lockKey);
                        statement.execute();
                    }

                    return null;
                }
        );
    }
}
