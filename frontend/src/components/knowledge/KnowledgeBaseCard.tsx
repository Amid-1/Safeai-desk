import type {
    KnowledgeBase,
} from '../../api/knowledgeApi'
import {
    formatDateTime,
} from '../../utils/format'

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
                        {knowledgeBase.name}
                    </h2>

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

            <div className="knowledge-card__future">
                Документы и версии:
                следующий этап V39.
            </div>

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
        </article>
    )
}

export default KnowledgeBaseCard
