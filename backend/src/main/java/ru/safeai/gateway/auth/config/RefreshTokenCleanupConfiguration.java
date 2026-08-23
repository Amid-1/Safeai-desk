package ru.safeai.gateway.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import ru.safeai.gateway.auth.service.RefreshTokenCleanupProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
        RefreshTokenCleanupProperties.class
)
public class RefreshTokenCleanupConfiguration {
}
