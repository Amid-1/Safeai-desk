// frontend/src/features/knowledge/lib/knowledgeFileTransfer.ts
// Безопасный просмотр растровых изображений и принудительное скачивание остальных документов.

const PREVIEW_LIFETIME_MS = 60 * 60 * 1000
const DOWNLOAD_LIFETIME_MS = 1_000

const SIGNATURES: Record<string, (bytes: Uint8Array) => boolean> = {
    'image/png': bytes => startsWith(bytes, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    'image/jpeg': bytes => startsWith(bytes, [0xff, 0xd8, 0xff]),
    'image/gif': bytes => startsWith(bytes, [0x47, 0x49, 0x46, 0x38, 0x37, 0x61])
        || startsWith(bytes, [0x47, 0x49, 0x46, 0x38, 0x39, 0x61]),
    'image/webp': bytes => startsWith(bytes, [0x52, 0x49, 0x46, 0x46])
        && bytes.length >= 12 && startsWith(bytes.subarray(8), [0x57, 0x45, 0x42, 0x50]),
}

function startsWith(bytes: Uint8Array, prefix: number[]): boolean {
    return bytes.length >= prefix.length && prefix.every((byte, index) => bytes[index] === byte)
}

/** Не доверяем одному Content-Type: проверяем сигнатуру перед same-origin blob-навигацией. */
export async function safePreviewMime(blob: Blob): Promise<string | null> {
    const mime = blob.type.split(';', 1)[0]?.trim().toLowerCase() ?? ''
    const accepts = SIGNATURES[mime]
    if (!accepts || blob.size === 0) return null
    const bytes = new Uint8Array(await readHeader(blob))
    return accepts(bytes) ? mime : null
}

/** Скачивание сохраняет байты и имя без выполнения содержимого документа. */
export function downloadKnowledgeBlob(blob: Blob, filename: string): void {
    const objectUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    try {
        link.href = objectUrl
        link.download = filename || 'document'
        link.rel = 'noopener'
        document.body.appendChild(link)
        link.click()
    } finally {
        link.remove()
        window.setTimeout(() => URL.revokeObjectURL(objectUrl), DOWNLOAD_LIFETIME_MS)
    }
}

/** HTML, SVG, PDF, XML, пустой/ложный MIME не открываются в origin приложения. */
export async function previewOrDownloadKnowledgeBlob(
    blob: Blob,
    filename: string,
    previewWindow: Window,
): Promise<'preview' | 'download'> {
    const mime = await safePreviewMime(blob)
    if (!mime) {
        try { previewWindow.close() } catch { /* Загрузка файла не зависит от состояния вкладки. */ }
        downloadKnowledgeBlob(blob, filename)
        return 'download'
    }
    // Перезаписываем MIME строго проверенным значением; входной заголовок не переносим.
    const safeBlob = new Blob([blob], { type: mime })
    const objectUrl = URL.createObjectURL(safeBlob)
    try {
        previewWindow.location.replace(objectUrl)
    } catch (error) {
        URL.revokeObjectURL(objectUrl)
        throw error
    }
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), PREVIEW_LIFETIME_MS)
    return 'preview'
}

// JSDOM/старые окружения могут не реализовать Blob.arrayBuffer; FileReader сохраняет точность байтов.
async function readHeader(blob: Blob): Promise<ArrayBuffer> {
    const header = blob.slice(0, 16)
    if (typeof header.arrayBuffer === 'function') return header.arrayBuffer()
    return new Promise<ArrayBuffer>((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => {
            if (reader.result instanceof ArrayBuffer) resolve(reader.result)
            else reject(new TypeError('Не удалось прочитать сигнатуру документа.'))
        }
        reader.onerror = () => reject(reader.error ?? new Error('Ошибка чтения документа.'))
        reader.readAsArrayBuffer(header)
    })
}
