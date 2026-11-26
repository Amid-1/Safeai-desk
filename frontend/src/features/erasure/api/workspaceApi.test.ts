// frontend/src/features/erasure/api/workspaceApi.test.ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../../../shared/api/http'
import { loadCapabilities, loadRegistry, loadWorkspaceHistory, loadRegistrySummary, loadRegistryDetail } from './workspaceApi'
vi.mock('../../../shared/api/http',()=>({apiRequest:vi.fn()}))
const request=vi.mocked(apiRequest)
describe('Workspace API contracts',()=>{
    beforeEach(()=>vi.resetAllMocks())
    it('requires explicit boolean capabilities',async()=>{
        request.mockResolvedValueOnce({platform:true,tenantRegistry:false,personalRequests:false,reviewQueue:false,requestsEnabled:false,approvalsEnabled:false})
        expect((await loadCapabilities()).reviewQueue).toBe(false)
        request.mockResolvedValueOnce({platform:true});await expect(loadCapabilities()).rejects.toThrow()
    })
    it('uses bounded server paging and excludes missing personal identity',async()=>{
        request.mockResolvedValueOnce({page:2,pageSize:25,hasNext:false,items:[{requestId:'id',organization:'A',resourceType:'CHAT',state:'PENDING',intakeState:'SUBMITTED',reviewState:'PENDING',reviewVisible:true,holdActive:false,updatedAt:'2026-11-25T00:00:00Z'}]})
        const data=await loadRegistry(false,'PENDING','A & B',2)
        expect(data.items[0].requester).toBeNull()
        expect(request).toHaveBeenLastCalledWith('/api/admin/erasure/registry?state=PENDING&query=A+%26+B&page=2',{method:'GET',signal:undefined})
    })
    it('rejects mismatched page and malformed history',async()=>{
        request.mockResolvedValueOnce({page:4,pageSize:25,hasNext:false,items:[]});await expect(loadRegistry(true,'ALL','',0)).rejects.toThrow()
        request.mockResolvedValueOnce({page:0,pageSize:25,hasNext:false,items:[{version:-1}]});await expect(loadWorkspaceHistory('',0)).rejects.toThrow()
    })
    it('rejects platform review metadata in a tenant summary',async()=>{
        request.mockResolvedValueOnce({scope:'TENANT',total:2,registered:1,withdrawn:0,drafts:1,reviewed:1,pending:1,held:0,needsInformation:0})
        await expect(loadRegistrySummary()).rejects.toThrow()
    })
    it('preserves nullable review fields and does not assert deletion proof',async()=>{
        const item={requestId:'id',organization:'A',resourceType:'CHAT',intakeState:'SUBMITTED',reviewState:null,reviewVisible:false,holdActive:null,
            createdAt:'2026-11-25T00:00:00Z',updatedAt:'2026-11-25T00:00:00Z',executionState:'NOT_AVAILABLE',completionProven:false}
        request.mockResolvedValueOnce(item)
        expect(await loadRegistryDetail('id')).toEqual(item)
        request.mockResolvedValueOnce({...item,completionProven:true})
        await expect(loadRegistryDetail('id')).rejects.toThrow()
    })
    it('keeps independent history event identities',async()=>{
        const event={requestId:'id',organization:'A',eventCode:'SUBMITTED',createdAt:'2026-11-25T00:00:00Z',version:0,eventId:'e1',source:'INTAKE'}
        request.mockResolvedValueOnce({page:0,pageSize:25,hasNext:false,items:[event,{...event,eventId:'e2',source:'REVIEW'}]})
        expect((await loadWorkspaceHistory('',0)).items.map(x=>x.source)).toEqual(['INTAKE','REVIEW'])
    })
})
