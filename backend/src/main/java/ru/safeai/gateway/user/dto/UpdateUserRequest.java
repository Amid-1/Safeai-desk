// Safeai-desk/backend/src/main/java/ru/safeai/gateway/user/dto/UpdateUserRequest.java
package ru.safeai.gateway.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @Size(max = 255)
        String fullName
) {
}