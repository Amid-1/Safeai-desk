package ru.safeai.gateway.common.exception;

import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ValidationErrorExtractor {

    Map<String, List<String>> bindingErrors(
            BindingResult bindingResult
    ) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        for (FieldError error : bindingResult.getFieldErrors()) {
            addError(
                    errors,
                    error.getField(),
                    defaultMessage(error)
            );
        }

        for (ObjectError error : bindingResult.getGlobalErrors()) {
            addError(
                    errors,
                    "_global",
                    defaultMessage(error)
            );
        }

        return errors;
    }

    void addError(
            Map<String, List<String>> errors,
            String field,
            String message
    ) {
        String normalizedField =
                field == null || field.isBlank()
                        ? "_global"
                        : field.trim();
        String normalizedMessage =
                message == null || message.isBlank()
                        ? "Некорректное значение"
                        : message.trim();

        List<String> messages = errors.computeIfAbsent(
                normalizedField,
                ignored -> new ArrayList<>()
        );

        if (!messages.contains(normalizedMessage)) {
            messages.add(normalizedMessage);
        }
    }

    String parameterName(
            ParameterValidationResult result
    ) {
        String parameterName = result
                .getMethodParameter()
                .getParameterName();

        String base = parameterName == null || parameterName.isBlank()
                ? "parameter"
                : parameterName;

        if (result.getContainerIndex() != null) {
            return base + "[" + result.getContainerIndex() + "]";
        }

        if (result.getContainerKey() != null) {
            return base + "[" + result.getContainerKey() + "]";
        }

        return base;
    }

    /**
     * Does not depend on Path#toString(), so Bean Validation textual
     * representation changes cannot alter the public field-error contract.
     */
    String normalizeConstraintPath(
            Path path
    ) {
        if (path == null) {
            return "_global";
        }

        List<String> properties = new ArrayList<>();
        String parameterFallback = null;

        for (Path.Node node : path) {
            ElementKind kind = node.getKind();
            String name = node.getName();

            if (kind == ElementKind.PARAMETER
                    && name != null
                    && !name.isBlank()) {
                parameterFallback = name.trim();
                continue;
            }

            if ((kind == ElementKind.PROPERTY
                    || kind == ElementKind.CONTAINER_ELEMENT)
                    && name != null
                    && !name.isBlank()
                    && !name.startsWith("<")) {
                properties.add(name.trim());
            }
        }

        if (!properties.isEmpty()) {
            return String.join(".", properties);
        }

        return Objects.requireNonNullElse(
                parameterFallback,
                "_global"
        );
    }

    String defaultMessage(
            MessageSourceResolvable error
    ) {
        String message = error.getDefaultMessage();
        return message == null || message.isBlank()
                ? "Некорректное значение"
                : message;
    }
}
