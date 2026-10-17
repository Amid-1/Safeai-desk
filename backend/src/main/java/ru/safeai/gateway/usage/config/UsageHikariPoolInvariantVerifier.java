package ru.safeai.gateway.usage.config;

import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Objects;

/** Production preflight to reduce starvation of the shared connection pool. */
@Component
public final class UsageHikariPoolInvariantVerifier implements ApplicationRunner {
    private static final int RESERVED_NON_REPORT_CONNECTIONS = 2;

    private final DataSource dataSource;
    private final Environment environment;
    private final UsageProperties usageProperties;

    public UsageHikariPoolInvariantVerifier(
            DataSource dataSource, Environment environment, UsageProperties usageProperties
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.usageProperties = Objects.requireNonNull(usageProperties, "usageProperties");
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        if (!environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            return;
        }
        if (!(dataSource instanceof HikariDataSource hikari)) {
            throw new IllegalStateException(
                    "Production usage rollup requires a verified HikariDataSource");
        }
        int reports = usageProperties.maxConcurrentReports();
        int minimumPoolSize = Math.addExact(reports, RESERVED_NON_REPORT_CONNECTIONS);
        if (hikari.getMaximumPoolSize() < minimumPoolSize) {
            throw new IllegalStateException(
                    "Usage Hikari maximumPoolSize должен быть >= " + minimumPoolSize
                            + " (" + reports + " reports + "
                            + RESERVED_NON_REPORT_CONNECTIONS
                            + " reserved DB connections)");
        }
        // Pool sizing alone does not prove freedom from leaks or lock waits.
    }
}
