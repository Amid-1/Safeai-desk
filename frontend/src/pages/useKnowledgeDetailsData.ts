import {
    useCallback,
    useEffect,
    useState,
} from 'react'
import {
    getKnowledgeBase,
    getKnowledgeBaseAccess,
} from '../api/knowledgeApi'
import type {
    KnowledgeBase,
    KnowledgeBaseAccess,
} from '../api/knowledgeApi'
import {
    getKnowledgeDocuments,
    getKnowledgeHealth,
} from '../api/knowledgeDocumentApi'
import type {
    KnowledgeDocument,
    KnowledgeHealth,
} from '../api/knowledgeDocumentApi'
import {
    getApiErrorMessage,
} from '../api/http'
import type {
    PageResponse,
} from '../utils/page'

export const KNOWLEDGE_DOCUMENT_PAGE_SIZE = 50

const EMPTY_DOCUMENT_PAGE:
    PageResponse<KnowledgeDocument> = {
    content: [],
    page: 0,
    size: KNOWLEDGE_DOCUMENT_PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
}

export function useKnowledgeDetailsData(
    knowledgeBaseId: string,
) {
    const [base, setBase] =
        useState<KnowledgeBase | null>(null)
    const [health, setHealth] =
        useState<KnowledgeHealth | null>(null)
    const [access, setAccess] =
        useState<KnowledgeBaseAccess | null>(null)
    const [loading, setLoading] =
        useState(true)
    const [error, setError] =
        useState('')

    const [documentPage, setDocumentPage] =
        useState(0)
    const [documentsPage, setDocumentsPage] =
        useState<PageResponse<KnowledgeDocument>>(
            EMPTY_DOCUMENT_PAGE,
        )
    const [documentsLoading, setDocumentsLoading] =
        useState(true)
    const [documentsError, setDocumentsError] =
        useState('')

    const loadOverview = useCallback(
        async (signal?: AbortSignal) => {
            const [
                knowledgeBase,
                knowledgeHealth,
                knowledgeAccess,
            ] = await Promise.all([
                getKnowledgeBase(
                    knowledgeBaseId,
                    {
                        signal,
                    },
                ),
                getKnowledgeHealth(
                    knowledgeBaseId,
                    signal,
                ),
                getKnowledgeBaseAccess(
                    knowledgeBaseId,
                    {
                        signal,
                    },
                ),
            ])

            setBase(knowledgeBase)
            setHealth(knowledgeHealth)
            setAccess(knowledgeAccess)
        },
        [knowledgeBaseId],
    )

    const loadDocuments = useCallback(
        async (
            targetPage: number,
            signal?: AbortSignal,
        ) => {
            const response =
                await getKnowledgeDocuments(
                    knowledgeBaseId,
                    targetPage,
                    KNOWLEDGE_DOCUMENT_PAGE_SIZE,
                    signal,
                )

            if (
                response.totalPages > 0
                && targetPage
                    >= response.totalPages
            ) {
                setDocumentPage(
                    response.totalPages - 1,
                )
                return
            }

            setDocumentsPage(response)
        },
        [knowledgeBaseId],
    )

    const refreshAfterSuccessfulUpload =
        useCallback(
            async (targetPage: number) => {
                setError('')
                setDocumentsError('')

                const [
                    overviewResult,
                    documentsResult,
                ] = await Promise.allSettled([
                    loadOverview(),
                    loadDocuments(targetPage),
                ])

                if (
                    overviewResult.status
                    === 'rejected'
                ) {
                    setError(
                        getApiErrorMessage(
                            overviewResult.reason,
                            'Документ загружен, но не удалось обновить состояние базы знаний.',
                        ),
                    )
                }

                if (
                    documentsResult.status
                    === 'rejected'
                ) {
                    setDocumentsError(
                        getApiErrorMessage(
                            documentsResult.reason,
                            'Документ загружен, но не удалось обновить список документов.',
                        ),
                    )
                }
            },
            [
                loadDocuments,
                loadOverview,
            ],
        )

    useEffect(() => {
        const controller =
            new AbortController()

        queueMicrotask(() => {
            if (controller.signal.aborted) {
                return
            }

            setLoading(true)
            setError('')

            void loadOverview(
                controller.signal,
            )
                .catch((loadError) => {
                    if (
                        !controller.signal.aborted
                    ) {
                        setError(
                            getApiErrorMessage(
                                loadError,
                                'Не удалось открыть базу знаний.',
                            ),
                        )
                    }
                })
                .finally(() => {
                    if (
                        !controller.signal.aborted
                    ) {
                        setLoading(false)
                    }
                })
        })

        return () => {
            controller.abort()
        }
    }, [loadOverview])

    useEffect(() => {
        const controller =
            new AbortController()

        queueMicrotask(() => {
            if (controller.signal.aborted) {
                return
            }

            setDocumentsLoading(true)
            setDocumentsError('')

            void loadDocuments(
                documentPage,
                controller.signal,
            )
                .catch((loadError) => {
                    if (
                        !controller.signal.aborted
                    ) {
                        setDocumentsError(
                            getApiErrorMessage(
                                loadError,
                                'Не удалось загрузить документы базы знаний.',
                            ),
                        )
                    }
                })
                .finally(() => {
                    if (
                        !controller.signal.aborted
                    ) {
                        setDocumentsLoading(false)
                    }
                })
        })

        return () => {
            controller.abort()
        }
    }, [
        documentPage,
        loadDocuments,
    ])


    async function retryOverview() {
        setError('')

        try {
            await Promise.all([
                loadOverview(),
                loadDocuments(documentPage),
            ])
        } catch (loadError) {
            setError(
                getApiErrorMessage(
                    loadError,
                    'Не удалось обновить данные базы знаний.',
                ),
            )
        }
    }

    async function retryDocuments() {
        setDocumentsError('')
        setDocumentsLoading(true)

        try {
            await loadDocuments(documentPage)
        } catch (loadError) {
            setDocumentsError(
                getApiErrorMessage(
                    loadError,
                    'Не удалось загрузить документы базы знаний.',
                ),
            )
        } finally {
            setDocumentsLoading(false)
        }
    }

    return {
        base,
        health,
        access,
        loading,
        error,
        setError,
        documentPage,
        setDocumentPage,
        documentsPage,
        documents:
            documentsPage.content,
        documentsLoading,
        documentsError,
        loadOverview,
        loadDocuments,
        refreshAfterSuccessfulUpload,
        retryOverview,
        retryDocuments,
    }
}
