package ru.safeai.gateway.user.validation;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;

    /**
     * BCrypt обрабатывает максимум 72 байта входного пароля.
     */
    public static final int MAX_BCRYPT_BYTES = 72;

    public static final String MESSAGE =
            "Пароль должен содержать минимум 12 символов, "
                    + "строчную букву, заглавную букву, цифру "
                    + "и спецсимвол, не содержать управляющих "
                    + "символов и занимать не более 72 байт UTF-8";

    public static final String BCRYPT_LENGTH_MESSAGE =
            "Пароль не должен превышать 72 байта в UTF-8";

    private PasswordPolicy() {
    }

    public static boolean isValidNewPassword(
            String password
    ) {
        if (password == null || password.isBlank()) {
            return false;
        }

        int codePointCount =
                password.codePointCount(
                        0,
                        password.length()
                );

        if (codePointCount < MIN_LENGTH) {
            return false;
        }

        if (!hasValidUtf8Length(password)) {
            return false;
        }

        boolean hasLowercase = false;
        boolean hasUppercase = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (int offset = 0;
             offset < password.length();) {

            int codePoint =
                    password.codePointAt(offset);

            if (Character.isISOControl(codePoint)) {
                return false;
            }

            if (Character.isLowerCase(codePoint)) {
                hasLowercase = true;
            } else if (Character.isUpperCase(codePoint)) {
                hasUppercase = true;
            } else if (Character.isDigit(codePoint)) {
                hasDigit = true;
            } else {
                hasSpecial = true;
            }

            offset +=
                    Character.charCount(
                            codePoint
                    );
        }

        return hasLowercase
                && hasUppercase
                && hasDigit
                && hasSpecial;
    }

    public static boolean hasValidUtf8Length(
            String password
    ) {
        return password != null
                && utf8Length(password)
                <= MAX_BCRYPT_BYTES;
    }

    public static int utf8Length(
            String password
    ) {
        if (password == null) {
            return 0;
        }

        return password
                .getBytes(
                        StandardCharsets.UTF_8
                )
                .length;
    }
}