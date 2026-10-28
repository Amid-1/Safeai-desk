// frontend/src/features/knowledge/controller/useKnowledgeUploadController.test.tsx
// Двойная отправка, версия документа и безопасный сброс состояния загрузки.
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { uploadKnowledgeDocument, uploadKnowledgeDocumentVersion } from '../../../entities/knowledge/api/knowledgeDocumentApi'
import { ApiError } from '../../../shared/api/http'
import { useKnowledgeUploadController } from './useKnowledgeUploadController'

vi.mock('../../../entities/knowledge/api/knowledgeDocumentApi', () => ({
    uploadKnowledgeDocument: vi.fn(), uploadKnowledgeDocumentVersion: vi.fn(),
}))
const upload = vi.mocked(uploadKnowledgeDocument)
const version = vi.mocked(uploadKnowledgeDocumentVersion)
const BASE = '11111111-1111-4111-8111-111111111111'
const DOC = {id:'22222222-2222-4222-8222-222222222222', name:'Документ'}
const uploadFile = () => new File(['safe'], 'runbook.txt', {type:'text/plain'})

beforeEach(() => { vi.clearAllMocks(); upload.mockResolvedValue(undefined as never); version.mockResolvedValue(undefined as never) })

function mount(refresh = vi.fn().mockResolvedValue(undefined)) {
    const setDocumentPage = vi.fn()
    const render = renderHook(() => useKnowledgeUploadController({
        knowledgeBaseId: BASE, documentPage: 2, setDocumentPage, refreshAfterSuccessfulUpload: refresh,
    }))
    return { ...render, refresh, setDocumentPage }
}

describe('useKnowledgeUploadController concurrency', () => {
    it('отклоняет одновременный submit до следующего React render', async () => {
        let resolve!: () => void
        upload.mockImplementation(() => new Promise<Awaited<ReturnType<typeof uploadKnowledgeDocument>>>(resolveInner => { resolve = () => resolveInner(undefined as never) }))
        const {result} = mount()
        act(() => { result.current.openUpload('new') })
        act(() => { result.current.selectFile(uploadFile()) })
        let first!: Promise<void>
        let second!: Promise<void>
        act(() => { first = result.current.submitUpload(); second = result.current.submitUpload() })
        expect(upload).toHaveBeenCalledTimes(1)
        resolve()
        await act(async () => { await Promise.all([first, second]) })
        expect(result.current.busy).toBe(false)
    })
    it('сохраняет исходную страницу при загрузке новой версии', async () => {
        const { result, refresh, setDocumentPage } = mount()
        act(() => { result.current.openUpload(DOC as never) })
        act(() => { result.current.selectFile(uploadFile()) })
        await act(async () => { await result.current.submitUpload() })
        expect(version).toHaveBeenCalledWith(BASE, DOC.id, expect.any(File))
        expect(setDocumentPage).not.toHaveBeenCalled()
        expect(refresh).toHaveBeenCalledWith(2)
    })
    it('отклоняет файл свыше 25 МБ до обращения к API', async () => {
        const {result} = mount()
        const huge = new File(['x'], 'oversize.txt')
        Object.defineProperty(huge, 'size', {value: 25 * 1024 * 1024 + 1})
        act(() => { result.current.openUpload('new') })
        act(() => { result.current.selectFile(huge) })
        expect(result.current.uploadSubmitDisabled).toBe(true)
        await act(async () => { await result.current.submitUpload() })
        expect(upload).not.toHaveBeenCalled()
    })
    it('показывает ошибку валидации 400 и допускает ручное исправление', async () => {
        upload.mockRejectedValueOnce(new ApiError(
            'Расширение файла не соответствует содержимому.',
            {code: 'BAD_REQUEST'},
            400,
        ))
        const {result, refresh} = mount()
        act(() => { result.current.openUpload('new') })
        act(() => { result.current.selectFile(uploadFile()) })
        await act(async () => { await result.current.submitUpload() })
        expect(result.current.busy).toBe(false)
        expect(result.current.uploadError).toContain('Расширение файла')
        expect(result.current.uploadSubmitDisabled).toBe(false)
        expect(refresh).not.toHaveBeenCalled()
        await act(async () => { await result.current.submitUpload() })
        expect(upload).toHaveBeenCalledTimes(2)
    })

    it.each([0, 408, 500])(
        'после неопределённого результата %i обновляет список, но не повторяет POST',
        async (status) => {
            upload.mockRejectedValueOnce(new ApiError(
                'Ответ не получен',
                {code: 'REQUEST_TIMEOUT', requestId: 'req-upload-test'},
                status,
            ))
            const {result, refresh, setDocumentPage} = mount()
            act(() => { result.current.openUpload('new') })
            act(() => { result.current.selectFile(uploadFile()) })
            await act(async () => { await result.current.submitUpload() })
            expect(upload).toHaveBeenCalledTimes(1)
            expect(refresh).toHaveBeenCalledWith(0, false)
            expect(setDocumentPage).toHaveBeenCalledWith(0)
            expect(result.current.uploadError).toContain('мог сохраниться')
            expect(result.current.uploadSubmitDisabled).toBe(true)
            if (status === 0 || status >= 500) {
                expect(result.current.uploadRequestId).toBe('req-upload-test')
            }
            await act(async () => { await result.current.submitUpload() })
            expect(upload).toHaveBeenCalledTimes(1)
        },
    )

    it('позволяет новую осознанную попытку только после закрытия окна', async () => {
        upload.mockRejectedValueOnce(new Error('network lost'))
        const {result} = mount()
        act(() => { result.current.openUpload('new') })
        act(() => { result.current.selectFile(uploadFile()) })
        await act(async () => { await result.current.submitUpload() })
        expect(result.current.uploadSubmitDisabled).toBe(true)
        act(() => { result.current.closeUpload() })
        act(() => { result.current.openUpload('new') })
        act(() => { result.current.selectFile(uploadFile()) })
        await act(async () => { await result.current.submitUpload() })
        expect(upload).toHaveBeenCalledTimes(2)
    })
})
