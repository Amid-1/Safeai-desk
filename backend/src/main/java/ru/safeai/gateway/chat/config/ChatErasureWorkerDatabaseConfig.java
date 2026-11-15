package ru.safeai.gateway.chat.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** The worker connection holder is NOT exposed as another DataSource or
 * PlatformTransactionManager bean. Spring Boot's primary JPA/Flyway datasource
 * and default @Transactional manager remain completely unchanged. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {
    "safeai.chat.erasure.executor-enabled", "safeai.chat.erasure.retention-approved"
}, havingValue = "true")
public class ChatErasureWorkerDatabaseConfig {

    public static final class WorkerConnections implements AutoCloseable {
        private final HikariDataSource dataSource;
        private final JdbcTemplate jdbc;
        private final PlatformTransactionManager transactions;

        public WorkerConnections(String url, String username, String password) {
            if (url == null || url.isBlank()
                || !"safeai_chat_erasure_worker".equals(username)
                || password == null || password.isBlank()) {
                throw new IllegalStateException("Dedicated worker database credentials required");
            }
            HikariConfig options = new HikariConfig();
            options.setJdbcUrl(url);
            options.setUsername(username);
            options.setPassword(password);
            options.setPoolName("safeai-chat-erasure-worker");
            options.setMaximumPoolSize(2);
            options.setMinimumIdle(0);
            options.setConnectionTimeout(10_000L);
            options.setValidationTimeout(5_000L);
            this.dataSource = new HikariDataSource(options);
            this.jdbc = new JdbcTemplate(dataSource);
            this.transactions = new DataSourceTransactionManager(dataSource);
        }

        public JdbcTemplate jdbc() { return jdbc; }
        public PlatformTransactionManager transactionManager() { return transactions; }

        @Override public void close() { dataSource.close(); }
    }

    @Bean(destroyMethod = "close")
    public WorkerConnections chatErasureWorkerConnections(
        @Value("${safeai.chat.erasure.worker-jdbc-url:}") String url,
        @Value("${safeai.chat.erasure.worker-username:}") String username,
        @Value("${safeai.chat.erasure.worker-password:}") String password,
        @Value("${spring.datasource.url:}") String applicationUrl) {
        // A different JDBC URL may point to another cluster containing identical
        // UUIDs; destructive worker may NEVER silently use a different target.
        if (applicationUrl == null || applicationUrl.isBlank()
                || !applicationUrl.equals(url)) {
            throw new IllegalStateException("Erasure worker JDBC target must match app datasource exactly");
        }
        return new WorkerConnections(url, username, password);
    }
}
