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

    public boolean isActive(
            UUID userId,
            UUID organizationId,
            long tokenVersion,
            long organizationAuthVersion
    ) {
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
                      and u.token_version = ?
                      and o.auth_version = ?
                )
                """,
                Boolean.class,
                userId,
                organizationId,
                tokenVersion,
                organizationAuthVersion
        );
        return Boolean.TRUE.equals(active);
    }
}
