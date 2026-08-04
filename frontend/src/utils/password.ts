const MIN_PASSWORD_CHARACTERS = 12
const MAX_PASSWORD_UTF8_BYTES = 72

const ASCII_LOWERCASE = /[a-z]/
const ASCII_UPPERCASE = /[A-Z]/
const ASCII_DIGIT = /\d/
const ASCII_SPECIAL =
    /[!"#$%&'()*+,\-./:;<=>?@[\\\]^_`{|}~]/

export function utf8ByteLength(
    value: string,
): number {
    return new TextEncoder()
        .encode(value)
        .byteLength
}

export function validatePassword(
    password: string,
): string | null {
    if (!password) {
        return 'Введите пароль.'
    }

    const missing: string[] = []

    if (
        Array.from(password).length
        < MIN_PASSWORD_CHARACTERS
    ) {
        missing.push(
            `минимум ${MIN_PASSWORD_CHARACTERS} символов`,
        )
    }

    if (
        utf8ByteLength(password)
        > MAX_PASSWORD_UTF8_BYTES
    ) {
        missing.push(
            `не более ${MAX_PASSWORD_UTF8_BYTES} байт в UTF-8`,
        )
    }

    if (!ASCII_LOWERCASE.test(password)) {
        missing.push(
            'латинскую строчную букву',
        )
    }

    if (!ASCII_UPPERCASE.test(password)) {
        missing.push(
            'латинскую заглавную букву',
        )
    }

    if (!ASCII_DIGIT.test(password)) {
        missing.push('цифру')
    }

    if (!ASCII_SPECIAL.test(password)) {
        missing.push(
            'ASCII-спецсимвол',
        )
    }

    return missing.length > 0
        ? (
            'Пароль должен содержать: '
            + `${missing.join(', ')}.`
        )
        : null
}
