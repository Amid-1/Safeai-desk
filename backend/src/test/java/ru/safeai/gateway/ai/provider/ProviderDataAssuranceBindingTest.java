package ru.safeai.gateway.ai.provider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderDataAssuranceBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProviderDataAssuranceProperties.class)
    static class Config {}

    @Test
    void missingConfigurationHasNoImplicitProof() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var config = context.getBean(ProviderDataAssuranceProperties.class);
            assertThat(config.chat()).isNull();
            assertThat(config.embedding()).isNull();
            assertThat(config.ocr()).isNull();
        });
    }

    @Test
    void fullConfigurationBindsOnlyItsOwnScope() {
        runner.withPropertyValues(
                "safeai.provider-data-assurance.chat.source=CONTRACT-42",
                "safeai.provider-data-assurance.chat.verified-at=2026-11-17T12:00:00Z",
                "safeai.provider-data-assurance.chat.expires-at=2026-12-17T12:00:00Z",
                "safeai.provider-data-assurance.chat.connection-sha256=" + "1".repeat(64),
                "safeai.provider-data-assurance.chat.no-training=true",
                "safeai.provider-data-assurance.chat.zero-data-retention=true"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            var config = context.getBean(ProviderDataAssuranceProperties.class);
            assertThat(config.chat().noTraining()).isTrue();
            assertThat(config.chat().zeroDataRetention()).isTrue();
            assertThat(config.embedding()).isNull();
            assertThat(config.ocr()).isNull();
        });
    }

    @Test
    void dockerEnvironmentNamesBindToTheSameProperties() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.SystemEnvironmentPropertySource("assurance-systemEnvironment",
                        java.util.Map.of(
                                "SAFEAI_PROVIDERDATAASSURANCE_CHAT_SOURCE", "CONTRACT-42",
                                "SAFEAI_PROVIDERDATAASSURANCE_CHAT_VERIFIEDAT", "2026-11-17T12:00:00Z",
                                "SAFEAI_PROVIDERDATAASSURANCE_CHAT_EXPIRESAT", "2026-12-17T12:00:00Z",
                                "SAFEAI_PROVIDERDATAASSURANCE_CHAT_CONNECTIONSHA256", "1".repeat(64),
                                "SAFEAI_PROVIDERDATAASSURANCE_CHAT_NOTRAINING", "true",
                                "SAFEAI_PROVIDERDATAASSURANCE_CHAT_ZERODATARETENTION", "true"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var evidence = context.getBean(ProviderDataAssuranceProperties.class).chat();
                    assertThat(evidence.source()).isEqualTo("CONTRACT-42");
                    assertThat(evidence.noTraining()).isTrue();
                    assertThat(evidence.zeroDataRetention()).isTrue();
                });
    }

    @Test
    void partialConfigurationFailsStartup() {
        runner.withPropertyValues("safeai.provider-data-assurance.chat.no-training=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
