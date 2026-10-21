// ============================================================
// frontend/src/components/admin/audit/AuditFilters.production.test.tsx
// ============================================================
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ComponentProps } from 'react'
import AuditFilters from './AuditFilters'
import type { AuditDraftFilter } from './types'

const ORGANIZATION_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const ACTOR_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

function draft(overrides: Partial<AuditDraftFilter> = {}): AuditDraftFilter {
    return {
        eventType: '', actorUserId: '', actorEmail: '',
        dateFrom: '2026-10-01', dateTo: '2026-10-19',
        targetOrganizationId: '', ...overrides,
    }
}

function createProps(overrides: Partial<ComponentProps<typeof AuditFilters>> = {}) {
    return {
        draftFilter: draft(),
        eventTypes: ['USER_CREATED', 'USER_LOGIN_FAILED', 'KNOWLEDGE_DOCUMENT_DOWNLOADED'],
        organizations: [], actors: [], superAdmin: false,
        loading: false, directoriesLoading: false,
        eventTypesError: '', organizationsError: '', actorsError: '', filterError: '',
        filtersDirty: true,
        onFilterChange: vi.fn(),
        onActorSearch: vi.fn().mockResolvedValue(undefined),
        onOrganizationSearch: vi.fn().mockResolvedValue(undefined),
        onDatePreset: vi.fn(), onApply: vi.fn(), onReset: vi.fn(),
        ...overrides,
    } satisfies ComponentProps<typeof AuditFilters>
}

