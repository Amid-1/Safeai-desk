// frontend/src/utils/frontendErrorReporting.ts
import type {
    ErrorInfo,
} from 'react'

import {
    createSecureUuid,
} from './secureUuid'

type FrontendIncident = {
    incidentId: string
    type: 'REACT_RENDER_ERROR'
    errorName: string
    componentPath: string[]
    routePath: string
    release: string | null
    occurredAt: string
}

const FRONTEND_ERROR_EVENT =
    'safeai:frontend-error'

export function reportReactRenderError(
    error: Error,
    errorInfo: ErrorInfo,
): string {
    const incidentId =
        createIncidentIdSafely()

    if (import.meta.env.DEV) {
        console.error(
            'React error boundary caught error',
            error,
            errorInfo,
        )

        return incidentId
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
            getSafeRoutePath(),

        release:
            normalizeRelease(
                import.meta.env
                    .VITE_APP_VERSION,
            ),

        occurredAt:
            new Date().toISOString(),
    }

    publishIncidentSafely(incident)

    // В production выводится только безопасный ID.
    // Error, stack, props и response body в console не попадают.
    console.error(
        `Frontend incident: ${incidentId}`,
    )

    return incidentId
}

function publishIncidentSafely(
    incident: FrontendIncident,
): void {
    try {
        window.dispatchEvent(
            new CustomEvent<FrontendIncident>(
                FRONTEND_ERROR_EVENT,
                {
                    detail: incident,
                },
            ),
        )
    } catch {
        // Ошибка telemetry-механизма не должна
        // создавать вторую ошибку внутри ErrorBoundary.
    }
}

function createIncidentIdSafely(): string {
    try {
        return createSecureUuid()
    } catch {
        // Incident ID не является credential или auth token.
        // Этот fallback используется только при отсутствии Web Crypto.
        return `incident-${Date.now().toString(36)}`
    }
}

function sanitizeErrorName(
    value: string,
): string {
    const normalized =
        value.trim()

    if (
        !/^[A-Za-z][A-Za-z0-9]*$/.test(
            normalized,
        )
    ) {
        return 'Error'
    }

    return normalized.slice(0, 64)
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
        .map(extractComponentName)
        .filter(
            (
                componentName,
            ): componentName is string =>
                componentName !== null,
        )
        .slice(0, 25)
}

function extractComponentName(
    stackLine: string,
): string | null {
    const match =
        /\bat\s+([A-Za-z0-9_$.-]+)/.exec(
            stackLine,
        )

    const componentName =
        match?.[1]?.trim()

    if (!componentName) {
        return null
    }

    return componentName.slice(
        0,
        128,
    )
}

function getSafeRoutePath(): string {
    if (
        typeof window === 'undefined'
    ) {
        return '/'
    }

    const pathname =
        window.location.pathname

    if (!pathname) {
        return '/'
    }

    // Query string и hash намеренно не включаются:
    // в них могут находиться пользовательские данные.
    return pathname.slice(0, 2_048)
}

function normalizeRelease(
    value: unknown,
): string | null {
    if (
        typeof value !== 'string'
    ) {
        return null
    }

    const normalized =
        value.trim()

    if (!normalized) {
        return null
    }

    return normalized.slice(0, 100)
}