// frontend/src/entities/knowledge/api/knowledgeApi.production.test.ts
// ============================================================
// frontend/src/entities/knowledge/api/knowledgeApi.production.test.ts
// Контракты доступа к Knowledge Bases, версий и ACL.
// ============================================================
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest, API_TIMEOUTS } from '../../../shared/api/http'
import {
  searchKnowledgeBases, getKnowledgeBases, getKnowledgeBase, getKnowledgeBaseAccess,
  createKnowledgeBase, updateKnowledgeBase, getKnowledgeBaseMembers,
  searchKnowledgeMemberCandidates, addKnowledgeBaseMember,
  updateKnowledgeBaseMember, removeKnowledgeBaseMember,
  parseKnowledgeBase, parseKnowledgeBaseAccess, parseKnowledgeBaseMember,
} from './knowledgeApi'
vi.mock('../../../shared/api/http', async importOriginal => ({...(await importOriginal<typeof import('../../../shared/api/http')>()),apiRequest:vi.fn()}))
const request=vi.mocked(apiRequest)
const ID='11111111-1111-4111-8111-111111111111'
const USER='22222222-2222-4222-8222-222222222222'
const ORG='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const kb=()=>({id:ID,organizationId:ORG,name:'Runbooks',description:null,visibility:'MEMBERS',enabled:true,createdByUserId:USER,version:3,createdAt:'2026-08-10T10:00:00Z',updatedAt:'2026-08-11T10:00:00Z'})
const member=()=>({knowledgeBaseId:ID,userId:USER,email:'a@example.test',fullName:null,accessLevel:'VIEWER',version:7,createdAt:'2026-08-10T10:00:00Z',updatedAt:'2026-08-11T10:00:00Z'})
const access=()=>({knowledgeBaseId:ID,accessLevel:'OWNER',administrator:false,canEditDocuments:true,canManageBase:true,canManageMembers:true})
const page=(v:unknown)=>({content:[v],page:0,size:50,totalElements:1,totalPages:1})
beforeEach(()=>vi.resetAllMocks())

