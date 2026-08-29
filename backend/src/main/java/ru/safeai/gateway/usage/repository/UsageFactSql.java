package ru.safeai.gateway.usage.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

record UsageFactSql(
        String cte,
        MapSqlParameterSource parameters
) {
}
