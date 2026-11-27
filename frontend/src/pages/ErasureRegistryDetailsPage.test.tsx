// frontend/src/pages/ErasureRegistryDetailsPage.test.tsx
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import ErasureRegistryDetailsPage from './ErasureRegistryDetailsPage'
import { loadRegistryDetail } from '../features/erasure/api/workspaceApi'
vi.mock('../features/erasure/api/workspaceApi',()=>({loadRegistryDetail:vi.fn()}))
const id='4cc9a902-47a2-46f7-9594-49261e6dd90e'
const capabilities={platform:true,tenantRegistry:false,personalRequests:false,reviewQueue:false,requestsEnabled:false,approvalsEnabled:false}
function mount() {
    return render(<MemoryRouter initialEntries={[{pathname:`/admin/erasure/registry/${id}`,state:{from:'/admin/erasure/registry?state=HELD&page=2',scroll:120}}]}>
        <Routes><Route element={<Outlet context={{capabilities,loading:false,failed:false}}/>}>
            <Route path="/admin/erasure/registry/:requestId" element={<ErasureRegistryDetailsPage/>}/>
        </Route></Routes></MemoryRouter>)
}
describe('Read-only case card',()=>{
    beforeEach(()=>{vi.resetAllMocks();capabilities.platform=true;capabilities.tenantRegistry=false})
    it('shows independent states without approval or delete actions, and returns to original filters',async()=>{
        vi.mocked(loadRegistryDetail).mockResolvedValue({requestId:id,organization:'Tenant',resourceType:'CHAT',intakeState:'SUBMITTED',reviewState:'APPROVED_REVIEW_ONLY',reviewVisible:true,holdActive:true,
            createdAt:'2026-11-25T00:00:00Z',updatedAt:'2026-11-25T00:00:00Z',executionState:'NOT_AVAILABLE',completionProven:false})
        mount()
        expect(await screen.findByText('Предварительно согласовано')).toBeInTheDocument()
        expect(screen.getByText('Не подтверждён')).toBeInTheDocument()
        expect(screen.queryByRole('button',{name:/согласовать|удалить/i})).not.toBeInTheDocument()
        expect(screen.getByRole('link',{name:/К списку заявок/})).toHaveAttribute('href','/admin/erasure/registry?state=HELD&page=2')
    })
    it('explains a pending case while approvals are disabled without enabling execution',async()=>{
        vi.mocked(loadRegistryDetail).mockResolvedValue({requestId:id,organization:'Tenant',resourceType:'CHAT',intakeState:'SUBMITTED',reviewState:'PENDING',reviewVisible:true,holdActive:false,
            createdAt:'2026-11-25T00:00:00Z',updatedAt:'2026-11-25T00:00:00Z',executionState:'NOT_AVAILABLE',completionProven:false})
        mount()
        expect(await screen.findByText(/Рассмотрение заявок отключено/)).toBeInTheDocument()
        expect(screen.getByRole('list',{name:'Жизненный цикл заявки'})).toBeInTheDocument()
        expect(screen.getByRole('link',{name:'История этой заявки'})).toHaveAttribute('href',`/admin/erasure/history?query=${id}&page=0`)
        expect(screen.queryByRole('button',{name:/согласовать|удалить/i})).not.toBeInTheDocument()
    })
    it('does not fetch an unauthorized card',()=>{
        capabilities.platform=false
        mount()
        expect(screen.getByText('Карточка недоступна.')).toBeInTheDocument()
        expect(loadRegistryDetail).not.toHaveBeenCalled()
    })
    it('allows copying a tenant-visible UUID but does not expose platform history', async () => {
        capabilities.platform=false
        capabilities.tenantRegistry=true
        vi.mocked(loadRegistryDetail).mockResolvedValue({requestId:id,organization:'Tenant',resourceType:'CHAT',intakeState:'SUBMITTED',reviewState:null,reviewVisible:false,holdActive:null,
            createdAt:'2026-11-25T00:00:00Z',updatedAt:'2026-11-25T00:00:00Z',executionState:'NOT_AVAILABLE',completionProven:false})
        mount()
        expect(await screen.findByRole('button',{name:`Скопировать номер заявки ${id}`})).toBeInTheDocument()
        expect(screen.queryByRole('link',{name:'История этой заявки'})).not.toBeInTheDocument()
    })

})
