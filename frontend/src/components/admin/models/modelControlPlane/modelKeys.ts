// frontend/src/components/admin/models/modelControlPlane/modelKeys.ts
const MODEL_KEY_PATTERN = /^[a-z0-9][a-z0-9._:/-]{0,159}$/


export function normalizeModelKey(
    value: string,
): string {
    const normalized =
        value.trim().toLowerCase()

    if (!MODEL_KEY_PATTERN.test(normalized)) {
        throw new Error(
            'Ключ модели может содержать только латинские буквы, цифры и символы . _ : / -; максимум 160 символов.',
        )
    }

    return normalized
}

export function parseModelKeyList(
    value: string,
): string[] {
    const keys = value
        .split(/[\n\r,;]+/)
        .map((item) => item.trim())
        .filter(Boolean)
        .map(normalizeModelKey)

    return Array.from(
        new Set(keys),
    )
}
