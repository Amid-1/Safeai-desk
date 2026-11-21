// frontend/src/features/chat/controller/ChatKnowledgeModeSelector.tsx
import type { ChatPageController } from './useChatPageController'
import type { KnowledgeMode } from '../api/chatApi'

type Props = { controller: ChatPageController }

export default function ChatKnowledgeModeSelector({ controller }: Props) {
    const {
        knowledgeMode,
        knowledgeBaseId,
        knowledgeBases,
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
                        value={knowledgeBaseId}
                        disabled={disabled}
                        onChange={(event) => setKnowledgeBaseId(event.target.value)}
                    >
                        <option value="">Выберите базу знаний</option>
                        {knowledgeBases.map((base) => (
                            <option key={base.id} value={base.id}>{base.name}</option>
                        ))}
                    </select>
                </>
            )}
        </div>
    )
}
