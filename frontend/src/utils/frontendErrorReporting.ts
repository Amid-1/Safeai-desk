import type {
    ErrorInfo,
} from 'react'

export type FrontendIncident = {
    incidentId: string
    type: 'REACT_RENDER_ERROR'
    errorName: string
    componentPath: string[]
    routePath: string
    release: string | null
    occurredAt: string
}

export type FrontendErrorReporter =
    (
        incident: FrontendIncident,
    ) => void | Promise<void>

let productionReporter:
    FrontendErrorReporter | null = null

let fallbackIncidentCounter = 0

export function registerFrontendErrorReporter(
    reporter:
        FrontendErrorReporter | null,
): void {
    productionReporter = reporter
}

export function createFrontendIncidentId():
    string {
    const webCrypto =
        globalThis.crypto

    if (
        webCrypto
        && typeof webCrypto.randomUUID
            === 'function'
    ) {
        return webCrypto.randomUUID()
    }

    if (
        webCrypto
        && typeof webCrypto.getRandomValues
            === 'function'
    ) {
        const bytes =
            new Uint8Array(16)

        webCrypto.getRandomValues(bytes)

        bytes[6] =
            ((bytes[6] ?? 0) & 0x0f)
            | 0x40

        bytes[8] =
            ((bytes[8] ?? 0) & 0x3f)
            | 0x80

        const hex = [...bytes]
            .map(
                (byte) =>
                    byte
                        .toString(16)
                        .padStart(2, '0'),
            )
            .join('')

        return [
            hex.slice(0, 8),
            hex.slice(8, 12),
            hex.slice(12, 16),
            hex.slice(16, 20),
            hex.slice(20),
        ].join('-')
    }

    fallbackIncidentCounter += 1

    return [
        'incident',
        Date.now().toString(36),
        fallbackIncidentCounter
            .toString(36),
    ].join('-')
}

export function reportReactRenderError(
    incidentId: string,
    error: Error,
    errorInfo: ErrorInfo,
): void {
    if (import.meta.env.DEV) {
        console.error(
            'React error boundary caught error',
            error,
            errorInfo,
        )
        return
    }

    const reporter =
        productionReporter

    if (!reporter) {
        // В production raw error не пишется.
        // В консоль попадает только безопасный incident ID.
        console.error(
            `Frontend incident: ${incidentId}`,
        )
        return
    }

    const incident:
        FrontendIncident = {
        incidentId,
        type: 'REACT_RENDER_ERROR',
        errorName:
            sanitizeErrorName(
                error.name,
            ),
        componentPath:
            parseComponentPath(
                errorInfo.componentStack,
            ),
        routePath:
            window.location.pathname,
        release:
            normalizeRelease(
                import.meta.env
                    .VITE_APP_VERSION,
            ),
        occurredAt:
            new Date().toISOString(),
    }

    try {
        void Promise.resolve(
            reporter(incident),
        ).catch(() => {
            console.error(
                `Frontend incident reporting failed: ${incidentId}`,
            )
        })
    } catch {
        console.error(
            `Frontend incident reporting failed: ${incidentId}`,
        )
    }
}

function sanitizeErrorName(
    value: string,
): string {
    return /^[A-Za-z][A-Za-z0-9]*$/.test(
        value,
    )
        ? value.slice(0, 64)
        : 'Error'
}

function parseComponentPath(
    componentStack:
        string | null | undefined,
): string[] {
    if (!componentStack) {
        return []
    }

    return componentStack
        .split('\n')
        .map((line) => {
            const match =
                /\bat\s+([A-Za-z0-9_$.-]+)/.exec(
                    line,
                )

            return match?.[1] ?? ''
        })
        .filter(Boolean)
        .slice(0, 25)
}

function normalizeRelease(
    value: unknown,
): string | null {
    if (
        typeof value !== 'string'
    ) {
        return null
    }

    const normalized = value.trim()

    return normalized
        ? normalized.slice(0, 100)
        : null
}
