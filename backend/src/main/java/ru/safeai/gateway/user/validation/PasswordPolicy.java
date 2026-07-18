package ru.safeai.gateway.user.validation;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BCRYPT_BYTES = 72;

    public static final String MESSAGE =
            "Пароль должен содержать минимум 12 символов, строчную букву, "
                    + "заглавную букву, цифру и спецсимвол, не содержать "
                    + "управляющих символов и занимать не более 72 байт UTF-8";

    private PasswordPolicy() {
    }

    public static boolean hasValidUtf8Length(String password) {
        return password != null
                && password.getBytes(StandardCharsets.UTF_8).length
                <= MAX_BCRYPT_BYTES;
    }
}
