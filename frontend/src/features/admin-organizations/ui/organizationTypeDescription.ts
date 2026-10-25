// frontend/src/features/admin-organizations/ui/organizationTypeDescription.ts
import type { Organization } from '../../../entities/organization/api/organizationApi'

/** Human-facing labels do not alter immutable organization type. */
export function organizationTypeDescription(type: Organization['type']): string {
    return type === 'PLATFORM'
        ? 'Системная организация SafeAI Desk. Защищена от изменения статуса и названия.'
        : 'Организация клиента с отдельными пользователями, настройками и правами доступа к SafeAI Desk.'
}
