// frontend/src/components/chat/ChatTitleEditor.test.tsx
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
})
