package ru.safeai.gateway;

import ru.safeai.gateway.testsupport.PostgresTestMigrationBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
        properties = {
                "spring.data.redis.repositories.enabled=false"
        }
)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class SafeaiBackendApplicationTests {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:pg16")
                    .withDatabaseName("safeai_test")
                    .withUsername("safeai")
                    .withPassword("safeai_password");

    @DynamicPropertySource
    static void isolatedMigrationProperties(DynamicPropertyRegistry registry) {
        PostgresTestMigrationBootstrap.configureSpring(registry, POSTGRES);
    }


    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
        PostgresTestMigrationBootstrap.verifyMigratedSchema(jdbcTemplate, false);
    }
}
