// frontend/src/shared/api/http.production.test.ts
// ============================================================
// frontend/src/shared/api/http.production.test.ts
// Регрессии безопасного HTTP-клиента и представления ошибок.
// ============================================================
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiRequest, buildApiUrl, getApiErrorMessage,
    getApiErrorPresentation, parseRetryAfter, rotateCsrfToken,
    subscribeUnauthorized } from './http'
const csrf=(value:string)=>{ document.cookie=`XSRF-TOKEN=${encodeURIComponent(value)}; Path=/` }
const clear=()=>{ document.cookie='XSRF-TOKEN=; Max-Age=0; Path=/' }
const json=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}})
beforeEach(()=>{vi.restoreAllMocks(); clear(); Object.defineProperty(navigator,'locks',{
    configurable:true,value:{request:vi.fn(async(_name:string,_opts:unknown,cb:()=>Promise<unknown>)=>cb())}})})
afterEach(()=>{clear();vi.unstubAllGlobals();vi.useRealTimers()})

describe('http — контракт ошибок и конфиденциальности',()=>{
    it('не показывает внутренние детали 500 пользователю, сохраняя Request ID',()=>{
        const err=new ApiError('stack trace /secret',{code:'INTERNAL_ERROR',requestId:'rid-42'},500)
        expect(getApiErrorPresentation(err,'Внутренняя ошибка')).toEqual({message:'Внутренняя ошибка',requestId:'rid-42'})
        expect(getApiErrorMessage(err,'Внутренняя ошибка')).toBe('Внутренняя ошибка Request ID: rid-42')
    })
    it('не раскрывает Request ID для ожидаемых 4xx',()=>{
        const err=new ApiError('Ограничение',{error:'RATE_LIMIT_EXCEEDED',requestId:'rid'},429)
        expect(getApiErrorPresentation(err,'Fallback')).toEqual({message:'Ограничение',requestId:undefined})
    })
    it('обрабатывает Retry-After HTTP-date и числа без отрицательных задержек',()=>{
        vi.useFakeTimers(); vi.setSystemTime(new Date('2026-08-10T12:00:00Z'))
        expect(parseRetryAfter('0')).toBe(1)
        expect(parseRetryAfter('1.1')).toBe(2)
        expect(parseRetryAfter('-2')).toBeUndefined()
        expect(parseRetryAfter('Mon, 10 Aug 2026 12:00:03 GMT')).toBe(3)
    })
    it('отклоняет попытки path traversal и внешний origin',()=>{
        expect(()=>buildApiUrl('/api/../secrets')).toThrow()
        expect(()=>buildApiUrl('//foreign.example/api')).toThrow()
    })
    it('формирует 5xx Request ID от сервера и не пересылает html stack trace',async()=>{
        vi.stubGlobal('fetch',vi.fn(async()=>new Response('<html lang="">internal secrets</html>',{status:502,headers:{'Content-Type':'text/html','X-Request-Id':'backend-id'}})))
        let caught:unknown
        try { await apiRequest('/api/users') } catch(error) { caught=error }
        expect(caught).toBeInstanceOf(ApiError)
        expect((caught as ApiError).requestId).toBe('backend-id')
        expect((caught as ApiError).message).not.toContain('internal secrets')
    })
})

describe('http — CSRF и небезопасные запросы',()=>{
    it('не выполняет мутацию, если сервер не устанавливает CSRF cookie',async()=>{
        const fetchMock=vi.fn(async()=>new Response(null,{status:204}))
        vi.stubGlobal('fetch',fetchMock)
        await expect(apiRequest('/api/users',{method:'DELETE'})).rejects.toMatchObject({errorCode:'CSRF_TOKEN_MISSING'})
        expect(fetchMock).toHaveBeenCalledTimes(1)
    })
    it('не пытается автоматически повторять не-replayable ReadableStream',async()=>{
        csrf('token')
        const fetchMock=vi.fn()
        vi.stubGlobal('fetch',fetchMock)
        await expect(apiRequest('/api/upload',{method:'POST',body:new ReadableStream()})).rejects.toMatchObject({errorCode:'NON_REPLAYABLE_BODY'})
        expect(fetchMock).not.toHaveBeenCalled()
    })
    it('не допускает одновременно json и body',async()=>{
        await expect(apiRequest('/api/users',{method:'POST',json:{x:1},body:'different'})).rejects.toThrow('одновременно')
    })
    it('передаёт Request-ID, csrf и credentials include при мутации',async()=>{
        csrf('secure')
        const fetchMock=vi.fn(async()=>new Response(null,{status:204}))
        vi.stubGlobal('fetch',fetchMock)
        await apiRequest('/api/users',{method:'PATCH',json:{enabled:false}})
        const [url,init]=fetchMock.mock.calls[0] as unknown as [string,RequestInit]
        const headers=new Headers(init.headers)
        expect(url).toBe('/api/users')
        expect(headers.get('X-XSRF-TOKEN')).toBe('secure')
        expect(headers.get('X-Request-Id')).toBeTruthy()
        expect(init.credentials).toBe('include')
        expect(init.mode).toBe('same-origin')
    })
    it('не считает ротацией CSRF выдачу прежнего токена',async()=>{
        csrf('same')
        vi.stubGlobal('fetch',vi.fn(async()=>new Response(null,{status:204})))
        await expect(rotateCsrfToken('same')).rejects.toMatchObject({errorCode:'CSRF_TOKEN_NOT_ROTATED'})
    })
    it('возвращает Blob только для явно выбранного binary responseType',async()=>{
        vi.stubGlobal('fetch',vi.fn(async()=>new Response('file-content',{status:200,headers:{'Content-Type':'application/pdf'}})))
        const blob=await apiRequest<Blob>('/api/download',{responseType:'blob'})
        expect(blob).toBeInstanceOf(Blob)
        expect(await blob.text()).toBe('file-content')
    })
    it('не отправляет событие unauthorized при штатном 403',async()=>{
        const received:string[]=[]
        const off=subscribeUnauthorized(reason=>received.push(reason))
        vi.stubGlobal('fetch',vi.fn(async()=>json({code:'ACCESS_DENIED',message:'Denied'},403)))
        await expect(apiRequest('/api/users')).rejects.toBeInstanceOf(ApiError)
        off()
        expect(received).toEqual([])
    })
})