describe('AuditFilters — production regression', () => {
    beforeEach(() => { vi.useRealTimers() })
    afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })

    it('отделяет локальный поиск типов от отправки фильтров и сохраняет выбор события', () => {
        const props = createProps({ draftFilter: draft({ eventType: 'USER_LOGIN_FAILED' }) })
        render(<AuditFilters {...props} />)
        fireEvent.change(screen.getByLabelText('Поиск типа события'), {
            target: { value: 'скачан документ' },
        })
        const select = screen.getByLabelText('Тип события') as HTMLSelectElement
        expect(select.querySelector('option[value="KNOWLEDGE_DOCUMENT_DOWNLOADED"]')).not.toBeNull()
        expect(select.querySelector('option[value="USER_CREATED"]')).toBeNull()
        // Выбранный серверный тип остаётся доступным даже при поиске другой группы.
        expect(select.querySelector('option[value="USER_LOGIN_FAILED"]')).not.toBeNull()
        expect(props.onApply).not.toHaveBeenCalled()
    })

    it('передаёт изменение типа события через функциональное обновление черновика', () => {
        const props = createProps()
        render(<AuditFilters {...props} />)
        fireEvent.change(screen.getByLabelText('Тип события'), { target: { value: 'USER_CREATED' } })
        expect(props.onFilterChange).toHaveBeenCalledOnce()
        const update = vi.mocked(props.onFilterChange).mock.calls[0]?.[0]
        expect(typeof update).toBe('function')
        if (typeof update !== 'function') throw new Error('Ожидался updater')
        expect(update(draft({ actorEmail: 'user@company.test' }))).toMatchObject({
            eventType: 'USER_CREATED', actorEmail: 'user@company.test',
        })
    })

    it('не меняет применённые фильтры при вводе поисковой строки справочника', async () => {
        vi.useFakeTimers()
        const props = createProps({ superAdmin: true })
        render(<AuditFilters {...props} />)
        fireEvent.change(screen.getByPlaceholderText('Название или UUID'), {
            target: { value: 'Клиент' },
        })
        fireEvent.change(screen.getByPlaceholderText('Email, имя или UUID'), {
            target: { value: 'admin@' },
        })
        await act(async () => { vi.advanceTimersByTime(300) })
        expect(props.onFilterChange).not.toHaveBeenCalled()
        expect(props.onApply).not.toHaveBeenCalled()
        expect(props.onOrganizationSearch).toHaveBeenCalledWith('Клиент')
        expect(props.onActorSearch).toHaveBeenCalledWith('admin@')
    })

    it('использует актуальный callback, когда поиск ещё находится в debounce', async () => {
        vi.useFakeTimers()
        const initial = createProps({ superAdmin: true })
        const { rerender } = render(<AuditFilters {...initial} />)
        fireEvent.change(screen.getByPlaceholderText('Email, имя или UUID'), {
            target: { value: 'новый' },
        })
        const newCallback = vi.fn().mockResolvedValue(undefined)
        rerender(<AuditFilters {...initial} onActorSearch={newCallback} />)
        await act(async () => { vi.advanceTimersByTime(300) })
        expect(initial.onActorSearch).not.toHaveBeenCalled()
        expect(newCallback).toHaveBeenCalledTimes(1)
        expect(newCallback).toHaveBeenCalledWith('новый')
    })

    it('не открывает фильтр организаций администратору без SUPER_ADMIN', () => {
        render(<AuditFilters {...createProps({ superAdmin: false })} />)
        expect(screen.queryByText('Целевая организация')).not.toBeInTheDocument()
        expect(screen.getByLabelText('Инициатор')).toBeInTheDocument()
    })

    it('не теряет выбранные UUID при обновлении справочника', () => {
        render(<AuditFilters {...createProps({
            superAdmin: true,
            draftFilter: draft({ targetOrganizationId: ORGANIZATION_ID, actorUserId: ACTOR_ID }),
        })} />)
        expect(screen.getByLabelText('Целевая организация')).toHaveValue(ORGANIZATION_ID)
        expect(screen.getByLabelText('Инициатор')).toHaveValue(ACTOR_ID)
        expect(screen.getByText(/Название недоступно/)).toBeInTheDocument()
        expect(screen.getByText(/Инициатор без имени/)).toBeInTheDocument()
    })

    it('удаляет дубли справочников и показывает человекочитаемые подписи', () => {
        render(<AuditFilters {...createProps({
            superAdmin: true,
            organizations: [
                { targetOrganizationId: ORGANIZATION_ID, targetOrganizationName: 'Завод' },
                { targetOrganizationId: ORGANIZATION_ID, targetOrganizationName: 'Завод' },
            ],
            actors: [
                { actorUserId: ACTOR_ID, actorOrganizationId: null, actorEmail: 'admin@test.ru', actorDisplayName: 'Тест' },
                { actorUserId: ACTOR_ID, actorOrganizationId: null, actorEmail: 'admin@test.ru', actorDisplayName: 'Тест' },
            ],
        })} />)
        const org = screen.getByLabelText('Целевая организация') as HTMLSelectElement
        const actor = screen.getByLabelText('Инициатор') as HTMLSelectElement
        expect(org.querySelectorAll(`option[value="${ORGANIZATION_ID}"]`)).toHaveLength(1)
        expect(actor.querySelectorAll(`option[value="${ACTOR_ID}"]`)).toHaveLength(1)
        expect(screen.getByText(/admin@test.ru — Тест/)).toBeInTheDocument()
    })

    it('откладывает и объединяет последовательные запросы поиска на 300 мс', async () => {
        vi.useFakeTimers()
        const props = createProps({ superAdmin: true })
        render(<AuditFilters {...props} />)
        const input = screen.getByPlaceholderText('Название или UUID')
        fireEvent.change(input, { target: { value: 'Мо' } })
        fireEvent.change(input, { target: { value: 'Москва' } })
        await act(async () => { vi.advanceTimersByTime(299) })
        expect(props.onOrganizationSearch).not.toHaveBeenCalled()
        await act(async () => { vi.advanceTimersByTime(1) })
        expect(props.onOrganizationSearch).toHaveBeenCalledTimes(1)
        expect(props.onOrganizationSearch).toHaveBeenCalledWith('Москва')
        expect(props.onApply).not.toHaveBeenCalled()
    })

    it('не привязывает поиск инициатора к целевой организации', async () => {
        vi.useFakeTimers()
        const props = createProps({ superAdmin: true, draftFilter: draft({ targetOrganizationId: ORGANIZATION_ID }) })
        render(<AuditFilters {...props} />)
        fireEvent.change(screen.getByPlaceholderText('Email, имя или UUID'), {
            target: { value: 'test@company.ru' },
        })
        await act(async () => { vi.advanceTimersByTime(300) })
        expect(props.onActorSearch).toHaveBeenCalledTimes(1)
        expect(props.onActorSearch).toHaveBeenCalledWith('test@company.ru')
    })

    it('отменяет ожидающие таймеры после размонтирования', async () => {
        vi.useFakeTimers()
        const props = createProps({ superAdmin: true })
        const mounted = render(<AuditFilters {...props} />)
        fireEvent.change(screen.getByPlaceholderText('Название или UUID'), { target: { value: 'Компания' } })
        fireEvent.change(screen.getByPlaceholderText('Email, имя или UUID'), { target: { value: 'admin' } })
        mounted.unmount()
        await act(async () => { vi.advanceTimersByTime(1_000) })
        expect(props.onActorSearch).not.toHaveBeenCalled()
        expect(props.onOrganizationSearch).not.toHaveBeenCalled()
    })

    it('устанавливает пресеты и снимает выделение при ручной коррекции периода', () => {
        const props = createProps()
        render(<AuditFilters {...props} />)
        for (const [name, preset] of [
            ['Сегодня', 'today'], ['Вчера', 'yesterday'], ['7 дней', 'last7Days'],
            ['30 дней', 'last30Days'], ['365 дней', 'last365Days'],
        ] as const) {
            const button = screen.getByRole('button', { name })
            fireEvent.click(button)
            expect(button).toHaveAttribute('aria-pressed', 'true')
            expect(props.onDatePreset).toHaveBeenLastCalledWith(preset)
        }
        fireEvent.change(screen.getByLabelText('Дата по'), { target: { value: '2026-10-18' } })
        expect(screen.getByRole('button', { name: '365 дней' })).toHaveAttribute('aria-pressed', 'false')
    })

    it('показывает несохранённые фильтры, применяет и сбрасывает их независимо', () => {
        const props = createProps()
        render(<AuditFilters {...props} />)
        expect(screen.getByText(/результаты еще не обновлены/)).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: 'Показать события' }))
        expect(props.onApply).toHaveBeenCalledOnce()
        fireEvent.click(screen.getByRole('button', { name: 'Последние 30 дней' }))
        expect(props.onReset).toHaveBeenCalledOnce()
        expect(props.onDatePreset).not.toHaveBeenCalled()
    })

    it('отображает ошибки справочников и ошибки фильтра, не скрывая ввод', () => {
        render(<AuditFilters {...createProps({
            superAdmin: true, eventTypesError: 'Ошибка типов',
            actorsError: 'Ошибка инициаторов', organizationsError: 'Ошибка организаций',
            filterError: 'Некорректный период', directoriesLoading: true,
        })} />)
        expect(screen.getByText('Ошибка типов')).toBeInTheDocument()
        expect(screen.getByText('Ошибка инициаторов')).toBeInTheDocument()
        expect(screen.getByText('Ошибка организаций')).toBeInTheDocument()
        expect(screen.getByRole('alert')).toHaveTextContent('Некорректный период')
        expect(screen.getByText(/Загружаются справочники/)).toBeInTheDocument()
    })

    it('запрещает применение во время загрузки или без изменений', () => {
        const { rerender } = render(<AuditFilters {...createProps({ filtersDirty: false })} />)
        expect(screen.getByRole('button', { name: 'Показать события' })).toBeDisabled()
        rerender(<AuditFilters {...createProps({ loading: true })} />)
        expect(screen.getByRole('button', { name: 'Показать события' })).toBeDisabled()
        expect(screen.getByLabelText('Поиск типа события')).toBeDisabled()
        expect(screen.getByRole('button', { name: 'Последние 30 дней' })).toBeDisabled()
    })
})
