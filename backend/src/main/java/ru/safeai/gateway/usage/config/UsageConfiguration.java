package ru.safeai.gateway.usage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UsageProperties.class)
public class UsageConfiguration {

    @Bean
    UsageJdbcClients usageJdbcClients(
            DataSource dataSource,
            UsageProperties properties
    ) {
        return new UsageJdbcClients(
                dataSource,
                properties
        );
    }
}
