export function createSecureUuid(): string {
    const webCrypto = globalThis.crypto

    if (!webCrypto) {
        throw new Error(
            'Secure browser crypto API is required',
        )
    }

    if (
        typeof webCrypto.randomUUID
            === 'function'
    ) {
        return webCrypto.randomUUID()
    }

    if (
        typeof webCrypto.getRandomValues
            !== 'function'
    ) {
        throw new Error(
            'Secure browser crypto API is required',
        )
    }

    const bytes = new Uint8Array(16)

    webCrypto.getRandomValues(bytes)

    bytes[6] =
        ((bytes[6] ?? 0) & 0x0f)
        | 0x40

    bytes[8] =
        ((bytes[8] ?? 0) & 0x3f)
        | 0x80

    const hex = Array.from(
        bytes,
        (byte) =>
            byte
                .toString(16)
                .padStart(2, '0'),
    ).join('')

    return [
        hex.slice(0, 8),
        hex.slice(8, 12),
        hex.slice(12, 16),
        hex.slice(16, 20),
        hex.slice(20),
    ].join('-')
}