// frontend/src/components/knowledge/knowledgeMembers/knowledgeMembersConfig.ts
import type { KnowledgeBaseAccessLevel, KnowledgeBaseMember } from '../../../api/knowledgeApi'
import type { PageResponse } from '../../../utils/page'

export const MEMBER_PAGE_SIZE = 50

export const ACCESS_LEVEL_LABEL:
    Record<KnowledgeBaseAccessLevel, string> = {
    VIEWER: 'Только просмотр',
    EDITOR: 'Просмотр и загрузка',
    OWNER: 'Полное управление',
}

export const ACCESS_LEVEL_HINT:
    Record<KnowledgeBaseAccessLevel, string> = {
    VIEWER: 'Читает, скачивает и использует документы в AI-чате.',
    EDITOR: 'Также загружает документы и новые версии.',
    OWNER: 'Также меняет настройки и управляет доступом.',
}

export const EMPTY_MEMBER_PAGE:
    PageResponse<KnowledgeBaseMember> = {
    content: [],
    page: 0,
    size: MEMBER_PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
}
