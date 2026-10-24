// frontend/src/features/models/ui/AdminModelsNavigation.test.tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AdminModelsNavigation, parseModelsSection } from './AdminModelsNavigation'

describe('разделы управления моделями', () => {
    it('не принимает произвольные query-параметры как разделы', () => {
        expect(parseModelsSection(null)).toBe('overview')
        expect(parseModelsSection('catalog')).toBe('catalog')
        expect(parseModelsSection('access')).toBe('access')
        expect(parseModelsSection('diagnostics')).toBe('diagnostics')
        expect(parseModelsSection('admin')).toBe('overview')
        expect(parseModelsSection('<script>')).toBe('overview')
    })

    it('помечает текущий раздел и не превращает обычную навигацию в разрешение действия', () => {
        const onSelect = vi.fn()
        render(<AdminModelsNavigation section="catalog" onSelect={onSelect} />)
        expect(screen.getAllByRole('tab')).toHaveLength(4)
        expect(screen.getByRole('tab', { name: 'Каталог' })).toHaveAttribute('aria-selected', 'true')
        expect(screen.getByRole('tab', { name: 'Обзор' })).toHaveAttribute('aria-selected', 'false')
        expect(screen.getByRole('tab', { name: 'Каталог' })).toHaveAttribute('tabindex', '0')
        fireEvent.click(screen.getByRole('tab', { name: 'Доступ' }))
        expect(onSelect).toHaveBeenCalledWith('access')
    })

    it('поддерживает стрелки, Home и End с клавиатуры', () => {
        const onSelect = vi.fn()
        render(<AdminModelsNavigation section="overview" onSelect={onSelect} />)
        fireEvent.keyDown(screen.getByRole('tab', { name: 'Обзор' }), { key: 'ArrowRight' })
        expect(onSelect).toHaveBeenLastCalledWith('catalog')
        fireEvent.keyDown(screen.getByRole('tab', { name: 'Каталог' }), { key: 'End' })
        expect(onSelect).toHaveBeenLastCalledWith('diagnostics')
        fireEvent.keyDown(screen.getByRole('tab', { name: 'Диагностика' }), { key: 'Home' })
        expect(onSelect).toHaveBeenLastCalledWith('overview')
    })
})
