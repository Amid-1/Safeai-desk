// ============================================================
// frontend/src/api/modelApi.exports.test.ts
// ============================================================
import {
    describe,
    expect,
    it,
} from 'vitest'

import {
    BUDGET_ENFORCEMENTS,
    MODEL_CAPABILITIES,
    MODEL_LIFECYCLES,
    MODEL_MODALITIES,
    MODEL_PRICING_STATUSES,
    MODEL_RETENTION_STATUSES,
    MODEL_TRAINING_USE_STATUSES,
} from './modelApi'

describe('modelApi public UI runtime exports', () => {
    it('exports all enum value arrays consumed by model UI modules', () => {
        expect(MODEL_CAPABILITIES).toContain('VISION')
        expect(MODEL_LIFECYCLES).toContain('ACTIVE')
        expect(MODEL_MODALITIES).toContain('TEXT')
        expect(MODEL_PRICING_STATUSES).toContain('CONFIGURED')
        expect(MODEL_RETENTION_STATUSES).toContain('ZERO_DATA_RETENTION')
        expect(MODEL_TRAINING_USE_STATUSES).toContain('CONTRACTUAL_NO_TRAINING')
        expect(BUDGET_ENFORCEMENTS).toEqual(['SOFT', 'HARD'])
    })
})
