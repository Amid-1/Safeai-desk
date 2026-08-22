package ru.safeai.gateway.chat.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.ZoneOffset;

@TestConfiguration(proxyBeanMethods = false)
public class ChatIntegrationClockConfiguration {

    @Bean
    @Primary
    Clock chatIntegrationClock() {
        return Clock.fixed(
                AbstractChatPostgresIntegrationTest.NOW,
                ZoneOffset.UTC
        );
    }
}
