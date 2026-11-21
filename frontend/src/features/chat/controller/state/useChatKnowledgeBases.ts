// frontend/src/features/chat/controller/state/useChatKnowledgeBases.ts
// Загрузка доступных баз знаний не должна блокировать обычный чат.
import { useEffect, useState } from 'react'
import { getKnowledgeBases } from '../../../../entities/knowledge/api/knowledgeApi'
import type { KnowledgeBase } from '../../../../entities/knowledge/api/knowledgeApi'

export function useChatKnowledgeBases() {
    const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])

    useEffect(() => {
        const controller = new AbortController()
        void getKnowledgeBases(0, 100, { signal: controller.signal })
            .then((response) => {
                if (!controller.signal.aborted) {
                    setKnowledgeBases(response.content.filter((base) => base.enabled))
                }
            })
            .catch(() => {
                if (!controller.signal.aborted) {
                    // Ошибка загрузки не должна ломать режим GENERAL.
                    setKnowledgeBases([])
                }
            })
        return () => controller.abort()
    }, [])

    return { knowledgeBases, setKnowledgeBases }
}
