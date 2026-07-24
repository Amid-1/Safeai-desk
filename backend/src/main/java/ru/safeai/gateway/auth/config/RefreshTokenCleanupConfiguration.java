package ru.safeai.gateway.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import ru.safeai.gateway.auth.service.RefreshTokenCleanupProperties;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(
        RefreshTokenCleanupProperties.class
)
public class RefreshTokenCleanupConfiguration {
}