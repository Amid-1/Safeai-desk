// frontend/src/features/chat/ui/ChatTitleEditor.test.tsx
import {
    fireEvent,
    render,
    screen,
    waitFor,
} from '@testing-library/react'
import {
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import ChatTitleEditor from './ChatTitleEditor'

describe('ChatTitleEditor', () => {
    it('renames from the header and normalizes whitespace', async () => {
        const onSave = vi.fn().mockResolvedValue(undefined)

        render(
            <ChatTitleEditor
                title="Новый чат"
                onSave={onSave}
            />,
        )

        fireEvent.click(
            screen.getByRole('button', {
                name: /переименовать чат/i,
            }),
        )

        const input = screen.getByRole('textbox', {
            name: 'Название чата',
        })

        fireEvent.change(input, {
            target: {
                value: '  Архитектура   SafeAI  ',
            },
        })
        fireEvent.keyDown(input, {
            key: 'Enter',
        })

        await waitFor(() => {
            expect(onSave).toHaveBeenCalledWith(
                'Архитектура SafeAI',
            )
        })
    })

    it('does not save a blank title', async () => {
        const onSave = vi.fn().mockResolvedValue(undefined)

        render(
            <ChatTitleEditor
                title="Новый чат"
                onSave={onSave}
            />,
        )

        fireEvent.click(
            screen.getByRole('button', {
                name: /переименовать чат/i,
            }),
        )

        const input = screen.getByRole('textbox', {
            name: 'Название чата',
        })
        fireEvent.change(input, {
            target: { value: '   ' },
        })
        fireEvent.keyDown(input, {
            key: 'Enter',
        })

        expect(await screen.findByRole('alert'))
            .toHaveTextContent('Введите название чата.')
        expect(onSave).not.toHaveBeenCalled()
    })

    it('opens directly from history and closes only after a successful rename', async () => {
        const onSave = vi.fn().mockResolvedValue(undefined)
        const onSaved = vi.fn()
        const onCancelEditing = vi.fn()
        render(
            <ChatTitleEditor
                title="Предыдущее название"
                onSave={onSave}
                initiallyEditing
                onSaved={onSaved}
                onCancelEditing={onCancelEditing}
            />,
        )

        const input = screen.getByRole('textbox', { name: 'Название чата' })
        fireEvent.change(input, { target: { value: 'Новое название' } })
        fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
        await waitFor(() => {
            expect(onSave).toHaveBeenCalledWith('Новое название')
            expect(onSaved).toHaveBeenCalledTimes(1)
        })
        expect(onCancelEditing).not.toHaveBeenCalled()
    })

    it('does not modify a title when the history dialog is cancelled', () => {
        const onSave = vi.fn().mockResolvedValue(undefined)
        const onCancelEditing = vi.fn()
        render(
            <ChatTitleEditor
                title="Ручное название"
                onSave={onSave}
                initiallyEditing
                onCancelEditing={onCancelEditing}
            />,
        )
        fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))
        expect(onSave).not.toHaveBeenCalled()
        expect(onCancelEditing).toHaveBeenCalledTimes(1)
    })

})
