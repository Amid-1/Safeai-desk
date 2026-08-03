package ru.safeai.gateway.usage.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Usage-specific JDBC clients are wrapped in a dedicated type so they do not
 * create additional JdbcTemplate beans and do not make existing unqualified
 * JdbcTemplate injection ambiguous elsewhere in the application.
 */
public final class UsageJdbcClients {

    private final NamedParameterJdbcTemplate query;
    private final JdbcTemplate rollup;

    public UsageJdbcClients(
            DataSource dataSource,
            UsageProperties properties
    ) {
        Objects.requireNonNull(
                dataSource,
                "dataSource не должен быть null"
        );
        Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );

        JdbcTemplate queryTemplate = new JdbcTemplate(dataSource);
        queryTemplate.setQueryTimeout(
                properties.queryTimeoutSeconds()
        );
        queryTemplate.setFetchSize(properties.fetchSize());

        JdbcTemplate rollupTemplate = new JdbcTemplate(dataSource);
        rollupTemplate.setQueryTimeout(
                properties.rollupStatementTimeoutSeconds()
        );

        this.query = new NamedParameterJdbcTemplate(queryTemplate);
        this.rollup = rollupTemplate;
    }

    public NamedParameterJdbcTemplate query() {
        return query;
    }

    public JdbcTemplate rollup() {
        return rollup;
    }
}
