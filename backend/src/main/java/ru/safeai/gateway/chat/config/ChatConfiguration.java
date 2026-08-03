package ru.safeai.gateway.chat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({
        ChatProperties.class,
        ChatLockProperties.class,
        ChatQuotaProperties.class,
        ChatRecoveryProperties.class
})
public class ChatConfiguration {
}
