package ru.safeai.gateway.chat.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL transaction-scoped advisory mutex. It only serializes reservation
 * work; unique constraints and conditional state transitions remain the source
 * of correctness.
 */
@Repository
public class ChatTurnMutexRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatTurnMutexRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockChat(UUID chatId) {
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        jdbcTemplate.query(
                "select pg_advisory_xact_lock(?)",
                resultSet -> {
                    // PostgreSQL void-returning function: consuming the row is enough.
                },
                key(chatId)
        );
    }

    private long key(UUID chatId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer input = ByteBuffer.allocate(16);
            input.putLong(chatId.getMostSignificantBits());
            input.putLong(chatId.getLeastSignificantBits());
            byte[] hash = digest.digest(input.array());
            return ByteBuffer.wrap(hash).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен", exception);
        }
    }
}
