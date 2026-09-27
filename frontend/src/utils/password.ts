// frontend/src/utils/password.ts
const MIN_PASSWORD_CHARACTERS = 12
const MAX_PASSWORD_UTF8_BYTES = 72

const UNICODE_LOWERCASE =
    /\p{Lowercase}/u

const UNICODE_UPPERCASE =
    /\p{Uppercase}/u

const UNICODE_DIGIT =
    /\p{Decimal_Number}/u

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

    let hasLowercase = false
    let hasUppercase = false
    let hasDigit = false
    let hasSpecial = false
    let hasIsoControl = false

    for (const character of password) {
        const codePoint =
            character.codePointAt(0)

        if (
            codePoint !== undefined
            && isIsoControl(
                codePoint,
            )
        ) {
            hasIsoControl = true
            continue
        }

        if (
            UNICODE_LOWERCASE.test(
                character,
            )
        ) {
            hasLowercase = true
        } else if (
            UNICODE_UPPERCASE.test(
                character,
            )
        ) {
            hasUppercase = true
        } else if (
            UNICODE_DIGIT.test(
                character,
            )
        ) {
            hasDigit = true
        } else {
            /*
             * Mirrors backend PasswordPolicy:
             * any non-control code point that is not lower/upper/digit
             * satisfies the "special" category.
             */
            hasSpecial = true
        }
    }

    if (!hasLowercase) {
        missing.push(
            'строчную букву',
        )
    }

    if (!hasUppercase) {
        missing.push(
            'заглавную букву',
        )
    }

    if (!hasDigit) {
        missing.push(
            'цифру',
        )
    }

    if (!hasSpecial) {
        missing.push(
            'спецсимвол',
        )
    }

    if (hasIsoControl) {
        missing.push(
            'не содержать управляющих символов',
        )
    }

    return missing.length > 0
        ? (
            'Пароль должен содержать: '
            + `${missing.join(', ')}.`
        )
        : null
}

function isIsoControl(
    codePoint: number,
): boolean {
    return (
        codePoint >= 0x0000
        && codePoint <= 0x001f
    ) || (
        codePoint >= 0x007f
        && codePoint <= 0x009f
    )
}
