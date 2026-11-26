// frontend/src/shared/ui/WorkspaceReturn.test.tsx
import { describe,expect,it } from 'vitest'
import { render,screen,fireEvent } from '@testing-library/react'
import { MemoryRouter,Routes,Route } from 'react-router-dom'
import { WorkspaceLink,WorkspaceReturn } from './WorkspaceReturn'
import { safeReturn } from './workspaceReturnPolicy'
describe('Contextual return',()=>{
    it('rejects external and unapproved return URLs',()=>{for(const from of ['//example.org','https://example.org','/admin/users','/admin/retention\\evil','/admin/retention/../users'])expect(safeReturn({from})).toBeNull()})
    it('restores exact queue search parameters',()=>{render(<MemoryRouter initialEntries={['/admin/erasure/requests?state=PENDING&page=2']}><Routes><Route path="/admin/erasure/requests" element={<WorkspaceLink to="/admin/erasure/requests/123">Карточка</WorkspaceLink>}/><Route path="/admin/erasure/requests/123" element={<WorkspaceReturn/>}/></Routes></MemoryRouter>);fireEvent.click(screen.getByText('Карточка'));expect(screen.getByRole('link',{name:/К списку согласований/})).toHaveAttribute('href','/admin/erasure/requests?state=PENDING&page=2')})
    it('has a deterministic fallback',()=>{render(<MemoryRouter><WorkspaceReturn fallback="/admin/retention"/></MemoryRouter>);expect(screen.getByRole('link',{name:/К политике хранения/})).toHaveAttribute('href','/admin/retention')})
})
