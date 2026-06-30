package ru.safeai.gateway.user.validation;

public final class PasswordPolicy {

    public static final String REGEX =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{12,72}$";

    public static final String MESSAGE =
            "Пароль должен содержать минимум 12 символов, строчную букву, заглавную букву, цифру и спецсимвол";

    private PasswordPolicy() {
    }
}