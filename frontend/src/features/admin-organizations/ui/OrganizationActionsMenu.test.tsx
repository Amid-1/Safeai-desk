// frontend/src/features/admin-organizations/ui/OrganizationActionsMenu.test.tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { OrganizationActionsMenu } from './OrganizationActionsMenu'

describe('OrganizationActionsMenu accessibility and protection', () => {
    function props() { return {organizationName: 'Контур', enabled: true, disabled: false, onRename: vi.fn(), onToggleStatus: vi.fn()} }
    const trigger = () => screen.getByRole('button', {name: 'Действия для организации Контур'})

    it('opens from the keyboard, restores focus with Escape and does not mutate', async () => {
        const p = props()
        render(<OrganizationActionsMenu {...p} />)
        trigger().focus()
        fireEvent.keyDown(trigger(), {key: 'ArrowDown'})
        expect(trigger()).toHaveAttribute('aria-expanded', 'true')
        await waitFor(() => expect(screen.getByRole('button', {name: 'Переименовать'})).toHaveFocus())
        fireEvent.keyDown(document, {key: 'Escape'})
        await waitFor(() => expect(trigger()).toHaveFocus())
        expect(screen.queryByRole('button', {name:'Переименовать'})).toBeNull()
        expect(p.onRename).not.toHaveBeenCalled()
        expect(p.onToggleStatus).not.toHaveBeenCalled()
    })

    it('closes on outside pointer or scroll without triggering an action', () => {
        const p = props()
        render(<OrganizationActionsMenu {...p} />)
        fireEvent.click(trigger())
        fireEvent.pointerDown(trigger())
        expect(trigger()).toHaveAttribute('aria-expanded','true')
        fireEvent.pointerDown(screen.getByRole('button', {name:'Переименовать'}))
        expect(trigger()).toHaveAttribute('aria-expanded','true')
        fireEvent.pointerDown(document.body)
        expect(trigger()).toHaveAttribute('aria-expanded','false')
        fireEvent.click(trigger())
        fireEvent.scroll(window)
        expect(trigger()).toHaveAttribute('aria-expanded','false')
        expect(p.onToggleStatus).not.toHaveBeenCalled()
    })

    it('positions upward at the viewport edge and closes while disabled', async () => {
        const p = props()
        const {rerender} = render(<OrganizationActionsMenu {...p} />)
        vi.spyOn(trigger(), 'getBoundingClientRect').mockReturnValue(DOMRect.fromRect({x:240,y:window.innerHeight-25,width:40,height:20}))
        fireEvent.click(trigger())
        const popup = screen.getByRole('button', {name:'Переименовать'}).parentElement!
        vi.spyOn(popup, 'getBoundingClientRect').mockReturnValue(DOMRect.fromRect({width:230,height:100}))
        fireEvent.resize(window)
        expect(popup).toHaveStyle({top: `${window.innerHeight-130}px`})
        rerender(<OrganizationActionsMenu {...p} disabled />)
        await waitFor(() => expect(trigger()).toHaveAttribute('aria-expanded','false'))
        expect(trigger()).toBeDisabled()
        expect(p.onRename).not.toHaveBeenCalled()
    })

    it('delegates rename only and leaves status unchanged', () => {
        const p = props()
        render(<OrganizationActionsMenu {...p} />)
        fireEvent.click(trigger())
        fireEvent.click(screen.getByRole('button', {name:'Переименовать'}))
        expect(p.onRename).toHaveBeenCalledOnce()
        expect(p.onToggleStatus).not.toHaveBeenCalled()
        expect(trigger()).toHaveAttribute('aria-expanded','false')
    })
})
