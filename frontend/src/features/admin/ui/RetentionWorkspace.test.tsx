// frontend/src/features/admin/ui/RetentionWorkspace.test.tsx
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { loadRetention, saveRetention, type RetentionStatus } from '../api/retentionApi'
import { RetentionWorkspace } from './RetentionWorkspace'

vi.mock('../api/retentionApi', () => ({loadRetention: vi.fn(), saveRetention: vi.fn()}))

const prepared = {configured: true, workerInitialized: true}
const notPrepared = {configured: false, workerInitialized: false}
const status: RetentionStatus = {
    chatAllowed: true, knowledgeAllowed: true, version: 4,
    chat: prepared, document: prepared, base: prepared, userRetentionSeconds: 604800,
}

function mount() {
    return render(<MemoryRouter><RetentionWorkspace/></MemoryRouter>)
}

function confirmAction() {
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {
        name: /новые запросы/,
    }))
}

describe('Хранение и удаление: понятная политика и безопасные действия', () => {
    beforeEach(() => {
        vi.resetAllMocks()
        vi.mocked(loadRetention).mockResolvedValue(status)
    })

    it('показывает краткую сводку и готовность для каждой категории без ложного обещания удаления', async () => {
        mount()
        expect(await screen.findByText('Хранение и удаление')).toBeInTheDocument()
        expect(screen.getByText(/2 из 2 направлений разрешены политикой/)).toBeInTheDocument()
        expect(screen.getByText(/3 из 3 типов настроены/)).toBeInTheDocument()
        expect(screen.getAllByText('Настроено')).toHaveLength(3)
        expect(screen.getByText(/Наличие настроек и обработчика не подтверждает/))
            .toHaveTextContent(/ни его работоспособность, ни фактическое удаление данных/)
        expect(screen.queryByText('Новые операции удаления могут быть отклонены')).not.toBeInTheDocument()
    })

    it('предупреждает об ожидании при открытом приёме, если сервер не подготовлен', async () => {
        vi.mocked(loadRetention).mockResolvedValue({...status, chat: notPrepared,
            document: {...prepared, workerInitialized: false}})
        mount()
        expect(await screen.findByText('Новые операции удаления могут быть отклонены')).toBeInTheDocument()
        expect(screen.getByText(/не принимаются автоматически в очередь/)).toBeInTheDocument()
        expect(screen.getByText(/1 из 3 типов настроены/)).toBeInTheDocument()
        expect(screen.getByText('Не завершены настройки и подготовка сервиса')).toBeInTheDocument()
        expect(screen.getByText('Сервис удаления ещё не подготовлен')).toBeInTheDocument()
    })

    it('не выдумывает причину, если отсутствуют только настройки', async () => {
        vi.mocked(loadRetention).mockResolvedValue({...status, chat: {...prepared, configured: false}})
        mount()
        expect(await screen.findByText('Настройки удаления ещё не включены')).toBeInTheDocument()
        expect(screen.getAllByText('Не готово')).toHaveLength(1)
    })

    it('различает действия для чатов и баз знаний, сохраняет версию только после подтверждения', async () => {
        vi.mocked(saveRetention).mockResolvedValue({...status, chatAllowed: false, version: 5})
        mount()
        const pause = await screen.findByRole('button', {
            name: 'Приостановить приём запросов на удаление чатов',
        })
        expect(screen.getByRole('button', {
            name: 'Приостановить приём запросов на удаление документов и баз',
        })).toBeInTheDocument()
        fireEvent.click(pause)
        expect(saveRetention).not.toHaveBeenCalled()
        expect(screen.getByRole('dialog')).toHaveTextContent(/только новые запросы/)
        confirmAction()
        expect(await screen.findByText('Изменения сохранены.', {exact: false})).toBeInTheDocument()
        expect(saveRetention).toHaveBeenCalledExactlyOnceWith({
            chatAllowed: false, knowledgeAllowed: true, expectedVersion: 4,
        })
    })

    it('изменяет только общий допуск документов и баз, не меняя чат', async () => {
        vi.mocked(saveRetention).mockResolvedValue({...status, knowledgeAllowed: false, version: 5})
        mount()
        fireEvent.click(await screen.findByRole('button', {
            name: 'Приостановить приём запросов на удаление документов и баз',
        }))
        confirmAction()
        expect(await screen.findByText('Изменения сохранены.', {exact: false})).toBeInTheDocument()
        expect(saveRetention).toHaveBeenCalledExactlyOnceWith({
            chatAllowed: true, knowledgeAllowed: false, expectedVersion: 4,
        })
    })

    it('отмена подтверждения оставляет политику без изменений', async () => {
        mount()
        fireEvent.click(await screen.findByRole('button', {
            name: 'Приостановить приём запросов на удаление чатов',
        }))
        fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {name: 'Отмена'}))
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
        expect(saveRetention).not.toHaveBeenCalled()
    })

    it('блокирует повторное включение неподготовленных чатов', async () => {
        vi.mocked(loadRetention).mockResolvedValue({...status, chatAllowed: false, chat: notPrepared})
        mount()
        expect(await screen.findByRole('button', {
            name: 'Возобновить приём запросов на удаление чатов',
        })).toBeDisabled()
        expect(saveRetention).not.toHaveBeenCalled()
    })

    it('не включает базы при отсутствии подготовки обоих типов данных', async () => {
        vi.mocked(loadRetention).mockResolvedValue({...status, knowledgeAllowed: false,
            document: notPrepared, base: notPrepared})
        mount()
        expect(await screen.findByRole('button', {
            name: 'Возобновить приём запросов на удаление документов и баз',
        })).toBeDisabled()
        expect(saveRetention).not.toHaveBeenCalled()
    })

    it.each([
        {label: 'документов', document: notPrepared, base: prepared},
        {label: 'баз знаний', document: prepared, base: notPrepared},
    ])('не предлагает включение при неготовности $label', async ({document, base}) => {
        vi.mocked(loadRetention).mockResolvedValue({...status, knowledgeAllowed: false, document, base})
        mount()
        expect(await screen.findByRole('button', {
            name: 'Возобновить приём запросов на удаление документов и баз',
        })).toBeDisabled()
        expect(screen.getByText('Сначала завершите подготовку системы')).toBeInTheDocument()
        expect(saveRetention).not.toHaveBeenCalled()
    })

    it('не отключает ранее принятые запросы при приостановленной политике', async () => {
        vi.mocked(loadRetention).mockResolvedValue({...status, chatAllowed: false, knowledgeAllowed: false})
        mount()
        expect(await screen.findByText(/0 из 2 направлений разрешены политикой/)).toBeInTheDocument()
        expect(screen.queryByText('Новые операции удаления могут быть отклонены')).not.toBeInTheDocument()
        expect(screen.getByText(/Уже принятые операции не отменяются/)).toBeInTheDocument()
    })

    it('показывает недоступность удаления организаций без дублирования и отдельные сроки пользователей', async () => {
        mount()
        expect(await screen.findByText(/Необратимое удаление ещё не реализовано/)).toBeInTheDocument()
        expect(screen.getByText('7 дн.')).toBeInTheDocument()
        expect(screen.getByRole('link', {name: 'Открыть пользователей'}))
            .toHaveAttribute('href', '/admin/users')
        expect(screen.getByRole('link', {name: 'Открыть организации'}))
            .toHaveAttribute('href', '/admin/organizations')
    })

    it('открывает историю с реально поддерживаемым серверным фильтром события', async () => {
        mount()
        const history = await screen.findByRole('link', {name: /Открыть историю изменений/})
        expect(history).toHaveAttribute('href', '/admin/audit?eventType=RETENTION_POLICY_CHANGED')
        expect(screen.getByText('Кто и когда менял правила приёма запросов на удаление.'))
            .toBeInTheDocument()
    })

    it('скрывает операторские подробности за раскрывающимся разделом', async () => {
        mount()
        const details = (await screen.findByText('Что нужно подготовить для выполнения удаления')).closest('details')
        expect(details).not.toHaveAttribute('open')
        fireEvent.click(within(details!).getByText('Что нужно подготовить для выполнения удаления'))
        expect(details).toHaveAttribute('open')
        expect(within(details!).getByText(/журнал удаления и защищённое хранилище/)).toBeInTheDocument()
    })

    it('при неизвестном результате записи не оставляет старую политику как актуальную', async () => {
        vi.mocked(saveRetention).mockRejectedValue(new Error('offline'))
        mount()
        fireEvent.click(await screen.findByRole('button', {
            name: 'Приостановить приём запросов на удаление чатов',
        }))
        confirmAction()
        expect(await screen.findByText('Политика недоступна')).toBeInTheDocument()
        expect(screen.queryByRole('button', {name: /Возобновить приём запросов/})).not.toBeInTheDocument()
    })
})


