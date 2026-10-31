// frontend/src/pages/KnowledgeDetailsPage.test.tsx
// Регрессии управления документами, границ доступа, операций и обработки ошибок.
import {
    fireEvent,
    render,
    screen,
    waitFor,
    within,
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
} from '../shared/api/http'
import {
    getKnowledgeBase,
    getKnowledgeBaseAccess,
} from '../entities/knowledge/api/knowledgeApi'
import type {
    KnowledgeBase,
} from '../entities/knowledge/api/knowledgeApi'
import {
    fetchKnowledgeDocumentBlob,
    getKnowledgeDocuments,
    getKnowledgeHealth,
    reindexKnowledgeDocument,
    updateKnowledgeDocument,
    uploadKnowledgeDocument,
    uploadKnowledgeDocumentVersion,
} from '../entities/knowledge/api/knowledgeDocumentApi'
import type {
    KnowledgeDocument,
} from '../entities/knowledge/api/knowledgeDocumentApi'
import {
    acceptDocumentDeletion,
    deletionCapability,
    listDocumentDeletions,
} from '../entities/knowledge/api/knowledgeDocumentDeletionApi'
import KnowledgeDetailsPage
    from './KnowledgeDetailsPage'

vi.mock(
    '../entities/knowledge/api/knowledgeApi',
    () => ({
        getKnowledgeBase:
            vi.fn(),
        getKnowledgeBaseAccess:
            vi.fn(),
    }),
)

vi.mock(
    '../entities/knowledge/api/knowledgeDocumentApi',
    async (importOriginal) => {
        const actual =
            await importOriginal<
                typeof import(
                    '../entities/knowledge/api/knowledgeDocumentApi'
                )
            >()

        return {
            ...actual,
            fetchKnowledgeDocumentBlob:
                vi.fn(),
            getKnowledgeDocuments:
                vi.fn(),
            getKnowledgeHealth:
                vi.fn(),
            reindexKnowledgeDocument:
                vi.fn(),
            updateKnowledgeDocument:
                vi.fn(),
            uploadKnowledgeDocument:
                vi.fn(),
            uploadKnowledgeDocumentVersion:
                vi.fn(),
        }
    },
)

vi.mock('../entities/knowledge/api/knowledgeDocumentDeletionApi', () => ({
    acceptDocumentDeletion: vi.fn(),
    deletionCapability: vi.fn(),
    listDocumentDeletions: vi.fn(),
}))

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
    searchableVersionId:
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

const deletionCapabilityMock = vi.mocked(deletionCapability)
const listDocumentDeletionsMock = vi.mocked(listDocumentDeletions)
const acceptDocumentDeletionMock = vi.mocked(acceptDocumentDeletion)

const getKnowledgeBaseMock =
    vi.mocked(
        getKnowledgeBase,
    )

const getKnowledgeBaseAccessMock =
    vi.mocked(
        getKnowledgeBaseAccess,
    )

const getKnowledgeDocumentsMock =
    vi.mocked(
        getKnowledgeDocuments,
    )

const fetchKnowledgeDocumentBlobMock =
    vi.mocked(
        fetchKnowledgeDocumentBlob,
    )

