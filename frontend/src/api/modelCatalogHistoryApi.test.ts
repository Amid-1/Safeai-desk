// ============================================================
// frontend/src/api/modelCatalogHistoryApi.test.ts
// ============================================================
import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    apiRequest,
} from './http'
import {
    getModelCatalogHistory,
} from './modelCatalogHistoryApi'

vi.mock('./http', () => ({
    apiRequest: vi.fn(),
}))

const apiRequestMock =
    vi.mocked(apiRequest)

describe('modelCatalogHistoryApi', () => {
    beforeEach(() => {
        apiRequestMock.mockReset()
    })

    it('uses a query parameter so model keys containing slash remain safe', async () => {
        apiRequestMock.mockResolvedValue([])

        await getModelCatalogHistory(
            'OpenAI:Family/Model',
        )

        expect(apiRequestMock).toHaveBeenCalledWith(
            '/api/admin/models/catalog/versions?modelKey=openai%3Afamily%2Fmodel',
            {
                method: 'GET',
                signal: undefined,
            },
        )
    })

    it('rejects an empty model key before making a request', async () => {
        await expect(
            getModelCatalogHistory('   '),
        ).rejects.toThrow(
            'modelKey не должен быть пустым',
        )

        expect(apiRequestMock).not.toHaveBeenCalled()
    })

    it('rejects a malformed non-array response', async () => {
        apiRequestMock.mockResolvedValue({})

        await expect(
            getModelCatalogHistory(
                'openai:gpt-test',
            ),
        ).rejects.toThrow(
            'Некорректный ответ API истории версий модели.',
        )
    })
})
