import {
    fireEvent,
    render,
    screen,
    waitFor,
} from '@testing-library/react'
import {
    MemoryRouter,
    Route,
    Routes,
} from 'react-router-dom'
import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    ApiError,
} from '../api/http'
import {
    getKnowledgeBase,
} from '../api/knowledgeApi'
import type {
    KnowledgeBase,
} from '../api/knowledgeApi'
import {
    getKnowledgeDocuments,
    getKnowledgeHealth,
    reindexKnowledgeDocument,
    uploadKnowledgeDocument,
    uploadKnowledgeDocumentVersion,
} from '../api/knowledgeDocumentApi'
import type {
    KnowledgeDocument,
} from '../api/knowledgeDocumentApi'
import KnowledgeDetailsPage
    from './KnowledgeDetailsPage'

vi.mock(
    '../api/knowledgeApi',
    () => ({
        getKnowledgeBase:
            vi.fn(),
    }),
)

vi.mock(
    '../api/knowledgeDocumentApi',
    async (importOriginal) => {
        const actual =
            await importOriginal<
                typeof import(
                    '../api/knowledgeDocumentApi'
                )
            >()

        return {
            ...actual,
            getKnowledgeDocuments:
                vi.fn(),
            getKnowledgeHealth:
                vi.fn(),
            reindexKnowledgeDocument:
                vi.fn(),
            uploadKnowledgeDocument:
                vi.fn(),
            uploadKnowledgeDocumentVersion:
                vi.fn(),
        }
    },
)

const KNOWLEDGE_BASE_ID =
    '28ae4cac-2f14-42af-85e9-e3f1385a249f'

const DOCUMENT_ID =
    '534f6ea3-595b-47be-a90a-6397a96d16b8'

const KNOWLEDGE_BASE:
    KnowledgeBase = {
    id:
        KNOWLEDGE_BASE_ID,
    organizationId:
        'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    name:
        'ABC',
    description:
        'Тестовая база знаний.',
    visibility:
        'ORGANIZATION',
    enabled:
        true,
    createdByUserId:
        'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    version:
        1,
    createdAt:
        '2026-08-14T17:56:46Z',
    updatedAt:
        '2026-08-16T18:26:34Z',
}

const DOCUMENT:
    KnowledgeDocument = {
    id:
        DOCUMENT_ID,
    knowledgeBaseId:
        KNOWLEDGE_BASE_ID,
    name:
        '04_Техническая_архитектура_SafeAI',
    enabled:
        true,
    version:
        1,
    currentVersionId:
        'ce73e260-0e85-47f0-9d3e-4bab1a833150',
    versionNumber:
        1,
    originalFilename:
        '04_Техническая_архитектура_SafeAI.pdf',
    mediaType:
        'application/pdf',
    sizeBytes:
        78_286,
    status:
        'PENDING',
    createdAt:
        '2026-08-16T18:23:49Z',
    updatedAt:
        '2026-08-16T18:23:49Z',
}

const getKnowledgeBaseMock =
    vi.mocked(
        getKnowledgeBase,
    )

const getKnowledgeDocumentsMock =
    vi.mocked(
        getKnowledgeDocuments,
    )

const getKnowledgeHealthMock = vi.mocked(getKnowledgeHealth)
const reindexKnowledgeDocumentMock = vi.mocked(reindexKnowledgeDocument)

const uploadKnowledgeDocumentMock =
    vi.mocked(
        uploadKnowledgeDocument,
    )

const uploadKnowledgeDocumentVersionMock =
    vi.mocked(
        uploadKnowledgeDocumentVersion,
    )

function renderPage() {
    return render(
        <MemoryRouter
            initialEntries={[
                `/knowledge/${KNOWLEDGE_BASE_ID}`,
            ]}
        >
            <Routes>
                <Route
                    path="/knowledge/:knowledgeBaseId"
                    element={
                        <KnowledgeDetailsPage />
                    }
                />
            </Routes>
        </MemoryRouter>,
    )
}

async function openUploadAndSelectFile() {
    fireEvent.click(
        await screen.findByRole(
            'button',
            {
                name:
                    'Загрузить документ',
            },
        ),
    )

    fireEvent.change(
        screen.getByLabelText(
            'Название документа',
        ),
        {
            target: {
                value:
                    'Повтор документа',
            },
        },
    )

    const file =
        new File(
            [
                'SafeAI test file',
            ],
            'document.txt',
            {
                type:
                    'text/plain',
            },
        )

    fireEvent.change(
        screen.getByLabelText(
            'Файл',
        ),
        {
            target: {
                files: [
                    file,
                ],
            },
        },
    )

    fireEvent.click(
        screen.getByRole(
            'button',
            {
                name:
                    'Загрузить',
            },
        ),
    )
}