const getKnowledgeHealthMock = vi.mocked(getKnowledgeHealth)
const reindexKnowledgeDocumentMock = vi.mocked(reindexKnowledgeDocument)
const updateKnowledgeDocumentMock = vi.mocked(updateKnowledgeDocument)

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

            deletionCapabilityMock.mockResolvedValue(false)
            listDocumentDeletionsMock.mockResolvedValue([])

            getKnowledgeBaseMock
                .mockResolvedValue(
                    KNOWLEDGE_BASE,
                )

            getKnowledgeBaseAccessMock
                .mockResolvedValue({
                    knowledgeBaseId:
                        KNOWLEDGE_BASE_ID,
                    accessLevel:
                        'OWNER',
                    administrator:
                        true,
                    canEditDocuments:
                        true,
                    canManageBase:
                        true,
                    canManageMembers:
                        true,
                })

            getKnowledgeDocumentsMock
                .mockResolvedValue({
                    content: [
                        DOCUMENT,
                    ],
                    page: 0,
                    size: 50,
                    totalElements: 1,
                    totalPages: 1,
                })

            fetchKnowledgeDocumentBlobMock
                .mockResolvedValue(
                    new Blob(
                        ['%PDF-1.7'],
                        {
                            type:
                                'application/pdf',
                        },
                    ),
                )

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

        it('OWNER видит неактивное удаление, пока backend запрещает его', async () => {
            renderPage()
            fireEvent.click(await screen.findByText('Другие действия'))
            const button = screen.getByRole('button', {name: 'Удалить…'})
            await waitFor(() => expect(deletionCapabilityMock).toHaveBeenCalledWith(
                KNOWLEDGE_BASE_ID, expect.any(AbortSignal),
            ))
            expect(button).toBeDisabled()
            await waitFor(() => expect(button).toHaveAttribute('title',
                'Безвозвратное удаление пока не включено на сервере.'))
            expect(acceptDocumentDeletionMock).not.toHaveBeenCalled()
            await waitFor(() => expect(listDocumentDeletionsMock).toHaveBeenCalledWith(
                    KNOWLEDGE_BASE_ID, expect.any(AbortSignal),
                ))
        })

        it('ошибка capability закрывает опасную команду, а не разрешает удаление', async () => {
            deletionCapabilityMock.mockRejectedValue(new Error('Backend unavailable'))
            renderPage()
            fireEvent.click(await screen.findByText('Другие действия'))
            const button = screen.getByRole('button', {name: 'Удалить…'})
            await waitFor(() => expect(button.getAttribute('title'))
                .toMatch(/Не удалось проверить возможность удаления/))
            expect(button).toBeDisabled()
            expect(acceptDocumentDeletionMock).not.toHaveBeenCalled()
        })

        it('OWNER подтверждает удаление точным названием, DELETING не считается DELETED', async () => {
            deletionCapabilityMock.mockResolvedValue(true)
            acceptDocumentDeletionMock.mockResolvedValue({
                operationId: '5cb5d02d-88ec-4970-812f-c421af5239e3',
                documentId: DOCUMENT_ID,
                state: 'DELETING',
                createdAt: '2026-10-28T18:00:00Z',
                completedAt: null,
            })
            renderPage()
                fireEvent.click(await screen.findByText('Другие действия'))
                const button = screen.getByRole('button', {name: 'Удалить…'})
                await waitFor(() => expect(button).toBeEnabled())
                fireEvent.click(button)
                const dialog = screen.getByRole('dialog')
                const confirm = within(dialog).getByRole('button', {name: 'Удалить безвозвратно'})
                expect(confirm).toBeDisabled()
                fireEvent.change(within(dialog).getByRole('textbox', {name: 'Название документа'}),
                    {target: {value: DOCUMENT.name}})
                fireEvent.click(confirm)
                await waitFor(() => expect(acceptDocumentDeletionMock).toHaveBeenCalledWith(
                    KNOWLEDGE_BASE_ID, DOCUMENT_ID, DOCUMENT.version,
                    expect.stringMatching(/^[a-f0-9-]{36}$/),
                ))
                expect(await screen.findByText(/Удаление выполняется/)).toBeInTheDocument()
                expect(screen.queryByText(/Документ удалён/)).not.toBeInTheDocument()
        })

        it('VIEWER не видит команду удаления даже при разрешённом feature flag', async () => {
            deletionCapabilityMock.mockResolvedValue(true)
            getKnowledgeBaseAccessMock.mockResolvedValue({
                knowledgeBaseId: KNOWLEDGE_BASE_ID,
                accessLevel: 'VIEWER', administrator: false,
                canEditDocuments: false, canManageBase: false, canManageMembers: false,
            })
            renderPage()
            await screen.findByText(DOCUMENT.name)
            expect(screen.queryByRole('button', {name: 'Удалить…'})).not.toBeInTheDocument()
            expect(acceptDocumentDeletionMock).not.toHaveBeenCalled()
        })

        it('отключает документ одним PATCH после подтверждения и обновляет список', async () => {
            updateKnowledgeDocumentMock.mockResolvedValue({
                ...DOCUMENT,
                enabled: false,
                version: 2,
            })
            renderPage()
            fireEvent.click(await screen.findByText('Другие действия'))
            fireEvent.click(screen.getByRole('button', {name: 'Отключить'}))
            expect(updateKnowledgeDocumentMock).not.toHaveBeenCalled()
            fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {name: 'Отключить'}))
            await waitFor(() => {
                expect(updateKnowledgeDocumentMock).toHaveBeenCalledWith(
                    KNOWLEDGE_BASE_ID,
                    DOCUMENT_ID,
                    {name: DOCUMENT.name, enabled: false, expectedVersion: 1},
                )
            })
            expect(updateKnowledgeDocumentMock).toHaveBeenCalledTimes(1)
        })

        it('при сетевой ошибке PATCH не показывает успех и не делает повторных запросов', async () => {
            updateKnowledgeDocumentMock.mockRejectedValue(new Error('Сеть недоступна'))
            renderPage()
            fireEvent.click(await screen.findByText('Другие действия'))
            fireEvent.click(screen.getByRole('button', {name: 'Отключить'}))
            fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {
                name: 'Отключить',
            }))
            await waitFor(() => {
                expect(updateKnowledgeDocumentMock).toHaveBeenCalledTimes(1)
                expect(screen.getByRole('dialog')).toBeInTheDocument()
            })
        })

        it('включает ранее отключённый документ с актуальной версией', async () => {
            getKnowledgeDocumentsMock.mockResolvedValue({
                content: [{...DOCUMENT, enabled: false, version: 8}],
                page: 0, size: 50, totalElements: 1, totalPages: 1,
            })
            updateKnowledgeDocumentMock.mockResolvedValue({
                ...DOCUMENT,
                enabled: true,
                version: 9,
            })
            renderPage()
            fireEvent.click(await screen.findByText('Другие действия'))
            fireEvent.click(screen.getByRole('button', {name: 'Включить'}))
            fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {
                name: 'Включить',
            }))
            await waitFor(() => expect(updateKnowledgeDocumentMock).toHaveBeenCalledWith(
                KNOWLEDGE_BASE_ID,
                DOCUMENT_ID,
                {name: DOCUMENT.name, enabled: true, expectedVersion: 8},
            ))
        })

        it('VIEWER не получает управление состоянием документа', async () => {
            getKnowledgeBaseAccessMock.mockResolvedValue({
                knowledgeBaseId: KNOWLEDGE_BASE_ID,
                accessLevel: 'VIEWER',
                administrator: false,
                canEditDocuments: false,
                canManageBase: false,
                canManageMembers: false,
            })
            renderPage()
            await screen.findByText(DOCUMENT.name)
            expect(screen.queryByText('Другие действия')).not.toBeInTheDocument()
        })

        it('не запускает reindex, пока документ обрабатывается', async () => {
            getKnowledgeDocumentsMock.mockResolvedValue({
                content: [{ ...DOCUMENT, status: 'CHUNKING' }],
                page: 0, size: 50, totalElements: 1, totalPages: 1,
            })
            renderPage()
            fireEvent.click(await screen.findByText('Другие действия'))
            expect(screen.getByRole('button', {
                name: 'Переиндексировать',
            })).toBeDisabled()
            expect(reindexKnowledgeDocumentMock).not.toHaveBeenCalled()
        })

        it('различает текущую и ранее опубликованную поисковую версию', async () => {
            getKnowledgeDocumentsMock.mockResolvedValue({
                content: [{
                    ...DOCUMENT,
                    status: 'FAILED',
                    searchableVersionId: 'f728ad87-e91f-4db5-a1c5-c83d88dc6a27',
                }],
                page: 0, size: 50, totalElements: 1, totalPages: 1,
            })
            renderPage()
            expect(await screen.findByText(/Предыдущая поисковая версия сохранена/))
                .toBeInTheDocument()
            expect(screen.getByText(/Точная причина не передаётся этим API/))
                .toBeInTheDocument()
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
            'имя файла не ведёт напрямую на защищённый API endpoint',
            async () => {
                renderPage()

                const openButton =
                    await screen.findByRole(
                        'button',
                        {
                            name:
                                `Открыть файл ${DOCUMENT.originalFilename} в новой вкладке`,
                        },
                    )

                expect(openButton)
                    .toBeInTheDocument()

                expect(
                    screen.queryByRole(
                        'link',
                        {
                            name:
                                `Скачать файл ${DOCUMENT.originalFilename}`,
                        },
                    ),
                ).not.toBeInTheDocument()
            },
        )

        it(
            'скачивает документ через authenticated API client',
            async () => {
                const createObjectUrl =
                    vi.fn(
                        () =>
                            'blob:safeai-test',
                    )
                const revokeObjectUrl =
                    vi.fn()

                Object.defineProperty(
                    URL,
                    'createObjectURL',
                    {
                        configurable: true,
                        value:
                            createObjectUrl,
                    },
                )

                Object.defineProperty(
                    URL,
                    'revokeObjectURL',
                    {
                        configurable: true,
                        value:
                            revokeObjectUrl,
                    },
                )

                const click =
                    vi.spyOn(
                        HTMLAnchorElement.prototype,
                        'click',
                    )
                    .mockImplementation(
                        () => undefined,
                    )

                renderPage()

                fireEvent.click(
                    await screen.findByRole(
                        'button',
                        {
                            name:
                                `Скачать ${DOCUMENT.originalFilename}`,
                        },
                    ),
                )

                await waitFor(() => {
                    expect(
                        fetchKnowledgeDocumentBlobMock,
                    ).toHaveBeenCalledWith(
                        KNOWLEDGE_BASE_ID,
                        DOCUMENT_ID,
                    )
                })

                expect(createObjectUrl)
                    .toHaveBeenCalledTimes(1)
                expect(click)
                    .toHaveBeenCalledTimes(1)
            },
        )

        it(
            'показывает здоровье индекса и запускает reindex текущей версии',
            async () => {
                getKnowledgeDocumentsMock.mockResolvedValue({
                    content: [{ ...DOCUMENT, status: 'READY' }],
                    page: 0, size: 50, totalElements: 1, totalPages: 1,
                })
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

                fireEvent.click(screen.getByText('Другие действия'))
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
            'поддерживает backend-статус CHUNKING',
            async () => {
                getKnowledgeDocumentsMock
                    .mockResolvedValue({
                        content: [
                            {
                                ...DOCUMENT,
                                status: 'CHUNKING',
                            },
                        ],
                        page: 0,
                        size: 50,
                        totalElements: 1,
                        totalPages: 1,
                    })

                renderPage()

                expect(
                    await screen.findByText(
                        'Формируются фрагменты',
                    ),
                ).toBeInTheDocument()
            },
        )

        it(
            'не показывает ложную ошибку загрузки если POST успешен, а последующий refresh списка упал',
            async () => {
                const createdDocument:
                    KnowledgeDocument = {
                    ...DOCUMENT,
                    id:
                        'd72a991f-4a60-4eb2-894e-7cf13126f80a',
                    name:
                        'Повтор документа',
                    originalFilename:
                        'document.txt',
                    mediaType:
                        'text/plain',
                    status:
                        'PENDING',
                }

                uploadKnowledgeDocumentMock
                    .mockResolvedValue(
                        createdDocument,
                    )

                /*
                 * Первый вызов нужен начальному render.
                 * Второй вызывается уже после успешного POST и имитирует
                 * transient/contract failure именно refresh-запроса.
                 */
                getKnowledgeDocumentsMock
                    .mockResolvedValueOnce({
                        content: [
                            DOCUMENT,
                        ],
                        page: 0,
                        size: 50,
                        totalElements: 1,
                        totalPages: 1,
                    })
                    .mockRejectedValueOnce(
                        new Error(
                            'temporary refresh failure',
                        ),
                    )

                renderPage()

                await screen.findByRole(
                    'heading',
                    {
                        name:
                            'Загруженные документы',
                    },
                )

                await openUploadAndSelectFile()

                await waitFor(() => {
                    expect(
                        uploadKnowledgeDocumentMock,
                    ).toHaveBeenCalledTimes(
                        1,
                    )
                })

                await waitFor(() => {
                    expect(
                        screen.queryByText(
                            'Загрузка документа',
                        ),
                    ).not.toBeInTheDocument()
                })

                expect(
                    screen.queryByText(
                        'Не удалось загрузить файл.',
                    ),
                ).not.toBeInTheDocument()

                expect(
                    await screen.findByText(
                        'Документ загружен, но не удалось обновить список документов.',
                    ),
                ).toBeInTheDocument()
            },
        )

        it(
            'загружает следующую страницу документов через backend pagination',
            async () => {
                getKnowledgeDocumentsMock
                    .mockImplementation(
                        async (
                            _knowledgeBaseId,
                            requestedPage,
                            size,
                        ) => ({
                            content: [
                                DOCUMENT,
                            ],
                            page:
                                requestedPage
                                ?? 0,
                            size:
                                size
                                ?? 50,
                            totalElements: 51,
                            totalPages: 2,
                        }),
                    )

                renderPage()

                const nextButton =
                    await screen.findByRole(
                        'button',
                        {
                            name: 'Вперёд',
                        },
                    )

                fireEvent.click(nextButton)

                await waitFor(() => {
                    expect(
                        getKnowledgeDocumentsMock,
                    ).toHaveBeenCalledWith(
                        KNOWLEDGE_BASE_ID,
                        1,
                        50,
                        expect.anything(),
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
            'для 500 сохраняет Request ID, проверяет список и блокирует повторный POST',
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
                        'Не удалось подтвердить результат загрузки.',
                    )
                expect(alert)
                    .toHaveTextContent(
                        'Документ мог сохраниться на сервере.',
                    )
                expect(alert)
                    .toHaveTextContent(
                        'Не отправляйте тот же файл повторно до проверки.',
                    )
                expect(alert)
                    .not.toHaveTextContent(
                        'Sensitive internal details',
                    )
                expect(alert)
                    .not.toHaveTextContent(
                        'INTERNAL_ERROR',
                    )
                expect(alert)
                    .toHaveTextContent(
                        'Код запроса:',
                    )
                expect(alert)
                    .toHaveTextContent(
                        'request-server-500',
                    )

                // Initial read + read-only reconciliation after uncertain POST.
                await waitFor(() => {
                    expect(getKnowledgeDocumentsMock)
                        .toHaveBeenCalledTimes(2)
                })
                expect(uploadKnowledgeDocumentMock)
                    .toHaveBeenCalledTimes(1)
                expect(uploadKnowledgeDocumentVersionMock)
                    .not.toHaveBeenCalled()

                const submitButton = screen.getByRole(
                    'button',
                    { name: 'Загрузить' },
                )
                expect(submitButton).toBeDisabled()
                fireEvent.click(submitButton)
                expect(uploadKnowledgeDocumentMock)
                    .toHaveBeenCalledTimes(1)
            },
        )

        it('для просмотра без права редактирования не открывает диалог загрузки', async () => {
            getKnowledgeBaseAccessMock.mockResolvedValueOnce({
                knowledgeBaseId: KNOWLEDGE_BASE_ID,
                accessLevel: 'VIEWER', administrator: false,
                canEditDocuments: false, canManageBase: false, canManageMembers: false,
            })
            renderPage()
            await screen.findByRole('heading', { name: 'Загруженные документы' })
            expect(screen.queryByRole('button', { name: 'Загрузить документ' }))
                .not.toBeInTheDocument()
            expect(uploadKnowledgeDocumentMock).not.toHaveBeenCalled()
            expect(reindexKnowledgeDocumentMock).not.toHaveBeenCalled()
        })

        it('загрузка не выполняется, если файл превышает 25 МБ', async () => {
            renderPage()
            fireEvent.click(await screen.findByRole('button', { name: 'Загрузить документ' }))
            const large = new File(['oversized'], 'oversized.pdf', { type: 'application/pdf' })
            Object.defineProperty(large, 'size', { value: 25 * 1024 * 1024 + 1 })
            fireEvent.change(screen.getByLabelText('Файл'), {
                target: { files: [large] },
            })
            expect(screen.getByText('Размер файла превышает 25 МБ.')).toBeInTheDocument()
            expect(screen.getByRole('button', { name: 'Загрузить' })).toBeDisabled()
            expect(uploadKnowledgeDocumentMock).not.toHaveBeenCalled()
        })
    },
)
