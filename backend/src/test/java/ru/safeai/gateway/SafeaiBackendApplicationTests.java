package ru.safeai.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
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
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:pg16")
                    .withDatabaseName("safeai_test")
                    .withUsername("safeai")
                    .withPassword("safeai_password");

    @Test
    void contextLoads() {
    }
}
