package ru.safeai.gateway.usage.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Objects;

@Component
public final class UsageHikariPoolInvariantVerifier implements ApplicationRunner {
    private final DataSource dataSource;
    private final Environment environment;
    private final UsageProperties usageProperties;

    public UsageHikariPoolInvariantVerifier(
            DataSource dataSource,
            Environment environment,
            UsageProperties usageProperties
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.usageProperties = Objects.requireNonNull(usageProperties, "usageProperties");
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        if (!environment.acceptsProfiles(Profiles.of("prod", "production"))
                || !usageProperties.rollup().isEnabled()) {
            return;
        }
        if (!(dataSource instanceof HikariDataSource hikari)) {
            throw new IllegalStateException("Production usage rollup requires a verified HikariDataSource");
        }
        // 1 advisory-lock session + 1 worker transaction + spare capacity.
        // Size alone does NOT prove freedom from pool starvation.
        if (hikari.getMaximumPoolSize() < 3) {
            throw new IllegalStateException("Usage rollup requires Hikari maximumPoolSize >= 3");
        }
    }
}
