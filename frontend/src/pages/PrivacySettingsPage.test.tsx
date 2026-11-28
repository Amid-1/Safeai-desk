// frontend/src/pages/PrivacySettingsPage.test.tsx
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import PrivacySettingsPage from './PrivacySettingsPage'
const auth=vi.hoisted(()=>({currentUser:{enabled:true,roles:['SUPER_ADMIN']}}))
const caps=vi.hoisted(()=>({personalRequests:false}))
vi.mock('../features/auth/state/useAuth',()=>({useAuth:()=>auth}))
vi.mock('../features/erasure/api/useWorkspaceCapabilities',()=>({useWorkspaceCapabilities:()=>({capabilities:caps,loading:false,failed:false,retry:vi.fn()})}))
describe('Privacy capabilities',()=>{
    it('does not advertise personal request to system admin',()=>{auth.currentUser.roles=['SUPER_ADMIN'];caps.personalRequests=true;render(<MemoryRouter><PrivacySettingsPage/></MemoryRouter>);expect(screen.getByText(/Для системного супер-администратора/)).toBeInTheDocument();expect(screen.queryByRole('link',{name:'Открыть обращения'})).not.toBeInTheDocument()})
    it('offers personal requests only when confirmed',()=>{auth.currentUser.roles=['USER'];caps.personalRequests=true;render(<MemoryRouter><PrivacySettingsPage/></MemoryRouter>);expect(screen.getByRole('link',{name:'Открыть обращения'})).toHaveAttribute('href','/my/erasure-requests')})
    it('shows disabled state rather than fake controls',()=>{auth.currentUser.roles=['USER'];caps.personalRequests=false;render(<MemoryRouter><PrivacySettingsPage/></MemoryRouter>);expect(screen.getByText('Подача обращений сейчас недоступна.')).toBeInTheDocument();expect(screen.queryByRole('link',{name:'Открыть обращения'})).not.toBeInTheDocument()})
})
