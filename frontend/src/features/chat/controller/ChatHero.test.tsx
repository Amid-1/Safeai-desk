// frontend/src/features/chat/controller/ChatHero.test.tsx
// ============================================================
// frontend/src/features/chat/controller/ChatHero.test.tsx
// ============================================================
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import ChatHero from './ChatHero'
import { CHAT_TRUST_ITEMS } from './chatTrustInfo'

describe('ChatHero', () => {
    it('preserves the three trust explanations and their selected payloads', () => {
        const onSelectTrustItem = vi.fn()

        render(<ChatHero onSelectTrustItem={onSelectTrustItem} />)

        expect(screen.getByRole('heading', { name: 'Корпоративный AI' }))
            .toBeInTheDocument()

        for (const item of CHAT_TRUST_ITEMS) {
            const button = screen.getByRole('button', {
                name: `${item.label}: открыть объяснение`,
            })

            fireEvent.click(button)
            expect(onSelectTrustItem).toHaveBeenLastCalledWith(item)
        }

        expect(onSelectTrustItem).toHaveBeenCalledTimes(CHAT_TRUST_ITEMS.length)
    })
})
