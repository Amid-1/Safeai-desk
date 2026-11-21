package ru.safeai.gateway.knowledge.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Non-primary, isolated PostgreSQL connection for irreversible Knowledge erasure.
 * Unlike the normal application DataSource this connection can DELETE live
 * Knowledge content. Never expose it as a standalone DataSource Spring bean. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "safeai.knowledge.deletion", name = "executor-enabled", havingValue = "true")
public class KnowledgeErasureWorkerDatabaseConfig {

    public static final class WorkerConnections implements AutoCloseable {
        private final HikariDataSource pool;
        private final JdbcTemplate jdbc;
        private final PlatformTransactionManager tx;

        public WorkerConnections(String url, String username, String password) {
            if (url == null || url.isBlank()
                    || !"safeai_knowledge_erasure_worker".equals(username)
                    || password == null || password.isBlank()) {
                throw new IllegalStateException("Dedicated Knowledge erasure worker credentials required");
            }
            HikariConfig cfg = new HikariConfig();
            cfg.setPoolName("safeai-knowledge-erasure-worker");
            cfg.setJdbcUrl(url);
            cfg.setUsername(username);
            cfg.setPassword(password);
            cfg.setMaximumPoolSize(2);
            cfg.setMinimumIdle(0);
            cfg.setConnectionTimeout(10000);
            cfg.setValidationTimeout(5000);
            this.pool = new HikariDataSource(cfg);
            this.jdbc = new JdbcTemplate(pool);
            this.tx = new DataSourceTransactionManager(pool);
            String actual = jdbc.queryForObject("SELECT session_user", String.class);
            if (!"safeai_knowledge_erasure_worker".equals(actual)) {
                pool.close();
                throw new IllegalStateException("Knowledge erasure JDBC role mismatch");
            }
        }

        public JdbcTemplate jdbc() { return jdbc; }
        public PlatformTransactionManager transactionManager() { return tx; }
        @Override public void close() { pool.close(); }
    }

    @Bean(destroyMethod = "close")
    public WorkerConnections knowledgeErasureWorkerConnections(
            @Value("${safeai.knowledge.deletion.worker-jdbc-url:}") String url,
            @Value("${safeai.knowledge.deletion.worker-username:}") String username,
            @Value("${safeai.knowledge.deletion.worker-password:}") String password,
            @Value("${spring.datasource.url:}") String applicationUrl,
            @Value("${safeai.knowledge.erasure-journal.enabled:false}") boolean journalEnabled,
            @Value("${safeai.knowledge.erasure-journal.object-lock-required:false}") boolean lockRequired) {
        if (!journalEnabled || !lockRequired) {
            throw new IllegalStateException("Knowledge erasure cannot start without immutable S3 journal");
        }
        // Both pools must target the same database and cluster.
        if (applicationUrl == null || applicationUrl.isBlank() || !applicationUrl.equals(url)) {
            throw new IllegalStateException("Knowledge worker JDBC URL must exactly match application URL");
        }
        return new WorkerConnections(url, username, password);
    }
}
