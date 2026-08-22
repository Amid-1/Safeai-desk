import type {
    KnowledgeBase,
} from '../../api/knowledgeApi'
import {
    formatDateTime,
} from '../../utils/format'
import { Link } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { getKnowledgeHealth } from '../../api/knowledgeDocumentApi'

type KnowledgeBaseCardProps = {
    knowledgeBase: KnowledgeBase
    canManage: boolean
    onEdit:
        (knowledgeBase: KnowledgeBase) => void
    onMembers:
        (knowledgeBase: KnowledgeBase) => void
}

function KnowledgeBaseCard({
    knowledgeBase,
    canManage,
    onEdit,
    onMembers,
}: KnowledgeBaseCardProps) {
    const [stats, setStats] = useState<{
        total: number
        ready: number
        processing: number
    } | null>(null)

    useEffect(() => {
        const controller = new AbortController()

        getKnowledgeHealth(
            knowledgeBase.id,
            controller.signal,
        ).then((health) => {
            setStats({
                total: health.totalDocuments,
                ready: health.searchableDocuments,
                processing:
                    health.pendingDocuments
                    + health.processingDocuments,
            })
        }).catch(() => {
            if (!controller.signal.aborted) setStats(null)
        })

        return () => controller.abort()
    }, [knowledgeBase.id])
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
                    <h2><Link to={`/knowledge/${knowledgeBase.id}`}>{knowledgeBase.name}</Link></h2>

                    <span
                        className={
                            knowledgeBase.enabled
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
                            knowledgeBase.enabled
                                ? 'Активна'
                                : 'Отключена'
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

            <p className="knowledge-card__description">
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
                </div>
            )}
            <Link className="knowledge-card__open" to={`/knowledge/${knowledgeBase.id}`}>Открыть базу</Link>
        </article>
    )
}

export default KnowledgeBaseCard
