// frontend/src/features/knowledge/lib/knowledgeDeletionRequestKey.ts
import {createSecureUuid} from '../../../shared/lib/secureUuid'

/**
 * Ключ идемпотентности принадлежит конкретному объекту и ожидаемой версии.
 * Сохраняем его только в рамках вкладки, чтобы потеря HTTP-ответа или обновление
 * страницы не порождали второе намерение удаления с новым ключом.
 * Никакие сведения о содержимом документа или токены авторизации не сохраняем.
 */
const PREFIX = 'safeai:knowledge-erasure:v1:'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const volatile = new Map<string, string>()

function storage() {
    try {
        return typeof window === 'undefined' ? null : window.sessionStorage
    } catch {
        // Браузер может запрещать sessionStorage. Переходим на память вкладки.
        return null
    }
}

function slot(kind: 'base' | 'document', baseId: string, entityId: string, version: number) {
    // UUID и версия приходят от уже проверенного серверного DTO.
    if (!UUID.test(baseId) || !UUID.test(entityId)
        || !Number.isSafeInteger(version) || version < 0) {
        throw new Error('Недопустимая идентичность запроса удаления.')
    }
    return `${PREFIX}${kind}:${baseId}:${entityId}:${version}`
}

export function getKnowledgeDeletionRequestKey(kind: 'base' | 'document',
    baseId: string, entityId: string, version: number): string {
    const identifier = slot(kind, baseId, entityId, version)
    let previous = volatile.get(identifier)
    try { previous = storage()?.getItem(identifier) ?? previous } catch { /* Хранилище недоступно. */ }
    if (previous && UUID.test(previous)) return previous
    const key = createSecureUuid()
    if (!UUID.test(key)) throw new Error('Не удалось создать безопасный ключ удаления.')
    volatile.set(identifier, key)
    try { storage()?.setItem(identifier, key) } catch { /* Хранилище недоступно. */ }
    return key
}

export function clearKnowledgeDeletionRequestKey(kind: 'base' | 'document',
    baseId: string, entityId: string, version: number): void {
    const identifier = slot(kind, baseId, entityId, version)
    volatile.delete(identifier)
    try { storage()?.removeItem(identifier) } catch { /* Хранилище недоступно. */ }
}
