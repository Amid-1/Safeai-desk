// frontend/src/features/knowledge/lib/knowledgeFileTransfer.test.ts
// Защита origin приложения от HTML/SVG/PDF и MIME spoofing при предпросмотре.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { previewOrDownloadKnowledgeBlob, safePreviewMime } from './knowledgeFileTransfer'

const originalCreate = Object.getOwnPropertyDescriptor(URL, 'createObjectURL')
const originalRevoke = Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL')
afterEach(() => {
    vi.restoreAllMocks()
    if (originalCreate) Object.defineProperty(URL, 'createObjectURL', originalCreate)
    else Reflect.deleteProperty(URL, 'createObjectURL')
    if (originalRevoke) Object.defineProperty(URL, 'revokeObjectURL', originalRevoke)
    else Reflect.deleteProperty(URL, 'revokeObjectURL')
})
function objectUrls() {
    const create = vi.fn((_blob: Blob) => 'blob:safeai-test')
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: create })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
    return create
}

const blob = (bytes: number[], type: string) => new Blob([new Uint8Array(bytes)], { type })

describe('knowledge document preview security', () => {
    it.each(['text/html', 'image/svg+xml', 'application/xhtml+xml', 'application/pdf', 'application/octet-stream', ''])(
        'не открывает активный или неизвестный MIME %s', async (mime) => {
            expect(await safePreviewMime(blob([60, 115, 118, 103, 62], mime))).toBeNull()
        },
    )
    it('отказывает поддельному PNG с HTML-содержимым', async () => {
        expect(await safePreviewMime(blob([60, 115, 99, 114, 105, 112, 116, 62], 'image/png'))).toBeNull()
    })
    it('разрешает проверенный PNG, JPEG, GIF и WEBP', async () => {
        expect(await safePreviewMime(blob([137, 80, 78, 71, 13, 10, 26, 10], 'image/png'))).toBe('image/png')
        expect(await safePreviewMime(blob([255, 216, 255, 1], 'image/jpeg'))).toBe('image/jpeg')
        expect(await safePreviewMime(blob([71, 73, 70, 56, 57, 97], 'image/gif'))).toBe('image/gif')
        expect(await safePreviewMime(blob([82, 73, 70, 70, 0, 0, 0, 0, 87, 69, 66, 80], 'image/webp'))).toBe('image/webp')
    })
    it('содержимое HTML принудительно скачивается, навигация popup запрещена', async () => {
        const create = objectUrls()
        const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
        const popup = { close: vi.fn(), location: { replace: vi.fn() } } as unknown as Window
        expect(await previewOrDownloadKnowledgeBlob(new Blob(['<script>alert(1)</script>'], {type:'text/html'}), 'bad.html', popup)).toBe('download')
        expect(popup.close).toHaveBeenCalledOnce()
        expect(popup.location.replace).not.toHaveBeenCalled()
        expect(click).toHaveBeenCalledOnce()
        expect(create).toHaveBeenCalledOnce()
    })
    it('проверенное изображение открывается с явно безопасным MIME', async () => {
        const create = objectUrls()
        const popup = { close: vi.fn(), location: { replace: vi.fn() } } as unknown as Window
        expect(await previewOrDownloadKnowledgeBlob(blob([137, 80, 78, 71, 13, 10, 26, 10], 'image/png'), 'ok.png', popup)).toBe('preview')
        expect(popup.location.replace).toHaveBeenCalledWith('blob:safeai-test')
        expect((create.mock.calls[0]?.[0] as Blob).type).toBe('image/png')
        expect(popup.close).not.toHaveBeenCalled()
    })
})
