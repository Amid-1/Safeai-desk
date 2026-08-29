import {
    describe,
    expect,
    it,
} from 'vitest'
import type {
    RuntimeModelStatus,
} from '../../../api/modelApi'
import {
    buildCatalogRequest,
    createCatalogDraft,
    parseModelKeyList,
} from './modelControlPlaneSupport'

const RUNTIME: RuntimeModelStatus = {
    provider: 'mock',
    model: 'mock-safeai',
    enabled: true,
    routingMode: 'SINGLE_PROVIDER_STATIC',
    maxInputTokens: 64_000,
    maxOutputTokens: 2_048,
    toolsSupported: false,
    visionSupported: false,
    structuredOutputSupported: false,
    dataRetentionStatus: 'NOT_DECLARED',
    healthStatus: 'NOT_PROBED',
    pricingStatus: 'FREE',
    inputUsdPer1mTokens: '0',
    outputUsdPer1mTokens: '0',
    pricingVersion: 'mock-2026-01',
}

describe('modelControlPlaneSupport', () => {
    it('не позволяет отправить IMAGE как выходной формат, которого нет в схеме каталога', () => {
        const draft = createCatalogDraft(
            null,
            RUNTIME,
        )

        draft.outputModalities = [
            'TEXT',
            'IMAGE',
        ]

        expect(() =>
            buildCatalogRequest(
                draft,
                0,
            ),
        ).toThrow(
            'Изображения пока не поддерживаются как выходной формат каталога.',
        )
    })

    it('нормализует регистр model key и удаляет дубликаты', () => {
        expect(
            parseModelKeyList(
                ' OpenAI:GPT-5\nopenai:gpt-5;ANTHROPIC:Claude-Sonnet ',
            ),
        ).toEqual([
            'openai:gpt-5',
            'anthropic:claude-sonnet',
        ])
    })

    it('отклоняет пробелы и недопустимые символы внутри model key', () => {
        expect(() =>
            parseModelKeyList(
                'openai:gpt 5',
            ),
        ).toThrow(
            'Ключ модели может содержать только латинские буквы',
        )
    })

})
