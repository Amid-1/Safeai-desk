// frontend/src/pages/ErasureReviewQueuePage.test.tsx
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import ErasureReviewQueuePage from './ErasureReviewQueuePage'
import { loadRegistry, loadRegistrySummary, type RegistryItem } from '../features/erasure/api/workspaceApi'
vi.mock('../features/erasure/api/workspaceApi',()=>({loadRegistry:vi.fn(),loadRegistrySummary:vi.fn()}))
const item:RegistryItem={requestId:'4cc9a902-47a2-46f7-9594-49261e6dd90e',organization:'Tenant',requester:'test@example.test',resourceType:'CHAT',state:'PENDING',intakeState:'SUBMITTED',reviewState:'PENDING',reviewVisible:true,holdActive:false,updatedAt:'2026-11-25T00:00:00Z'}
function mount() {
    return render(<MemoryRouter><Routes><Route element={<Outlet context={{capabilities:{platform:false,tenantRegistry:true,personalRequests:true,reviewQueue:true,requestsEnabled:true,approvalsEnabled:true},loading:false,failed:false}}/>}><Route path="/" element={<ErasureReviewQueuePage/>}/></Route></Routes></MemoryRouter>)
}
describe('Server reviewer queue',()=>{
    beforeEach(()=>vi.resetAllMocks())
    it('passes state to server and preserves filter in card link state',async()=>{
        vi.mocked(loadRegistry).mockResolvedValueOnce({items:[item],page:0,pageSize:25,hasNext:true}).mockResolvedValueOnce({items:[],page:0,pageSize:25,hasNext:false})
        mount()
        expect(await screen.findByRole('link',{name:'Открыть карточку'})).toBeInTheDocument()
        fireEvent.change(screen.getByRole('combobox',{name:'Состояние заявки'}),{target:{value:'REJECTED'}})
        expect(await screen.findByText('Заявок не найдено.')).toBeInTheDocument()
        expect(loadRegistry).toHaveBeenLastCalledWith(true,'REJECTED','',0,expect.any(AbortSignal))
        expect(loadRegistrySummary).not.toHaveBeenCalled()
    })
    it('does not display results on forbidden response',async()=>{
        vi.mocked(loadRegistry).mockRejectedValue(new Error('Forbidden'))
        mount()
        expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось загрузить заявки')
        expect(screen.queryByRole('link',{name:'Открыть карточку'})).not.toBeInTheDocument()
    })
})
