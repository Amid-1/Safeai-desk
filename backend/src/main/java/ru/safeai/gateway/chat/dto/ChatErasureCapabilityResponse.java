package ru.safeai.gateway.chat.dto;

public record ChatErasureCapabilityResponse(boolean allowed, String reason, long version) {
}
