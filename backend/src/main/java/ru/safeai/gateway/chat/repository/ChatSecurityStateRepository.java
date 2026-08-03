package ru.safeai.gateway.chat.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class ChatSecurityStateRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatSecurityStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isActive(UUID userId, UUID organizationId) {
        Boolean active = jdbcTemplate.queryForObject(
                """
                select exists (
                    select 1
                    from users u
                    join organizations o on o.id = u.organization_id
                    where u.id = ?
                      and u.organization_id = ?
                      and u.enabled = true
                      and o.enabled = true
                )
                """,
                Boolean.class,
                userId,
                organizationId
        );
        return Boolean.TRUE.equals(active);
    }
}
