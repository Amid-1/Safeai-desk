import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './http'
import {
    getOrganizationDisableImpact,
    type OrganizationDisableImpact,
} from './organizationApi'

vi.mock('./http', async (importOriginal) => {
    const actual = await importOriginal<typeof import('./http')>()
    return { ...actual, apiRequest: vi.fn() }
})

const ORG_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const serverResponse = {
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

// An old server legitimately returns only the original six fields.
const legacyResponse = {
    organizationId: ORG_ID,
    organizationVersion: 3,
    enabledUsers: 0,
    administrators: 0,
    activeRefreshSessions: 0,
    activeChatOperations: 0,
} as const satisfies OrganizationDisableImpact

beforeEach(() => {
    vi.resetAllMocks()
})

describe('Organization disable-impact contract', () => {
    it('passes the full advisory response through without inventing values', async () => {
        vi.mocked(apiRequest).mockResolvedValue(serverResponse)

        const impact = await getOrganizationDisableImpact(ORG_ID)

        expect(impact).toEqual(serverResponse)
        expect(apiRequest).toHaveBeenCalledWith(
            `/api/organizations/${ORG_ID}/disable-impact`,
            expect.objectContaining({ method: 'GET' }),
        )
    })

    it('keeps legacy absence distinguishable from genuine zero counts', async () => {
        vi.mocked(apiRequest).mockResolvedValue(legacyResponse)

        const impact = await getOrganizationDisableImpact(ORG_ID)

        expect(impact).toEqual(legacyResponse)
        expect(impact.startedPhysicalAttempts).toBeUndefined()
        expect(impact.uncertainPhysicalAttempts).toBeUndefined()
        expect(impact.advisoryOnly).toBeUndefined()
        expect(Object.prototype.hasOwnProperty.call(impact, 'startedPhysicalAttempts')).toBe(false)
    })

    it('retains confirmed zeros in a complete extended response', async () => {
        vi.mocked(apiRequest).mockResolvedValue({
            ...serverResponse,
            startedPhysicalAttempts: 0,
            uncertainPhysicalAttempts: 0,
        })

        const impact = await getOrganizationDisableImpact(ORG_ID)

        expect(impact.startedPhysicalAttempts).toBe(0)
        expect(impact.uncertainPhysicalAttempts).toBe(0)
        expect(impact.advisoryOnly).toBe(true)
    })

    it.each([
        { ...serverResponse, startedPhysicalAttempts: -1 },
        { ...serverResponse, uncertainPhysicalAttempts: '1' },
        { ...serverResponse, startedPhysicalAttempts: Number.MAX_SAFE_INTEGER + 1 },
        { ...serverResponse, advisoryOnly: 'true' },
        { ...serverResponse, advisoryOnly: false },
        { ...serverResponse, startedPhysicalAttempts: null },
        { ...serverResponse, uncertainPhysicalAttempts: NaN },
        { ...serverResponse, organizationVersion: -1 },
        { ...serverResponse, activeChatOperations: '0' },
    ])('rejects malformed advisory snapshot (fail closed)', async invalid => {
        vi.mocked(apiRequest).mockResolvedValue(invalid)

        await expect(getOrganizationDisableImpact(ORG_ID)).rejects.toThrow()
    })

    it.each([
        { ...legacyResponse, startedPhysicalAttempts: 0 },
        { ...legacyResponse, uncertainPhysicalAttempts: 0 },
        { ...legacyResponse, advisoryOnly: true },
        { ...serverResponse, advisoryOnly: undefined },
        { ...serverResponse, startedPhysicalAttempts: undefined },
    ])('rejects incomplete extended contract instead of assuming legacy', async incomplete => {
        vi.mocked(apiRequest).mockResolvedValue(incomplete)

        await expect(getOrganizationDisableImpact(ORG_ID)).rejects.toThrow()
    })
})
