import { useState } from 'react'
import type { ReactNode } from 'react'
import type { Organization } from '../../../api/organizationApi'

/** Human-facing labels do not alter immutable organization type. */
export function organizationTypeDescription(type: Organization['type']): string {
    return type === 'PLATFORM'
        ? 'Системная организация SafeAI Desk. Защищена от изменения статуса и названия.'
        : 'Организация клиента с отдельными пользователями, настройками и правами доступа к SafeAI Desk.'
}

export function OrganizationStatusBadge({ enabled }: { enabled: boolean }) {
    return (
        <span className={enabled
            ? 'organization-status organization-status--enabled'
            : 'organization-status organization-status--disabled'}>
            <span className="organization-status__dot" aria-hidden="true" />
            {enabled ? 'Включена' : 'Отключена'}
        </span>
    )
}

export function OrganizationTypeBadge({ type }: { type: Organization['type'] }) {
    return (
        <span
            className={type === 'PLATFORM'
                ? 'organization-type organization-type--platform'
                : 'organization-type organization-type--tenant'}
            title={organizationTypeDescription(type)}
        >
            {type === 'PLATFORM' ? 'Системная' : 'Клиентская'}
        </span>
    )
}

/** Fully accessible on mouse, keyboard and touch; the type is never edited here. */
export function OrganizationTypeInfo({ type }: { type: Organization['type'] }) {
    return (
        <details className="org-explain">
            <summary aria-label={`Тип организации: ${type === 'PLATFORM' ? 'системная' : 'клиентская'}. Подробнее`}>
                <OrganizationTypeBadge type={type} />
                <span className="org-help-symbol" aria-hidden="true">?</span>
            </summary>
            <p className="org-explain__text">{organizationTypeDescription(type)} Тип определяется
                системой при создании и здесь не изменяется.</p>
        </details>
    )
}

export function OrganizationVersionInfo({ version }: { version: number }) {
    return (
        <details className="org-explain">
            <summary aria-label={`Версия организации: ${version}. Подробнее`}>
                <span className="organization-version">v{version}</span>
                <span className="org-help-symbol" aria-hidden="true">?</span>
            </summary>
            <p className="org-explain__text">
                Номер обновления записи — счётчик изменений организации. Сервер проверяет его при сохранении,
                чтобы одна правка не затёрла другую. Новая организация обычно начинается
                с v0. Это не версия приложения, вручную менять её нельзя.
            </p>
        </details>
    )
}

export function OrganizationId({ id }: { id: string }) {
    const [status, setStatus] = useState<'idle' | 'copied' | 'error'>('idle')

    async function copy() {
        try {
            await navigator.clipboard.writeText(id)
            setStatus('copied')
        } catch {
            setStatus('error')
        }
    }

    return (
        <span className="org-identity">
            <code title={id} className="org-identity__short">{id.slice(0, 8)}…</code>
            <button type="button" className="org-icon-button" onClick={() => void copy()}
                    title="Скопировать полный UUID организации"
                    aria-label={`Скопировать ID организации ${id}`}>
                <span aria-hidden="true">⧉</span>
            </button>
            {status === 'copied' && <span className="org-copy-status" role="status">Скопировано</span>}
            {status === 'error' && <span className="org-copy-status org-copy-status--error" role="alert">
                Не удалось скопировать. Полный ID доступен в сведениях.
            </span>}
        </span>
    )
}

export function OrganizationDetail({ term, value }: { term: string, value: ReactNode }) {
    return <div className="organization-details__row"><dt>{term}</dt><dd>{value}</dd></div>
}

export function OrganizationImpact({ term, value }: { term: string, value: number }) {
    return <div className="organization-impact__row"><dt>{term}</dt><dd>{value}</dd></div>
}
