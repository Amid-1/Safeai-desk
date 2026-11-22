// frontend/src/features/chat/controller/state/useChatKnowledgeBases.ts
// Загрузка доступных баз знаний не должна блокировать обычный чат.
import { useEffect, useState } from 'react'
import { getKnowledgeBases } from '../../../../entities/knowledge/api/knowledgeApi'
import type { KnowledgeBase } from '../../../../entities/knowledge/api/knowledgeApi'

export function useChatKnowledgeBases() {
    const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
    const [knowledgeBasesStatus, setKnowledgeBasesStatus] = useState<'loading' | 'ready' | 'error'>('loading')
    const [nextPage, setNextPage] = useState<number | null>(null)
    const [request, setRequest] = useState({ page: 0, revision: 0 })

    useEffect(() => {
        const controller = new AbortController()
        void getKnowledgeBases(request.page, 100, { signal: controller.signal })
            .then((response) => {
                if (!controller.signal.aborted) {
                    setKnowledgeBases(previous => {
                        const byId = new Map((request.page === 0 ? [] : previous).map(base => [base.id, base]))
                        for (const base of response.content) {
                            if (base.enabled && base.lifecycleState !== 'DELETING') byId.set(base.id, base)
                            else byId.delete(base.id)
                        }
                        return [...byId.values()]
                    })
                    setNextPage(request.page + 1 < response.totalPages ? request.page + 1 : null)
                    setKnowledgeBasesStatus('ready')
                }
            })
            .catch(() => {
                if (!controller.signal.aborted) {
                    // Не подменяем сбой пустым списком и не меняем режим ответа.
                    setKnowledgeBasesStatus('error')
                }
            })
        return () => controller.abort()
    }, [request])

    function loadKnowledgeBasesPage() {
        if (knowledgeBasesStatus === 'loading') return
        const page = knowledgeBasesStatus === 'error' ? request.page : nextPage
        if (page === null) return
        setKnowledgeBasesStatus('loading')
        setRequest(previous => ({ page, revision: previous.revision + 1 }))
    }

    return { knowledgeBases, knowledgeBasesStatus, knowledgeBasesHasMore: nextPage !== null,
        loadKnowledgeBasesPage }
}
