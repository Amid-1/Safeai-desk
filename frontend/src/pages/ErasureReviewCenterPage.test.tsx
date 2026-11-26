// frontend/src/pages/ErasureReviewCenterPage.test.tsx
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import ErasureReviewCenterPage from './ErasureReviewCenterPage'
import { loadCapabilities, loadRegistry, loadRegistrySummary } from '../features/erasure/api/workspaceApi'
vi.mock('../features/erasure/api/workspaceApi',()=>({loadCapabilities:vi.fn(),loadRegistry:vi.fn(),loadRegistrySummary:vi.fn()}))
describe('Platform overview',()=>{
    beforeEach(()=>{vi.resetAllMocks();vi.mocked(loadCapabilities).mockResolvedValue({platform:true,tenantRegistry:false,personalRequests:false,reviewQueue:false,requestsEnabled:false,approvalsEnabled:false});vi.mocked(loadRegistry).mockResolvedValue({items:[],page:0,pageSize:25,hasNext:false})})
    it('shows real server counts and empty attention list',async()=>{
        vi.mocked(loadRegistrySummary).mockResolvedValue({scope:'PLATFORM',total:18,registered:17,withdrawn:1,drafts:null,reviewed:10,pending:6,needsInformation:2,held:1})
        render(<MemoryRouter><ErasureReviewCenterPage/></MemoryRouter>)
        expect(await screen.findByText('18')).toBeInTheDocument();expect(screen.getByText('6')).toBeInTheDocument()
        expect(await screen.findByText('Нет заявок, требующих внимания.')).toBeInTheDocument()
        expect(screen.getByRole('link',{name:/Все заявки/})).toHaveAttribute('href','/admin/erasure/registry?state=ATTENTION')
    })
    it('does not substitute zeros on failure',async()=>{
        vi.mocked(loadRegistrySummary).mockRejectedValue(new Error('Forbidden'))
        render(<MemoryRouter><ErasureReviewCenterPage/></MemoryRouter>)
        expect(await screen.findByRole('alert')).toHaveTextContent('Сводка недоступна')
        expect(screen.getAllByText('—')).toHaveLength(4)
    })
    it('shows only tenant intake counters for an administrator',async()=>{
        vi.mocked(loadCapabilities).mockResolvedValue({platform:false,tenantRegistry:true,personalRequests:true,reviewQueue:false,requestsEnabled:true,approvalsEnabled:false})
        vi.mocked(loadRegistrySummary).mockResolvedValue({scope:'TENANT',total:9,registered:5,withdrawn:1,drafts:3,reviewed:null,pending:null,needsInformation:null,held:null})
        render(<MemoryRouter><ErasureReviewCenterPage/></MemoryRouter>)
        expect(await screen.findByText('9')).toBeInTheDocument()
        expect(screen.getByText('Черновики')).toBeInTheDocument()
        expect(screen.queryByText('Заявки с удержаниями')).not.toBeInTheDocument()
        expect(loadRegistry).not.toHaveBeenCalled()
        expect(screen.getByRole('link',{name:/Все заявки/})).toHaveAttribute('href','/admin/erasure/intake')
    })
})
