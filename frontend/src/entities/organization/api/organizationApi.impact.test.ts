// frontend/src/entities/organization/api/organizationApi.impact.test.ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest, API_TIMEOUTS } from '../../../shared/api/http'
import {
    getOrganizationDisableImpact,
    type OrganizationDisableImpact,
} from './organizationApi'

vi.mock('../../../shared/api/http', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../../../shared/api/http')>()

    return {
        ...actual,
        apiRequest: vi.fn(),
    }
})

const ORG_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

const fullResponse = {
    organizationId: ORG_ID,
    organizationVersion: 3,
    enabledUsers: 0,
    administrators: 0,
    activeRefreshSessions: 0,
    activeChatOperations: 0,
    startedPhysicalAttempts: 2,
    uncertainPhysicalAttempts: 1,
    advisoryOnly: true,
} as const satisfies OrganizationDisableImpact

// Older server versions return only the six required core fields.
const legacyResponse = {
    organizationId: ORG_ID,
    organizationVersion: 3,
    enabledUsers: 0,
    administrators: 0,
    activeRefreshSessions: 0,
    activeChatOperations: 0,
} as const satisfies OrganizationDisableImpact

const requestMock = vi.mocked(apiRequest)

beforeEach(() => {
    vi.resetAllMocks()
})

describe('Organization disable-impact API contract', () => {
    it('returns the complete advisory snapshot without changing server values', async () => {
        requestMock.mockResolvedValue(fullResponse)

        const impact = await getOrganizationDisableImpact(ORG_ID)

        expect(impact).toEqual(fullResponse)
        expect(requestMock).toHaveBeenCalledTimes(1)
        expect(requestMock).toHaveBeenCalledWith(
            `/api/organizations/${ORG_ID}/disable-impact`,
            {
                method: 'GET',
                signal: undefined,
                timeoutMs: API_TIMEOUTS.default,
            },
        )
    })

    it('forwards cancellation to the HTTP layer', async () => {
        requestMock.mockResolvedValue(fullResponse)
        const controller = new AbortController()

        await getOrganizationDisableImpact(ORG_ID, {
            signal: controller.signal,
        })

        expect(requestMock).toHaveBeenCalledWith(
            `/api/organizations/${ORG_ID}/disable-impact`,
            expect.objectContaining({
                method: 'GET',
                signal: controller.signal,
            }),
        )
    })

    it('preserves legacy absence instead of manufacturing zero counters', async () => {
        requestMock.mockResolvedValue(legacyResponse)

        const impact = await getOrganizationDisableImpact(ORG_ID)

        expect(impact).toEqual(legacyResponse)
        expect(impact.startedPhysicalAttempts).toBeUndefined()
        expect(impact.uncertainPhysicalAttempts).toBeUndefined()
        expect(impact.advisoryOnly).toBeUndefined()
        for (const key of [
            'startedPhysicalAttempts',
            'uncertainPhysicalAttempts',
            'advisoryOnly',
        ]) {
            expect(Object.hasOwn(impact, key)).toBe(false)
        }
    })

    it('preserves genuine zero counts in a complete advisory response', async () => {
        requestMock.mockResolvedValue({
            ...fullResponse,
            startedPhysicalAttempts: 0,
            uncertainPhysicalAttempts: 0,
        })

        const impact = await getOrganizationDisableImpact(ORG_ID)

        expect(impact.startedPhysicalAttempts).toBe(0)
        expect(impact.uncertainPhysicalAttempts).toBe(0)
        expect(impact.advisoryOnly).toBe(true)
    })

    it.each([
        { ...fullResponse, startedPhysicalAttempts: -1 },
        { ...fullResponse, startedPhysicalAttempts: 1.5 },
        { ...fullResponse, startedPhysicalAttempts: null },
        { ...fullResponse, startedPhysicalAttempts: Infinity },
        { ...fullResponse, startedPhysicalAttempts: Number.MAX_SAFE_INTEGER + 1 },
        { ...fullResponse, uncertainPhysicalAttempts: '1' },
        { ...fullResponse, uncertainPhysicalAttempts: NaN },
        { ...fullResponse, advisoryOnly: 'true' },
        { ...fullResponse, advisoryOnly: false },
        { ...fullResponse, organizationVersion: -1 },
        { ...fullResponse, activeChatOperations: '0' },
        { ...fullResponse, enabledUsers: -1 },
        { ...fullResponse, organizationId: 'not-a-uuid' },
    ])('rejects malformed response values without falling back to defaults', async (invalid) => {
        requestMock.mockResolvedValue(invalid)

        await expect(getOrganizationDisableImpact(ORG_ID)).rejects.toThrow()
    })

    it.each([
        { ...legacyResponse, startedPhysicalAttempts: 0 },
        { ...legacyResponse, uncertainPhysicalAttempts: 0 },
        { ...legacyResponse, advisoryOnly: true },
        { ...fullResponse, startedPhysicalAttempts: undefined },
        { ...fullResponse, uncertainPhysicalAttempts: undefined },
        { ...fullResponse, advisoryOnly: undefined },
        { ...legacyResponse, startedPhysicalAttempts: undefined },
        { ...legacyResponse, uncertainPhysicalAttempts: undefined },
        { ...legacyResponse, advisoryOnly: undefined },
    ])('rejects partial or explicitly undefined extended fields', async (incomplete) => {
        requestMock.mockResolvedValue(incomplete)

        await expect(getOrganizationDisableImpact(ORG_ID)).rejects.toThrow()
    })

    it.each([
        { invalid: null },
        { invalid: [] },
        { invalid: {} },
        { invalid: { ...legacyResponse, organizationId: undefined } },
        { invalid: { ...legacyResponse, organizationVersion: undefined } },
        { invalid: { ...legacyResponse, administrators: null } },
        { invalid: { ...legacyResponse, activeRefreshSessions: 0.5 } },
    ])('rejects malformed core snapshots, including legacy ones', async ({ invalid }) => {
        requestMock.mockResolvedValue(invalid)

        await expect(getOrganizationDisableImpact(ORG_ID)).rejects.toThrow()
    })

    it('does not swallow a failure from the HTTP layer', async () => {
        const failure = new Error('Network unavailable')
        requestMock.mockRejectedValue(failure)

        await expect(getOrganizationDisableImpact(ORG_ID)).rejects.toBe(failure)
    })
})
