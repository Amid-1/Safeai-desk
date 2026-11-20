// ============================================================
// frontend/src/features/audit/api/adminAuditApi.production.test.ts
// Журнал: API, tenant-фильтры, изоляция чувствительных данных.
// ============================================================
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest, API_TIMEOUTS } from '../../../shared/api/http'
import { getAuditEvents, getAuditSummary, getAuditEventTypes, searchAuditActors,
    searchAuditTargetOrganizations, parseAuditEvent, parseAuditDetails } from './adminAuditApi'
vi.mock('../../../shared/api/http',async importOriginal=>({...await importOriginal<typeof import('../../../shared/api/http')>(),apiRequest:vi.fn()}))
const request=vi.mocked(apiRequest)
const ID='11111111-1111-4111-8111-111111111111'
const ORG='22222222-2222-4222-8222-222222222222'
const event=()=>({id:ID,targetOrganizationId:ORG,targetOrganizationName:'Org',actorUserId:ID,
  actorOrganizationId:ORG,actorEmail:'admin@example.test',actorDisplayName:'Admin',
  eventType:'USER_CREATED',details:{userId:ID,safe:true},createdAt:'2026-08-10T12:00:00Z'})
const page={content:[],page:0,size:50,totalElements:0,totalPages:0}
beforeEach(()=>vi.resetAllMocks())

describe('adminAuditApi — HTTP',()=>{
  it('использует общие серверные фильтры для сводки и списка',async()=>{
    request.mockResolvedValueOnce(page).mockResolvedValueOnce({total:159,successful:145,denied:9,critical:5})
    const filter={targetOrganizationId:ORG,dateFrom:'2026-08-01',dateTo:'2026-08-31',category:'SECURITY' as const,metric:'DENIED' as const}
    await getAuditEvents(0,50,filter)
    expect(await getAuditSummary(filter)).toEqual({total:159,successful:145,denied:9,critical:5})
    for(const [url] of request.mock.calls) {
      expect(url).toContain('category=SECURITY')
      expect(url).toContain('organizationId='+ORG)
    }
    expect(request.mock.calls[0][0]).toContain('metric=DENIED')
    expect(request.mock.calls[1][0]).toContain('/summary?')
    expect(request.mock.calls[1][0]).not.toContain('metric=')
  })
  it('не превращает повреждённую сводку в нули',async()=>{
    request.mockResolvedValueOnce({total:1,successful:2,denied:0,critical:0})
    await expect(getAuditSummary({})).rejects.toThrow()
  })
  it('сохраняет все четыре GET и точные tenant/actor фильтры',async()=>{
    request.mockResolvedValueOnce(page).mockResolvedValueOnce(['USER_CREATED','USER_CREATED','CHAT_SENT'])
      .mockResolvedValueOnce([]).mockResolvedValueOnce([])
    await getAuditEvents(0,50,{eventType:' USER_CREATED ',actorUserId:ID,actorEmail:' admin@example.test ',targetOrganizationId:ORG,dateFrom:'2026-08-01',dateTo:'2026-08-31'})
    expect(await getAuditEventTypes()).toEqual(['CHAT_SENT','USER_CREATED'])
    await searchAuditActors(' admin ', ORG, 100)
    await searchAuditTargetOrganizations(' Org ',100)
    const urls=request.mock.calls.map(([path])=>path)
    expect(urls[0]).toContain('userId='+ID)
    expect(urls[0]).toContain('organizationId='+ORG)
    expect(urls[0]).toContain('userEmail=admin%40example.test')
    expect(urls[0]).toContain('eventType=USER_CREATED')
    expect(urls[1]).toBe('/api/admin/audit-event-types')
    expect(urls[2]).toContain('limit=50')
    expect(urls[3]).toContain('limit=50')
    expect(request.mock.calls.every(([,o])=>o?.timeoutMs===API_TIMEOUTS.report)).toBe(true)
  })
  it('rejects invalid tenant ID before network access',async()=>{
    await expect(getAuditEvents(0,50,{targetOrganizationId:'../evil'})).rejects.toThrow()
    expect(request).not.toHaveBeenCalled()
  })
  it('rejects invalid event type arrays and directory response',async()=>{
    request.mockResolvedValueOnce(['good lower']).mockResolvedValueOnce({})
    await expect(getAuditEventTypes()).rejects.toThrow('некорректный формат')
    await expect(searchAuditActors('u')).rejects.toThrow('массивом')
  })
})

describe('adminAuditApi — защита аудиторских данных',()=>{
  it('отображает только безопасный объект; не подменяет tenant',()=>{
    const result=parseAuditEvent(event())
    expect(result.targetOrganizationId).toBe(ORG)
    expect(result.details).toEqual({userId:ID,safe:true})
    expect(result.detailsInvalid).toBe(false)
    expect(result.detailsTruncated).toBe(false)
  })
  it('поддерживает исторические поля идентификаторов',()=>{
    const value={...event(),targetOrganizationId:undefined,targetOrganizationName:undefined,
      actorUserId:undefined,actorEmail:undefined,organizationId:ORG,
      organizationName:'Legacy',userId:ID,userEmail:'legacy@example.test'}
    expect(parseAuditEvent(value)).toMatchObject({targetOrganizationId:ORG,targetOrganizationName:'Legacy',actorUserId:ID,actorEmail:'legacy@example.test'})
  })
  it('маскирует секреты с разными именами/регистром, включая вложенные',()=>{
    const result=parseAuditDetails({password:'raw',Api_Key:'abc',nested:{refreshToken:'xyz',cookie:'session'},safe:'ok'})
    expect(result.details.password).toBe('[REDACTED]')
    expect(result.details.Api_Key).toBe('[REDACTED]')
    expect(result.details.nested).toEqual({refreshToken:'[REDACTED]',cookie:'[REDACTED]'})
    expect(result.details.safe).toBe('ok')
  })
  it('не допускает prototype pollution и спец-ключи',()=>{
    const data=JSON.parse('{"__proto__":{"admin":true},"constructor":{"secret":true},"safe":42}')
    const result=parseAuditDetails(data)
    expect(Object.getPrototypeOf(result.details)).toBeNull()
    expect(result.details.safe).toBe(42)
    expect(Object.hasOwn(result.details,'__proto__')).toBe(false)
    expect(result.truncated).toBe(true)
  })
  it('ограничивает глубину, количество полей, строки и массивы',()=>{
    const deep={a:{b:{c:{d:{e:{f:{g:{h:{i:{j:'sensitive'}}}}}}}}}}
    const result=parseAuditDetails({large:'x'.repeat(10000),array:Array.from({length:210},(_,i)=>i),deep})
    expect(result.truncated).toBe(true)
    expect(String(result.details.large)).toContain('[TRUNCATED]')
    expect((result.details.array as unknown[]).length).toBe(200)
  })
  it('отмечает некорректные details, не маскируя их под пустую валидную запись',()=>{
    expect(parseAuditDetails('x')).toMatchObject({details:{},invalid:true})
    expect(parseAuditDetails(null)).toMatchObject({details:{},invalid:false})
  })
  it('проверяет тип UUID, eventType и timestamp',()=>{
    expect(()=>parseAuditEvent({...event(),eventType:'user.created'})).toThrow()
    expect(()=>parseAuditEvent({...event(),targetOrganizationId:'bad'})).toThrow()
    expect(()=>parseAuditEvent({...event(),createdAt:'nonsense'})).toThrow()
  })
})
