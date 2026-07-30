package ru.safeai.gateway.audit.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        AuditOutboxProperties.class,
        AuditDetailsProperties.class
})
public class AuditOutboxConfiguration {
}
