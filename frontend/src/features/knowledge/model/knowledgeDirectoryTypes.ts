// frontend/src/features/knowledge/model/knowledgeDirectoryTypes.ts
// Контракты форм и фильтров каталога знаний, независимые от React-компонентов.
import type { KnowledgeBaseVisibility } from '../../../entities/knowledge/api/knowledgeApi'

export type KnowledgeBaseFormValue = {
    name: string
    description: string
    visibility: KnowledgeBaseVisibility
    enabled: boolean
}

export type ActivityFilter = 'all' | 'enabled' | 'disabled'
export type VisibilityFilter = 'all' | KnowledgeBaseVisibility
