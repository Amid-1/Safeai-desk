package ru.safeai.gateway.ai.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.provider.AiProviderProperties;
import ru.safeai.gateway.ai.provider.AiRetryProperties;
import ru.safeai.gateway.ai.provider.anthropic.AnthropicProperties;
import ru.safeai.gateway.ai.provider.openai.OpenAiProperties;

import java.time.Duration;
import java.util.Objects;

@Component
public final class AiRetryTimeoutBudgetValidator {

    private final AiProviderProperties providerProperties;
    private final AiRetryProperties retryProperties;
    private final ObjectProvider<OpenAiProperties> openAiProperties;
    private final ObjectProvider<AnthropicProperties> anthropicProperties;

    public AiRetryTimeoutBudgetValidator(
            AiProviderProperties providerProperties,
            AiRetryProperties retryProperties,
            ObjectProvider<OpenAiProperties> openAiProperties,
            ObjectProvider<AnthropicProperties> anthropicProperties
    ) {
        this.providerProperties = Objects.requireNonNull(
                providerProperties,
                "providerProperties не должен быть null"
        );
        this.retryProperties = Objects.requireNonNull(
                retryProperties,
                "retryProperties не должен быть null"
        );
        this.openAiProperties = Objects.requireNonNull(
                openAiProperties,
                "openAiProperties не должен быть null"
        );
        this.anthropicProperties = Objects.requireNonNull(
                anthropicProperties,
                "anthropicProperties не должен быть null"
        );
    }

    @PostConstruct
    void validate() {
        switch (providerProperties.provider()) {
            case "openai" -> {
                OpenAiProperties properties =
                        requireSelectedProviderProperties(
                                openAiProperties.getIfAvailable(),
                                "OpenAI"
                        );

                validateBudget(
                        "openai",
                        properties.connectTimeout(),
                        properties.readTimeout()
                );
            }

            case "anthropic" -> {
                AnthropicProperties properties =
                        requireSelectedProviderProperties(
                                anthropicProperties.getIfAvailable(),
                                "Anthropic"
                        );

                validateBudget(
                        "anthropic",
                        properties.connectTimeout(),
                        properties.readTimeout()
                );
            }

            case "mock" -> {
                // Mock provider не выполняет внешний network call.
            }

            default -> throw new IllegalStateException(
                    "Неизвестный AI provider: "
                            + providerProperties.provider()
            );
        }
    }

    private void validateBudget(
            String provider,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        Duration attemptBudget =
                safeAdd(
                        Objects.requireNonNull(
                                connectTimeout,
                                "connectTimeout не должен быть null"
                        ),
                        Objects.requireNonNull(
                                readTimeout,
                                "readTimeout не должен быть null"
                        )
                );

        Duration totalTimeout =
                retryProperties.effectiveTotalTimeout();

        if (attemptBudget.compareTo(totalTimeout) > 0) {
            throw new IllegalStateException(
                    "Некорректный timeout budget для AI provider "
                            + provider
                            + ": connectTimeout + readTimeout = "
                            + attemptBudget
                            + ", а safeai.ai.retry.total-timeout = "
                            + totalTimeout
                            + ". total-timeout должен быть не меньше "
                            + "максимального budget одного provider attempt"
            );
        }
    }

    private static Duration safeAdd(
            Duration left,
            Duration right
    ) {
        try {
            return left.plus(right);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "AI provider timeout budget переполнен",
                    exception
            );
        }
    }

    private static <T> T requireSelectedProviderProperties(
            T properties,
            String providerDisplayName
    ) {
        if (properties == null) {
            throw new IllegalStateException(
                    providerDisplayName
                            + " properties bean отсутствует "
                            + "для выбранного AI provider"
            );
        }

        return properties;
    }
}
