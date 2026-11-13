// frontend/src/features/knowledge/ui/KnowledgeErasureDialog.tsx
import {useId} from 'react'
import Modal from '../../../shared/ui/Modal'
import {matchesKnowledgeDeletionName} from '../lib/knowledgeDeletionConfirmation'
import {KnowledgeDeletionProgress} from './KnowledgeDeletionProgress'
import type {KnowledgeErasureOperation} from './KnowledgeDeletionProgress'
import '../styles/KnowledgeDeletionConfirmation.css'

type Kind = 'base' | 'document'
type Shared = {
    kind: Kind
    entityName: string
    onClose: () => void
    busy?: boolean
}
type DialogProps = Shared & (
    | { mode: 'checking' }
    | { mode: 'unavailable'; reason: string; checkFailed?: boolean }
    | {
    mode: 'confirm'; typedName: string; onTypedNameChange: (name: string) => void
    onConfirm: () => void; error?: string
}
    | {
    mode: 'operation'; operation: KnowledgeErasureOperation; historyHref?: string
    statusError?: string
}
    )

/**
 * Единое представление четырёх состояний удаления документа и базы.
 * Компонент только показывает данные сервера: права, retention и атомарность
 * проверяются на backend, а не при помощи состояния кнопки.
 */
export function KnowledgeErasureDialog(props: DialogProps) {
    const {kind, entityName, onClose, busy = false} = props
    const fieldId = useId()
    const isBase = kind === 'base'
    const title = props.mode === 'checking'
        ? 'Проверяем возможность удаления'
        : props.mode === 'unavailable'
            ? isBase ? 'Удаление базы недоступно' : 'Удаление документа недоступно'
            : props.mode === 'operation'
                ? 'Состояние удаления'
                : isBase ? 'Безвозвратно удалить базу?' : 'Безвозвратно удалить документ?'
    const confirmed = props.mode === 'confirm'
        && matchesKnowledgeDeletionName(props.typedName, entityName)

    return <Modal title={title} size="md" className="knowledge-erasure-dialog"
                  onClose={onClose} closeDisabled={busy} closeOnBackdrop={false}>
        {props.mode === 'checking' && <div role="status" className="knowledge-erasure-dialog__intro">
            <span className="knowledge-erasure-dialog__symbol" aria-hidden="true">◷</span>
            <p>Проверяем разрешение сервера и историю ранее принятых операций…</p>
        </div>}
        {props.mode === 'unavailable' && <>
            <div className="knowledge-erasure-dialog__intro">
                <span className="knowledge-erasure-dialog__symbol" aria-hidden="true">!</span>
                <div>
                    <strong>Ограничение безопасности</strong>
                    <p className="knowledge-deletion-confirmation__notice" role="status">{props.reason}</p>
                </div>
            </div>
            <p>{props.checkFailed
                ? 'Доступность удаления не подтверждена. Пока проверка недоступна, новый запрос не отправляется.'
                : 'Для включения безвозвратного удаления требуется предварительная проверка безопасности и конфигурации сервера.'}</p>
            <p>{isBase
                ? `База «${entityName}» и её документы не изменены этим действием.`
                : `Документ «${entityName}» и его версии не изменены этим действием.`}</p>
        </>}
        {props.mode === 'confirm' && <>
            <div className="knowledge-erasure-dialog__intro knowledge-erasure-dialog__intro--danger">
                <span className="knowledge-erasure-dialog__symbol" aria-hidden="true">!</span>
                <div>
                    <strong>Это необратимая операция</strong>
                    <p>{isBase
                        ? `База «${entityName}», её документы, версии файлов и поисковые данные будут поставлены на удаление.`
                        : `Документ «${entityName}», все его версии и связанные поисковые данные будут поставлены на удаление.`}</p>
                </div>
            </div>
            <p>После подтверждения система начнёт удаление. После завершения восстановить исходные файлы обычным
                способом нельзя. Результат можно проверить в истории удаления. Записи о выполненных действиях
                сохраняются.</p>
            <div className="knowledge-deletion-confirmation__field">
                <label htmlFor={fieldId}>{isBase ? 'Название базы знаний' : 'Название документа'}</label>
                <input id={fieldId} value={props.typedName} type="text" autoComplete="off"
                       spellCheck={false} maxLength={255} disabled={busy}
                       placeholder={entityName} onChange={event => props.onTypedNameChange(event.target.value)}/>
                <p className="knowledge-deletion-confirmation__hint">
                    Введите полное название. Регистр не важен, но символы других алфавитов и похожие цифры не заменяют
                    исходные буквы.
                </p>
            </div>
            {props.error && <p role="alert" className="knowledge-deletion-confirmation__notice">
                {props.error} Запрос будет повторён только с прежним ключом идемпотентности.
            </p>}
        </>}
        {props.mode === 'operation' && <>
            <KnowledgeDeletionProgress operation={props.operation}
                                       historyHref={props.historyHref} onHistoryClick={props.onClose}/>
            {props.statusError && <p role="alert" className="knowledge-deletion-confirmation__notice">
                {props.statusError} Последний полученный статус не считается подтверждением завершения.
            </p>}
        </>}
        <div className="modal-actions">
            <button type="button" className="secondary-button" disabled={busy} onClick={onClose}>
                {props.mode === 'confirm' ? 'Отмена'
                    : props.mode === 'unavailable' ? 'Понятно' : 'Закрыть'}
            </button>
            {props.mode === 'confirm' && <button type="button" className="danger-button"
                                                 disabled={busy || !confirmed} onClick={props.onConfirm}>
                {busy ? 'Отправляем запрос…'
                    : isBase ? 'Подтвердить удаление базы' : 'Подтвердить удаление документа'}
            </button>}
        </div>
    </Modal>
}
