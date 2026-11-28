// frontend/src/shared/ui/ErasureRequestLink.test.tsx
import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import ErasureRequestLink from './ErasureRequestLink'
import { ErasureEntryContext } from './erasureEntryContext'
import { parseContextTarget } from './erasureContextTarget'
import { safeReturn } from './workspaceReturnPolicy'

const id = 'a8517423-018f-412b-95a2-9c79748bf19a'
const baseId = '83155da1-4fae-4629-abde-9e09aa500b13'
function Destination() {
    const location = useLocation()
    const target = parseContextTarget(location.state?.erasureTarget)
    const back = safeReturn(location.state)
    return <div>{target?.id} {back?.to}</div>
}
describe('Contextual erasure entry', () => {
    it('fails closed before capabilities are confirmed', () => {
        render(<ErasureRequestLink target={{ type: 'CHAT', id }} />)
        expect(screen.queryByRole('link')).not.toBeInTheDocument()
    })
    it('passes only a typed target and allowlisted origin', () => {
        render(<ErasureEntryContext.Provider value={true}><MemoryRouter initialEntries={[`/knowledge/${baseId}`]}>
            <Routes><Route path="/knowledge/:id" element={<ErasureRequestLink target={{ type: 'KNOWLEDGE_DOCUMENT', id, baseId }} />} />
                <Route path="/my/erasure-requests" element={<Destination />} /></Routes>
        </MemoryRouter></ErasureEntryContext.Provider>)
        fireEvent.click(screen.getByRole('link', { name: 'Запросить удаление' }))
        expect(screen.getByText(`${id} /knowledge/${baseId}`)).toBeInTheDocument()
    })
    it('rejects malformed document or return path, and does not accept destructive operation data', () => {
        expect(parseContextTarget({ type: 'KNOWLEDGE_DOCUMENT', id })).toBeNull()
        expect(parseContextTarget({ type: 'USER_ACCOUNT', id })).toBeNull()
        expect(parseContextTarget({ type: 'CHAT', id, operation: 'DELETE' })).toEqual({ type: 'CHAT', id })
        expect(safeReturn({ from: '/knowledge/../../admin' })).toBeNull()
        expect(safeReturn({ from: `//knowledge/${baseId}` })).toBeNull()
        expect(safeReturn({ from: '/chat' })?.label).toBe('К чатам')
    })
})
