import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    getRuntimeModelStatus,
} from './modelApi'
import {
    apiRequest,
} from './http'

vi.mock('./http', () => ({
    API_TIMEOUTS: {default: 30_000},
    apiRequest: vi.fn(),
}))

describe('modelApi contract', () => {
    it('parses a Jackson numeric pricing field without losing the runtime contract', async () => {
        vi.mocked(apiRequest).mockResolvedValueOnce({
            provider: 'openai',
            model: 'gpt-4.1',
            enabled: true,
            routingMode: 'SINGLE_PROVIDER_STATIC',
            maxInputTokens: 64000,
            maxOutputTokens: 2048,
            toolsSupported: false,
            visionSupported: false,
            structuredOutputSupported: false,
            dataRetentionStatus: 'NOT_DECLARED',
            healthStatus: 'NOT_PROBED',
            pricingStatus: 'CONFIGURED',
            inputUsdPer1mTokens: 2,
            outputUsdPer1mTokens: 8,
            pricingVersion: 'openai-2026-07',
        })

        await expect(getRuntimeModelStatus()).resolves.toMatchObject({
            provider: 'openai',
            inputUsdPer1mTokens: '2',
            outputUsdPer1mTokens: '8',
        })
    })
})