describe(
    'KnowledgeDetailsPage',
    () => {
        beforeEach(() => {
            vi.clearAllMocks()

            getKnowledgeBaseMock
                .mockResolvedValue(
                    KNOWLEDGE_BASE,
                )

            getKnowledgeDocumentsMock
                .mockResolvedValue({
                    content: [
                        DOCUMENT,
                    ],
                    page: 0,
                    size: 100,
                    totalElements: 1,
                    totalPages: 1,
                })

            getKnowledgeHealthMock.mockResolvedValue({
                knowledgeBaseId: KNOWLEDGE_BASE_ID,
                state: 'HEALTHY',
                activeEmbeddingModel: 'text-embedding-3-small',
                totalDocuments: 1,
                enabledDocuments: 1,
                searchableDocuments: 1,
                pendingDocuments: 0,
                processingDocuments: 0,
                failedDocuments: 0,
                staleEmbeddingDocuments: 0,
                activeChunks: 12,
                checkedAt: '2026-08-21T10:00:00Z',
            })

            reindexKnowledgeDocumentMock.mockResolvedValue({
                knowledgeBaseId: KNOWLEDGE_BASE_ID,
                documentId: DOCUMENT_ID,
                documentVersionId: DOCUMENT.currentVersionId!,
                ingestionJobId: '7d35ae31-1881-4a55-8a54-2464da832929',
                status: 'PENDING',
                requestedAt: '2026-08-21T10:00:00Z',
            })
        })

        it(
            'file picker разрешает production whitelist из 10 форматов',
            async () => {
                renderPage()

                fireEvent.click(
                    await screen.findByRole(
                        'button',
                        {
                            name:
                                'Загрузить документ',
                        },
                    ),
                )

                const input =
                    screen.getByLabelText(
                        'Файл',
                    )

                expect(input)
                    .toHaveAttribute(
                        'accept',
                        expect.stringContaining(
                            '.pdf,.docx,.txt,.html,.htm,.md,.csv,.xlsx,.pptx,.json,.xml',
                        ),
                    )
            },
        )

        it(
            'имя исходного файла является ссылкой на безопасный download endpoint',
            async () => {
                renderPage()

                const fileLink =
                    await screen.findByRole(
                        'link',
                        {
                            name:
                                `Скачать файл ${DOCUMENT.originalFilename}`,
                        },
                    )

                expect(fileLink)
                    .toHaveAttribute(
                        'href',
                        `/api/knowledge-bases/${KNOWLEDGE_BASE_ID}/documents/${DOCUMENT_ID}/download`,
                    )
            },
        )

        it(
            'показывает здоровье индекса и запускает reindex текущей версии',
            async () => {
                renderPage()

                expect(
                    await screen.findByText('База готова к ответам'),
                ).toBeInTheDocument()
                expect(
                    screen.getByText('Фрагменты для AI'),
                ).toBeInTheDocument()
                expect(
                    screen.getByRole('heading', {
                        name: 'Загруженные документы',
                    }),
                ).toBeInTheDocument()
                expect(screen.getByText('12')).toBeInTheDocument()

                fireEvent.click(screen.getByRole('button', {
                    name: 'Переиндексировать',
                }))

                await waitFor(() => {
                    expect(reindexKnowledgeDocumentMock).toHaveBeenCalledWith(
                        KNOWLEDGE_BASE_ID,
                        DOCUMENT_ID,
                    )
                })
            },
        )

        it(
            'для CONFLICT показывает сообщение без технического Request ID',
            async () => {
                uploadKnowledgeDocumentMock
                    .mockRejectedValue(
                        new ApiError(
                            'Документ с таким названием уже существует.',
                            {
                                status: 409,
                                error:
                                    'CONFLICT',
                                message:
                                    'Документ с таким названием уже существует.',
                                requestId:
                                    'request-conflict-123',
                            },
                            409,
                        ),
                    )

                renderPage()
                await openUploadAndSelectFile()

                const alert =
                    await screen.findByRole(
                        'alert',
                    )

                expect(alert)
                    .toHaveTextContent(
                        'Документ с таким названием уже существует.',
                    )

                expect(alert)
                    .not.toHaveTextContent(
                        'Код запроса:',
                    )

                expect(alert)
                    .not.toHaveTextContent(
                        'request-conflict-123',
                    )

                expect(
                    uploadKnowledgeDocumentMock,
                ).toHaveBeenCalledTimes(1)

                expect(
                    uploadKnowledgeDocumentVersionMock,
                ).not.toHaveBeenCalled()
            },
        )

        it(
            'для внутренней 500 ошибки показывает код запроса',
            async () => {
                uploadKnowledgeDocumentMock
                    .mockRejectedValue(
                        new ApiError(
                            'Sensitive internal details',
                            {
                                status: 500,
                                error:
                                    'INTERNAL_ERROR',
                                message:
                                    'Sensitive internal details',
                                requestId:
                                    'request-server-500',
                            },
                            500,
                        ),
                    )

                renderPage()
                await openUploadAndSelectFile()

                const alert =
                    await screen.findByRole(
                        'alert',
                    )

                expect(alert)
                    .toHaveTextContent(
                        'Не удалось загрузить файл.',
                    )

                expect(alert)
                    .toHaveTextContent(
                        'Код запроса:',
                    )

                expect(alert)
                    .toHaveTextContent(
                        'request-server-500',
                    )
            },
        )
    },
)
