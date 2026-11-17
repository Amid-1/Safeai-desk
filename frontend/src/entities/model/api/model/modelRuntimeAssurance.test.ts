import {describe, expect, it} from 'vitest'
import {parseRuntimeModelStatus} from './modelRuntimeParsers'
import {createRuntimeModelStatus} from '../../../../test/fixtures/modelControlPlaneTestFixtures'

describe('provider data assurance wire contract', () => {
    it('preserves independent guarantees and verification dates', () => {
        const dataAssurance = {
            noTraining: true,
            zeroDataRetention: false,
            source: 'CONTRACT-42',
            verifiedAt: '2026-11-17T12:00:00Z',
            expiresAt: '2026-12-17T12:00:00Z',
        }
        expect(parseRuntimeModelStatus({...createRuntimeModelStatus(), dataAssurance}).dataAssurance)
            .toEqual(dataAssurance)
    })

    it('never invents assurance for an older server', () => {
        expect(parseRuntimeModelStatus(createRuntimeModelStatus()).dataAssurance).toBeUndefined()
    })

    it('rejects malformed proof instead of coercing strings into confirmation', () => {
        expect(() => parseRuntimeModelStatus({...createRuntimeModelStatus(), dataAssurance: {
            noTraining: 'true', zeroDataRetention: true, source: 'CONTRACT-42',
            verifiedAt: null, expiresAt: null,
        }})).toThrow()
        expect(() => parseRuntimeModelStatus({...createRuntimeModelStatus(), dataAssurance: {
            noTraining: true, zeroDataRetention: true, source: 'CONTRACT-42',
            verifiedAt: 'not-a-date', expiresAt: null,
        }})).toThrow()
    })
})
