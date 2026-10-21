/* frontend/src/components/admin/models/modelControlPlaneSupport.production.test.ts */
import { describe, expect, it } from 'vitest'
import {
    buildCatalogRequest, buildPolicyRequest, compareDecimalStrings,
    createCatalogDraft, createPolicyDraft, decimalToScale12, isZeroOrNull,
    normalizeDraftForPricingStatus, normalizeJsonObject,
    normalizeModelKey, parseModelKeyList, parseOptionalDecimal,
    parseOptionalNonNegativeInteger, parseOptionalPositiveInteger,
    parsePositiveInteger, validatePricingDraft,
} from './modelControlPlaneSupport'
import { createModelCatalogEntry, createOrganizationModelPolicy, createRuntimeModelStatus } from './ModelControlPlaneTestFixtures'

describe('modelControlPlaneSupport — строгие контракты', () => {
    it.each([
        ['', false], [' openai:gpt-5 ', true], ['a'.repeat(161), false],
        ['openai:gpt 5', false], ['./evil', false], ['MOCK:MODEL', true],
    ])('проверяет ключ %s', (value, valid) => {
        if (valid) expect(normalizeModelKey(value)).toBe(value.trim().toLowerCase())
        else expect(() => normalizeModelKey(value)).toThrow()
    })

    it('дедуплицирует списки после нормализации, сохраняя порядок', () => {
        expect(parseModelKeyList(' A:ONE;B:TWO, a:one\nb:two ')).toEqual(['a:one', 'b:two'])
    })

    it.each(['0', '-1', '1.5', 'Infinity', '9007199254740993', 'NaN'])('не принимает %s как положительный safe integer', value => {
        expect(() => parsePositiveInteger(value, 'Лимит')).toThrow()
    })

    it('обрабатывает nullable целые без подмены нуля пропуском', () => {
        expect(parseOptionalPositiveInteger('   ', 'Лимит')).toBeNull()
        expect(parseOptionalNonNegativeInteger('0', 'Срок')).toBe(0)
        expect(() => parseOptionalNonNegativeInteger('-1', 'Срок')).toThrow()
    })

    it.each(['-1', '1e-9', '1.0000000000001', '9999999999999999999', '1,5', 'NaN'])('отклоняет некорректный денежный ввод %s', value => {
        expect(() => parseOptionalDecimal(value, 'Цена')).toThrow()
    })

    it('сравнивает очень маленькие цены без Number и округления', () => {
        expect(compareDecimalStrings('0', '0.000000000001')).toBe(-1)
        expect(compareDecimalStrings('9007199254740993', '9007199254740992')).toBe(1)
        expect(decimalToScale12('0.000000000001')).toBe(1n)
        expect(isZeroOrNull(null)).toBe(true)
        expect(isZeroOrNull('0.000000000001')).toBe(false)
    })

    it.each(['[]', 'null', '7', '"string"', '{bad json}'])('не пропускает невалидный JSON-объект %s', value => {
        expect(() => normalizeJsonObject(value)).toThrow()
    })

    it('приводит пустые JSON-поля к пустому объекту', () => {
        expect(normalizeJsonObject(' ')).toBe('{}')
    })

    it('отличает FREE от неизвестной стоимости и не принимает противоречивые поля', () => {
        expect(() => validatePricingDraft('FREE', true, '0', '0', '0', '0.000000000001', null, '{}')).toThrow()
        expect(() => validatePricingDraft('UNPRICED', false, null, null, null, '0', null, '{}')).toThrow()
        expect(() => validatePricingDraft('INCOMPLETE', true, null, null, null, null, null, '{}')).toThrow()
    })

    it('сохраняет корректные тарифы cache provider выше базового input', () => {
        expect(() => validatePricingDraft('CONFIGURED', true, '5', '7', '10', '15', 'ver-1', '{}')).not.toThrow()
    })

    it('сохраняет immutable catalog version, но не изобретает декларацию обучения', () => {
        const draft = createCatalogDraft(createModelCatalogEntry(), createRuntimeModelStatus())
        expect(draft.trainingUseStatus).toBeDefined()
        const request = buildCatalogRequest(draft, 11)
        expect(request.expectedPreviousVersion).toBe(11)
        expect(request.modelKey).toBe(draft.modelKey)
        expect(request.trainingUseStatus).toBe(draft.trainingUseStatus)
    })

    it('не разрешает вывод IMAGE и некорректный срок ZERO_DATA_RETENTION', () => {
        const d = createCatalogDraft(null, createRuntimeModelStatus())
        expect(() => buildCatalogRequest({ ...d, outputModalities: ['IMAGE'] }, 0)).toThrow()
        expect(() => buildCatalogRequest({ ...d, retentionStatus: 'ZERO_DATA_RETENTION', retentionDays: '1' }, 0)).toThrow()
    })

    it('очищает цены для UNPRICED без подстановки FREE', () => {
        const d = createCatalogDraft(null, createRuntimeModelStatus())
        const n = normalizeDraftForPricingStatus({ ...d, pricingStatus: 'UNPRICED', outputUsdPer1mTokens: '2', pricingComplete: true })
        expect(n.outputUsdPer1mTokens).toBe('')
        expect(n.pricingComplete).toBe(false)
    })

    it('не изменяет исходную политику при конструировании черновика', () => {
        const policy = createOrganizationModelPolicy()
        const before = JSON.stringify(policy)
        const draft = createPolicyDraft(policy)
        expect(draft.enabled).toBe(policy.enabled)
        expect(JSON.stringify(policy)).toBe(before)
    })

    it('блокирует пересечение allow/deny и запрещённый default', () => {
        const draft = createPolicyDraft(createOrganizationModelPolicy())
        expect(() => buildPolicyRequest({ ...draft, allowModelKeys: 'a:one', denyModelKeys: 'A:ONE' }, 1)).toThrow()
        expect(() => buildPolicyRequest({ ...draft, denyModelKeys: 'a:one', defaultModelKey: 'a:one' }, 1)).toThrow()
        expect(() => buildPolicyRequest({ ...draft, allowModelKeys: 'b:two', defaultModelKey: 'a:one' }, 1)).toThrow()
    })
})
