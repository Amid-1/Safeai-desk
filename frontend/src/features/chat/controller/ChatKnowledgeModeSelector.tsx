// frontend/src/features/chat/controller/ChatKnowledgeModeSelector.tsx
import type { ChatPageController } from './useChatPageController'
import type { KnowledgeMode } from '../api/chatApi'
import './ChatKnowledgeModeSelector.css'

type Props = { controller: ChatPageController }

export default function ChatKnowledgeModeSelector({ controller }: Props) {
    const {
        knowledgeMode,
        knowledgeBaseId,
        knowledgeBases,
        knowledgeBasesStatus,
        knowledgeBasesHasMore,
        loadKnowledgeBasesPage,
        activeChatBusy,
        activeChatHasPending,
        setKnowledgeMode,
        setKnowledgeBaseId,
    } = controller

    const disabled = activeChatBusy || activeChatHasPending

    function handleModeChange(mode: KnowledgeMode) {
        setKnowledgeMode(mode)
        if (mode === 'GENERAL') setKnowledgeBaseId('')
    }

    return (
        <div className="message-form__controls">
            <label htmlFor="knowledge-mode">Режим ответа</label>
            <select
                id="knowledge-mode"
                value={knowledgeMode}
                disabled={disabled}
                onChange={(event) => handleModeChange(event.target.value as KnowledgeMode)}
            >
                <option value="GENERAL">Обычный AI</option>
                <option value="KNOWLEDGE_ASSISTED">AI + корпоративные знания</option>
                <option value="KNOWLEDGE_ONLY">Только корпоративные знания</option>
            </select>
            {knowledgeMode !== 'GENERAL' && (
                <>
                    <label htmlFor="knowledge-base">База знаний</label>
                    <select
                        id="knowledge-base"
                        value={knowledgeBases.some(base => base.id === knowledgeBaseId) ? knowledgeBaseId : ''}
                        disabled={disabled || knowledgeBasesStatus !== 'ready'}
                        aria-describedby="chat-knowledge-status"
                        onChange={(event) => setKnowledgeBaseId(event.target.value)}
                    >
                        <option value="">Выберите базу знаний</option>
                        {knowledgeBases.map((base) => (
                            <option key={base.id} value={base.id}>{base.name}</option>
                        ))}
                    </select>
                    <div id="chat-knowledge-status" className="chat-knowledge-status" role="status">
                        {knowledgeBasesStatus === 'loading' && 'Загружаем доступные базы…'}
                        {knowledgeBasesStatus === 'error' && 'Не удалось загрузить базы знаний.'}
                        {knowledgeBasesStatus === 'ready' && knowledgeBases.length === 0
                            && !knowledgeBasesHasMore && 'Доступных активных баз нет.'}
                        {knowledgeBasesStatus === 'ready' && knowledgeBases.length > 0
                            && !knowledgeBases.some(base => base.id === knowledgeBaseId)
                            && 'Выберите базу, чтобы отправить сообщение.'}
                        {knowledgeBasesStatus === 'error' && (
                            <button type="button" className="secondary-button" disabled={disabled}
                                onClick={loadKnowledgeBasesPage}>Повторить загрузку</button>
                        )}
                        {knowledgeBasesStatus === 'ready' && knowledgeBasesHasMore && (
                            <button type="button" className="secondary-button" disabled={disabled}
                                onClick={loadKnowledgeBasesPage}>Показать ещё базы</button>
                        )}
                    </div>
                </>
            )}
        </div>
    )
}
