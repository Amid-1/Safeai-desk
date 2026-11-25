// frontend/src/features/erasure/review/reviewLabels.ts
import type { ReviewState } from './reviewApi'

export const REVIEW_STATES: Record<ReviewState, string> = {
    PENDING: 'Ожидает рассмотрения',
    NEEDS_INFORMATION: 'Нужно уточнение',
    APPROVED_REVIEW_ONLY: 'Предварительно согласовано',
    REJECTED: 'Отказано',
    WITHDRAWN: 'Отозвана',
}
export const RESOURCE_NAMES: Record<string, string> = {
    CHAT: 'Чат', USER_ACCOUNT: 'Учётная запись', KNOWLEDGE_DOCUMENT: 'Документ',
    KNOWLEDGE_BASE: 'База знаний', ORGANIZATION: 'Организация',
}
export const EVENT_LABELS: Record<string, string> = {
    SUBMITTED: 'Заявка зарегистрирована',
    APPROVED_REVIEW_ONLY: 'Предварительное согласование',
    REJECTED: 'Вынесен отказ',
    NEEDS_INFORMATION: 'Запрошено уточнение',
    INFORMATION_PROVIDED: 'Заявитель подтвердил готовность уточнить',
    HOLD_APPLIED: 'Установлено юридическое удержание',
    HOLD_RELEASED: 'Юридическое удержание снято',
    WITHDRAWN: 'Заявка отозвана',
}
export function reviewTime(value: string): string {
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? 'Дата недоступна' : date.toLocaleString('ru-RU')
}
