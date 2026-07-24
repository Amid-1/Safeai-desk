package ru.safeai.gateway.auth.service;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(
        prefix = "safeai.auth.refresh-cleanup"
)
public record RefreshTokenCleanupProperties(

        @DefaultValue("500")
        @Min(
                value = 1,
                message = "batch-size должен быть положительным"
        )
        @Max(
                value = 10_000,
                message = "batch-size не должен превышать 10000"
        )
        int batchSize,

        @DefaultValue("100")
        @Min(
                value = 1,
                message = "max-batches-per-run должен быть положительным"
        )
        @Max(
                value = 10_000,
                message = "max-batches-per-run не должен превышать 10000"
        )
        int maxBatchesPerRun
) {
}