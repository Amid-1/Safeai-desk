import {
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    parseRuntimeModelProbe,
    probeRuntimeModel,
} from './modelApi'
import {
    apiRequest,
} from './http'

vi.mock('./http', () => ({
    apiRequest: vi.fn(),
}))

describe('runtime model probe contract', () => {
    it('accepts sanitized probe response', () => {
        expect(
            parseRuntimeModelProbe({
                provider: 'mock',
                model: 'mock-safeai',
                status: 'AVAILABLE',
                checkedAt: '2026-09-05T18:00:00Z',
                latencyMs: 0,
                httpStatus: null,
                message: 'Локальный mock provider доступен',
            }),
        ).toMatchObject({
            provider: 'mock',
            model: 'mock-safeai',
            status: 'AVAILABLE',
            latencyMs: 0,
        })
    })

    it('rejects unknown status', () => {
        expect(() =>
            parseRuntimeModelProbe({
                provider: 'mock',
                model: 'mock-safeai',
                status: 'UNKNOWN',
                checkedAt: '2026-09-05T18:00:00Z',
                latencyMs: 0,
                httpStatus: null,
                message: 'x',
            }),
        ).toThrow()
    })

    it('rejects unsafe HTTP status values', () => {
        expect(() =>
            parseRuntimeModelProbe({
                provider: 'mock',
                model: 'mock-safeai',
                status: 'ERROR',
                checkedAt: '2026-09-05T18:00:00Z',
                latencyMs: 0,
                httpStatus: 900,
                message: 'x',
            }),
        ).toThrow()
    })

    it('POST probe sends no customer JSON payload', async () => {
        vi.mocked(apiRequest)
            .mockResolvedValueOnce({
                provider: 'mock',
                model: 'mock-safeai',
                status: 'AVAILABLE',
                checkedAt: '2026-09-05T18:00:00Z',
                latencyMs: 0,
                httpStatus: null,
                message: 'Локальный mock provider доступен',
            })

        await probeRuntimeModel()

        expect(apiRequest)
            .toHaveBeenCalledWith(
                '/api/admin/models/runtime/probe',
                {
                    method: 'POST',
                    signal: undefined,
                },
            )
    })
})
