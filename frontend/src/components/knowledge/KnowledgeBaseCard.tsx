import type {
    KnowledgeBase,
} from '../../api/knowledgeApi'
import {
    formatDateTime,
} from '../../utils/format'
import { Link } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { getKnowledgeDocuments } from '../../api/knowledgeDocumentApi'

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

        getKnowledgeDocuments(
            knowledgeBase.id,
            0,
            100,
            controller.signal,
        ).then((page) => {
            setStats({
                total: page.totalElements,
                ready: page.content.filter(
                    (document) => document.status === 'READY',
                ).length,
                processing: page.content.filter(
                    (document) => document.status !== 'READY'
                        && document.status !== 'FAILED',
                ).length,
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
                            ? 'Вся организация'
                            : 'Только участники'
                    }
                </span>
            </div>

            <p className="knowledge-card__description">
                {
                    knowledgeBase.description
                    ?? 'Описание не задано.'
                }
            </p>

            <dl className="knowledge-card__meta">
                <div>
                    <dt>Версия</dt>
                    <dd>
                        {knowledgeBase.version}
                    </dd>
                </div>

                <div>
                    <dt>Обновлена</dt>
                    <dd>
                        {
                            formatDateTime(
                                knowledgeBase.updatedAt,
                            )
                        }
                    </dd>
                </div>
            </dl>

            <dl className="knowledge-card__document-stats">
                <div><dt>Документы</dt><dd>{stats?.total ?? '—'}</dd></div>
                <div><dt>Готово</dt><dd>{stats?.ready ?? '—'}</dd></div>
                <div><dt>Обрабатывается</dt><dd>{stats?.processing ?? '—'}</dd></div>
            </dl>

            {canManage && (
                <div className="knowledge-card__actions">
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={() =>
                            onMembers(
                                knowledgeBase,
                            )
                        }
                    >
                        Доступ
                    </button>

                    <button
                        type="button"
                        className="secondary-button"
                        onClick={() =>
                            onEdit(
                                knowledgeBase,
                            )
                        }
                    >
                        Настройки
                    </button>
                </div>
            )}
            <Link className="knowledge-card__open" to={`/knowledge/${knowledgeBase.id}`}>Открыть базу</Link>
        </article>
    )
}

export default KnowledgeBaseCard