describe('knowledgeApi — все 11 операций',()=>{
  it('сохраняет маршруты поиска, списка, просмотра, доступа, членов и кандидатов',async()=>{
    request.mockResolvedValueOnce(page(kb())).mockResolvedValueOnce(page(kb()))
      .mockResolvedValueOnce(kb()).mockResolvedValueOnce(access())
      .mockResolvedValueOnce(page(member())).mockResolvedValueOnce([{userId:USER,email:'a@example.test',fullName:null}])
    await searchKnowledgeBases(0,50,{query:' Run ',visibility:'MEMBERS',enabled:true})
    await getKnowledgeBases()
    await getKnowledgeBase(ID)
    await getKnowledgeBaseAccess(ID)
    await getKnowledgeBaseMembers(ID)
    await searchKnowledgeMemberCandidates('a',20)
    expect(request.mock.calls.map(([url])=>url)).toEqual([
      '/api/knowledge-bases/search?page=0&size=50&query=Run&enabled=true&visibility=MEMBERS',
      '/api/knowledge-bases?page=0&size=50',
      `/api/knowledge-bases/${ID}`, `/api/knowledge-bases/${ID}/access`,
      `/api/knowledge-bases/${ID}/members?page=0&size=50`,
      '/api/knowledge-bases/member-candidates?query=a&limit=20',
    ])
    expect(request.mock.calls.every(([,o])=>o?.timeoutMs===API_TIMEOUTS.default)).toBe(true)
  })
  it('сохраняет POST/PATCH/DELETE, IDs, payload и optimistic version',async()=>{
    request.mockResolvedValueOnce(kb()).mockResolvedValueOnce({...kb(),version:4})
      .mockResolvedValueOnce(member()).mockResolvedValueOnce({...member(),version:8})
      .mockResolvedValueOnce(undefined)
    await createKnowledgeBase({name:'Runbooks',description:null,visibility:'MEMBERS'})
    await updateKnowledgeBase(ID,{name:'Runbooks',description:null,visibility:'MEMBERS',enabled:true,expectedVersion:3})
    await addKnowledgeBaseMember(ID,{userId:USER,accessLevel:'VIEWER'})
    await updateKnowledgeBaseMember(ID,USER,{accessLevel:'EDITOR',expectedVersion:7})
    await removeKnowledgeBaseMember(ID,USER,8)
    expect(request.mock.calls.map(([u,o])=>[u,o?.method])).toEqual([
      ['/api/knowledge-bases','POST'],[`/api/knowledge-bases/${ID}`,'PATCH'],
      [`/api/knowledge-bases/${ID}/members`,'POST'],
      [`/api/knowledge-bases/${ID}/members/${USER}`,'PATCH'],
      [`/api/knowledge-bases/${ID}/members/${USER}?expectedVersion=8`,'DELETE'],
    ])
    expect(request.mock.calls[1][1]?.json).toEqual(expect.objectContaining({expectedVersion:3}))
    expect(request.mock.calls[3][1]?.json).toEqual(expect.objectContaining({expectedVersion:7}))
  })
  it.each([-1,1.5,Number.MAX_SAFE_INTEGER+1,NaN])('не отправляет PATCH/DELETE с ошибочной версией %s',async version=>{
    const data={name:'n',description:null,visibility:'MEMBERS' as const,enabled:true,expectedVersion:version}
    await expect(updateKnowledgeBase(ID,data)).rejects.toThrow('expectedVersion')
    await expect(updateKnowledgeBaseMember(ID,USER,{accessLevel:'EDITOR',expectedVersion:version})).rejects.toThrow('expectedVersion')
    expect(()=>removeKnowledgeBaseMember(ID,USER,version)).toThrow('expectedVersion')
    expect(request).not.toHaveBeenCalled()
  })
  it('не позволяет path traversal через UUID',async()=>{
    await expect(getKnowledgeBase('../tenant')).rejects.toThrow()
    expect(request).not.toHaveBeenCalled()
  })
  it('прокидывает AbortSignal и нормализует limit кандидатов',async()=>{
    request.mockResolvedValue([])
    const controller=new AbortController()
    await searchKnowledgeMemberCandidates('x',100,{signal:controller.signal})
    expect(request.mock.calls[0][0]).toContain('limit=50')
    expect(request.mock.calls[0][1]?.signal).toBe(controller.signal)
  })
})

describe('knowledgeApi — строгие DTO',()=>{
  it('различает MEMBERS/ORGANIZATION, nullable description и версии',()=>{
    expect(parseKnowledgeBase(kb()).version).toBe(3)
    expect(parseKnowledgeBase({...kb(),description:undefined}).description).toBeNull()
    expect(parseKnowledgeBase({...kb(),visibility:'ORGANIZATION'}).visibility).toBe('ORGANIZATION')
  })
  it('проверяет ACL-флаги и уровни доступа',()=>{
    expect(parseKnowledgeBaseAccess(access()).canManageMembers).toBe(true)
    expect(parseKnowledgeBaseMember(member()).accessLevel).toBe('VIEWER')
  })
  it.each([
    [{...kb(), visibility:'PUBLIC'},/неизвестное значение/],
    [{...kb(), version:-1},/неотрицательным/],
    [{...kb(), enabled:'true'},/boolean/],
    [{...kb(), organizationId:'bad'},/UUID/],
  ])('rejects malformed knowledge base', (value,message)=>{
    expect(()=>parseKnowledgeBase(value)).toThrow(message)
  })
  it('не принимает выдуманные права ACL',()=>{
    expect(()=>parseKnowledgeBaseAccess({...access(),canManageBase:'true'})).toThrow('boolean')
    expect(()=>parseKnowledgeBaseMember({...member(),accessLevel:'SUPER_ADMIN'})).toThrow('неизвестное значение')
  })
  it('отвергает не-массив кандидатов',async()=>{
    request.mockResolvedValue({})
    await expect(searchKnowledgeMemberCandidates('x')).rejects.toThrow('список кандидатов')
  })
})