describe('Точность персонального доступа и безопасная навигация', () => {
    beforeEach(() => {
        vi.resetAllMocks()
        vi.mocked(loadRetention).mockResolvedValue(status)
    })

    it('показывает только две сводные карточки и не обещает немедленное стирание', async () => {
        mount()
        expect(await screen.findByText('Политика платформы')).toBeInTheDocument()
        expect(screen.getByText('Техническая подготовка')).toBeInTheDocument()
        expect(screen.queryByText('Ранее созданные запросы')).not.toBeInTheDocument()
        expect(screen.getByText(/Физическое удаление подтверждается отдельно/)).toBeInTheDocument()
    })

    it('показывает действующие роли, но не создаёт фиктивные разрешения для отдельных людей', async () => {
        mount()
        expect(await screen.findByRole('heading', {name: 'Кто может запросить удаление'})).toBeInTheDocument()
        expect(screen.getByText(/Эта страница не выдаёт индивидуальных разрешений/)).toBeInTheDocument()
        expect(screen.getByText(/не являются автоматическим разрешением на стирание/)).toBeInTheDocument()
        expect(screen.getByRole('link', {name: 'Открыть чаты'})).toHaveAttribute('href', '/chat')
        expect(screen.getAllByRole('link', {name: 'Открыть базы знаний'})).toHaveLength(2)
        expect(screen.getByRole('link', {name: 'Открыть организации'}))
            .toHaveAttribute('href', '/admin/organizations')
    })
})
