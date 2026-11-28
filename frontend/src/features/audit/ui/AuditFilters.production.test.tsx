// frontend/src/features/audit/ui/AuditFilters.production.test.tsx
// ============================================================
// frontend/src/features/audit/ui/AuditFilters.production.test.tsx
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
    beforeEach(() => {
        vi.useRealTimers()
        const original = HTMLElement.prototype.getBoundingClientRect
        vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
            if (this.classList.contains('usage-filter-combobox__control')) {
                return DOMRect.fromRect({ x: 24, y: 120, width: 260, height: 34 })
            }
            return original.call(this)
        })
    })
    afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })

    it('отделяет локальный поиск типов от отправки фильтров и сохраняет выбор события', () => {
        const props = createProps({ draftFilter: draft({ eventType: 'USER_LOGIN_FAILED' }) })
        render(<AuditFilters {...props} />)
        fireEvent.change(screen.getByLabelText('Событие'), {
            target: { value: 'скачан документ' },
        })
        expect(screen.getByRole('option', { name: /Скачан документ/i })).toBeInTheDocument()
        expect(screen.queryByRole('option', { name: /Создан пользователь/i })).not.toBeInTheDocument()
        // Выбранный серверный тип остаётся доступным даже при поиске другой группы.
        expect(screen.getByRole('option', { name: /Неудачная попытка входа/i })).toHaveAttribute('aria-selected', 'true')
        expect(props.onApply).not.toHaveBeenCalled()
    })

    it('передаёт изменение типа события через функциональное обновление черновика', () => {
        const props = createProps()
        render(<AuditFilters {...props} />)
        fireEvent.focus(screen.getByLabelText('Событие'))
        fireEvent.click(screen.getByRole('option', { name: /Создан пользователь/i }))
        expect(props.onFilterChange).toHaveBeenCalledOnce()
        const update = vi.mocked(props.onFilterChange).mock.calls[0]?.[0]
        expect(typeof update).toBe('function')
        if (typeof update !== 'function') throw new Error('Ожидался updater')
        expect(update(draft({ actorEmail: 'user@company.test' }))).toMatchObject({
            eventType: 'USER_CREATED', actorEmail: 'user@company.test',
        })
    })

    it('выносит оба поисковых списка из формы и привязывает их к полям при прокрутке и изменении окна', () => {
        const props = createProps()
        const { container } = render(<AuditFilters {...props} />)
        const viewportHeight = vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(800)
        for (const name of ['Событие', 'Пользователь']) {
            const field = screen.getByRole('combobox', { name })
            const anchor = field.parentElement!
            let top = 120
            vi.spyOn(anchor, 'getBoundingClientRect').mockImplementation(() => DOMRect.fromRect({
                x: 24, y: top, width: 260, height: 34,
            }))
            fireEvent.focus(field)
            const list = screen.getByRole('listbox', { name })
            const popup = list.parentElement!
            expect(container).not.toContainElement(popup)
            expect(popup.parentElement).toBe(document.body)
            expect(popup).toHaveStyle({ left: '24px', top: '159px', width: '260px', maxHeight: '230px' })
            top = 200
            fireEvent.scroll(window)
            expect(popup).toHaveStyle({ top: '239px' })
            viewportHeight.mockReturnValue(450)
            top = 380
            fireEvent.resize(window)
            expect(popup).toHaveStyle({ top: 'auto', bottom: '75px', maxHeight: '230px' })
            fireEvent.keyDown(field, { key: 'Escape' })
            expect(screen.queryByRole('listbox', { name })).not.toBeInTheDocument()
            expect(field).toHaveAttribute('aria-expanded', 'false')
            viewportHeight.mockReturnValue(800)
        }
        expect(props.onFilterChange).not.toHaveBeenCalled()
        expect(props.onApply).not.toHaveBeenCalled()
    })

    it('не меняет применённые фильтры при вводе поисковой строки справочника', async () => {
        vi.useFakeTimers()
        const props = createProps({ superAdmin: true })
        render(<AuditFilters {...props} />)
        fireEvent.change(screen.getByRole('combobox', {name: 'Организация'}), {
            target: { value: 'Клиент' },
        })
        fireEvent.change(screen.getByPlaceholderText('Имя или email пользователя'), {
            target: { value: 'admin@' },
        })
        await act(async () => { vi.advanceTimersByTime(300) })
        expect(props.onFilterChange).not.toHaveBeenCalled()
        expect(props.onApply).not.toHaveBeenCalled()
        expect(props.onOrganizationSearch).toHaveBeenCalledWith('Клиент')
        expect(props.onActorSearch).toHaveBeenCalledWith('admin@')
    })

    it('список организаций — портал: выбор, прокрутка и Escape не перестраивают форму', () => {
        const props = createProps({ superAdmin: true, organizations: [
            { targetOrganizationId: ORGANIZATION_ID, targetOrganizationName: 'Завод' },
        ] })
        const { container } = render(<AuditFilters {...props} />)
        const input = screen.getByRole('combobox', { name: 'Организация' })
        const anchor = input.parentElement!
        let top = 120
        vi.spyOn(anchor, 'getBoundingClientRect').mockImplementation(() =>
            DOMRect.fromRect({ x: 24, y: top, width: 260, height: 34 }))
        fireEvent.focus(input)
        const menu = screen.getByRole('listbox', { name: 'Организация: варианты' })
        expect(menu.parentElement).toBe(document.body)
        expect(container).not.toContainElement(menu)
        expect(menu).toHaveStyle({ left: '24px', top: '159px', width: '260px' })
        top = 200
        fireEvent.scroll(window)
        expect(menu).toHaveStyle({ top: '239px' })
        fireEvent.pointerDown(screen.getByRole('option', { name: 'Завод' }))
        fireEvent.click(screen.getByRole('option', { name: 'Завод' }))
        expect(props.onFilterChange).toHaveBeenCalledOnce()
        expect(props.onApply).not.toHaveBeenCalled()
        fireEvent.click(input)
        fireEvent.keyDown(input, { key: 'Escape' })
        expect(screen.queryByRole('listbox', { name: 'Организация: варианты' })).not.toBeInTheDocument()
    })

    it('использует актуальный callback, когда поиск ещё находится в debounce', async () => {
        vi.useFakeTimers()
        const initial = createProps({ superAdmin: true })
        const { rerender } = render(<AuditFilters {...initial} />)
        fireEvent.change(screen.getByPlaceholderText('Имя или email пользователя'), {
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
        expect(screen.queryByText('Организация')).not.toBeInTheDocument()
        expect(screen.getByLabelText('Пользователь')).toBeInTheDocument()
    })

    it('не теряет выбранные UUID при обновлении справочника', () => {
        render(<AuditFilters {...createProps({
            superAdmin: true,
            draftFilter: draft({ targetOrganizationId: ORGANIZATION_ID, actorUserId: ACTOR_ID }),
        })} />)
        expect(screen.getByLabelText('Организация')).toHaveValue('Название недоступно')
        fireEvent.focus(screen.getByLabelText('Организация'))
        expect(screen.getByRole('option', {name:'Название недоступно'})).toHaveAttribute('aria-selected','true')
        expect(screen.getByLabelText('Пользователь')).toHaveValue('Пользователь без имени (' + ACTOR_ID + ')')
        expect(screen.getByRole('option', {name:'Название недоступно'})).toBeInTheDocument()
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
        const org = screen.getByLabelText('Организация')
        const actor = screen.getByLabelText('Пользователь')
        fireEvent.focus(org)
        expect(screen.getAllByRole('option', {name: 'Завод'})).toHaveLength(1)
        fireEvent.focus(actor)
        expect(screen.getAllByRole('option', { name: /admin@test.ru — Тест/ })).toHaveLength(1)
    })

    it('откладывает и объединяет последовательные запросы поиска на 300 мс', async () => {
        vi.useFakeTimers()
        const props = createProps({ superAdmin: true })
        render(<AuditFilters {...props} />)
        const input = screen.getByRole('combobox', {name: 'Организация'})
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
        fireEvent.change(screen.getByPlaceholderText('Имя или email пользователя'), {
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
        fireEvent.change(screen.getByRole('combobox', {name: 'Организация'}), { target: { value: 'Компания' } })
        fireEvent.change(screen.getByPlaceholderText('Имя или email пользователя'), { target: { value: 'admin' } })
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
            ['30 дней', 'last30Days'], ['90 дней', 'last90Days'], ['365 дней', 'last365Days'],
        ] as const) {
            const button = screen.getByRole('button', { name })
            fireEvent.click(button)
            expect(button).toHaveAttribute('aria-pressed', 'true')
            expect(props.onDatePreset).toHaveBeenLastCalledWith(preset)
        }
        fireEvent.change(screen.getByLabelText('Конец периода'), { target: { value: '2026-10-18' } })
        expect(screen.getByRole('button', { name: '365 дней' })).toHaveAttribute('aria-pressed', 'false')
    })

    it('показывает несохранённые фильтры, применяет и сбрасывает их независимо', () => {
        const props = createProps()
        render(<AuditFilters {...props} />)
        expect(screen.getByText(/результаты еще не обновлены/)).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: 'Показать события' }))
        expect(props.onApply).toHaveBeenCalledOnce()
        fireEvent.click(screen.getByRole('button', { name: 'Сбросить фильтры' }))
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
        expect(screen.getByLabelText('Событие')).toBeDisabled()
        expect(screen.getByRole('button', { name: 'Сбросить фильтры' })).toBeDisabled()
    })
})
