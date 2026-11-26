// frontend/src/features/knowledge/ui/KnowledgeBaseCard.tsx
import ErasureRequestLink from '../../../shared/ui/ErasureRequestLink'
import type {
    KnowledgeBase,
} from '../../../entities/knowledge/api/knowledgeApi'
import {
    formatDateTime,
} from '../../../shared/lib/format'
import { Link } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { KnowledgeBaseDeleteAction } from './KnowledgeBaseDeleteAction'
import { getKnowledgeHealth } from '../../../entities/knowledge/api/knowledgeDocumentApi'

type KnowledgeBaseCardProps = {
    knowledgeBase: KnowledgeBase
    canManage: boolean
    onDeletionAccepted?: () => void
    onEdit:
        (knowledgeBase: KnowledgeBase) => void
    onMembers:
        (knowledgeBase: KnowledgeBase) => void
}

function KnowledgeBaseCard({
    knowledgeBase,
    canManage,
    onDeletionAccepted,
    onEdit,
    onMembers,
}: KnowledgeBaseCardProps) {
    const [observation, setObservation] = useState<{
        key: string
        stats: { total: number; ready: number; processing: number } | null
    } | null>(null)
    const observationKey = `${knowledgeBase.id}:${knowledgeBase.version}`
    const current = observation?.key === observationKey ? observation : null
    const stats = current?.stats

    useEffect(() => {
        const controller = new AbortController()

        getKnowledgeHealth(
            knowledgeBase.id,
            controller.signal,
        ).then((health) => {
            if (controller.signal.aborted) return
            setObservation({
                key: observationKey,
                stats: {
                    total: health.totalDocuments,
                    ready: health.searchableDocuments,
                    processing: health.pendingDocuments + health.processingDocuments,
                },
            })
        }).catch(() => {
            if (!controller.signal.aborted) setObservation({ key: observationKey, stats: null })
        })

        return () => controller.abort()
    }, [knowledgeBase.id, observationKey])
    return (
        <article
            className={
                knowledgeBase.enabled
                    ? 'card knowledge-card'
                    : (
                        'card knowledge-card '
                        + 'knowledge-card--disabled'
                    )
            }
        >
            <div className="knowledge-card__top">
                <div>
                    <h2>
                        <Link
                            to={`/knowledge/${knowledgeBase.id}`}
                            title={knowledgeBase.name}
                        >
                            {knowledgeBase.name}
                        </Link>
                    </h2>

                    <span
                        className={
                            knowledgeBase.lifecycleState === 'DELETING'
                                ? 'knowledge-status knowledge-status--disabled'
                                : knowledgeBase.enabled
                                ? (
                                    'knowledge-status '
                                    + 'knowledge-status--enabled'
                                )
                                : (
                                    'knowledge-status '
                                    + 'knowledge-status--disabled'
                                )
                        }
                    >
                        {
                            knowledgeBase.lifecycleState === 'DELETING'
                                ? 'Удаляется'
                                : knowledgeBase.enabled ? 'Активна' : 'Отключена'
                        }
                    </span>
                </div>

                <span className="knowledge-visibility">
                    {
                        knowledgeBase.visibility
                            === 'ORGANIZATION'
                            ? 'Все сотрудники организации'
                            : 'Только приглашённые сотрудники'
                    }
                </span>
            </div>

            <p
                className="knowledge-card__description"
                title={knowledgeBase.description ?? undefined}
            >
                {
                    knowledgeBase.description
                    ?? 'Описание не задано.'
                }
            </p>

            <p className="knowledge-card__updated">
                Последнее изменение
                {' '}
                <time dateTime={knowledgeBase.updatedAt}>
                    {
                        formatDateTime(
                            knowledgeBase.updatedAt,
                        )
                    }
                </time>
            </p>

            <dl className="knowledge-card__document-stats">
                <div>
                    <dt>Всего документов</dt>
                    <dd>{stats?.total ?? '—'}</dd>
                    <Link
                        className="knowledge-card__documents-link"
                        to={`/knowledge/${knowledgeBase.id}#knowledge-documents`}
                    >
                        Открыть список →
                    </Link>
                </div>
                <div><dt>Готовы к поиску</dt><dd>{stats?.ready ?? '—'}</dd></div>
                <div><dt>Обрабатываются</dt><dd>{stats?.processing ?? '—'}</dd></div>
            </dl>
            {!stats && <p className="muted">
                {current ? 'Сведения о документах пока недоступны.' : 'Загружаем сведения о документах…'}
            </p>}

            {canManage && (
                <div className="knowledge-card__actions">
                    <button
                        type="button"
                        className="knowledge-card__management-action knowledge-card__management-action--access"
                        title="Настроить доступ сотрудников"
                        onClick={() =>
                            onMembers(
                                knowledgeBase,
                            )
                        }
                    >
                        <span className="knowledge-card__management-copy">
                            <strong>Доступ</strong>
                            <small>Участники и права</small>
                        </span>
                        <span
                            className="knowledge-card__management-arrow"
                            aria-hidden="true"
                        >
                            →
                        </span>
                    </button>

                    <button
                        type="button"
                        disabled={knowledgeBase.lifecycleState === 'DELETING'}
                        className="knowledge-card__management-action knowledge-card__management-action--settings"
                        title="Изменить настройки базы знаний"
                        onClick={() =>
                            onEdit(
                                knowledgeBase,
                            )
                        }
                    >
                        <span className="knowledge-card__management-copy">
                            <strong>Настройки</strong>
                            <small>Название, доступ и статус</small>
                        </span>
                        <span
                            className="knowledge-card__management-arrow"
                            aria-hidden="true"
                        >
                            →
                        </span>
                    </button>
                    <KnowledgeBaseDeleteAction base={knowledgeBase}
                        onAccepted={() => onDeletionAccepted?.()}/>
                </div>
            )}
            {canManage && <ErasureRequestLink target={{ type: 'KNOWLEDGE_BASE', id: knowledgeBase.id }} />}
            <Link className="knowledge-card__open" to={`/knowledge/${knowledgeBase.id}`}>Открыть базу</Link>
        </article>
    )
}

export default KnowledgeBaseCard
