package ru.safeai.gateway.common.exception;

import lombok.Getter;

import java.util.UUID;

@Getter
public class RefreshTokenReuseDetectedException extends InvalidRefreshTokenException {

    private final UUID userId;
    private final UUID organizationId;
    private final UUID tokenFamilyId;

    public RefreshTokenReuseDetectedException(
            String message,
            UUID userId,
            UUID organizationId,
            UUID tokenFamilyId
    ) {
        super(message);
        this.userId = userId;
        this.organizationId = organizationId;
        this.tokenFamilyId = tokenFamilyId;
    }
}