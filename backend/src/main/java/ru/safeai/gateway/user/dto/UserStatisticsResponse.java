package ru.safeai.gateway.user.dto;

public record UserStatisticsResponse(
        long total,
        long administrators,
        long users,
        long enabled,
        long disabled
) {
}
