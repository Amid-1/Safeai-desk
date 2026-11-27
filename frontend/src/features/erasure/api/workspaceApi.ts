// frontend/src/features/erasure/api/workspaceApi.ts
import { apiRequest } from '../../../shared/api/http'
import { contractError, expectRecord } from '../../../shared/api/runtime'

export type Capabilities = { platform: boolean; tenantRegistry: boolean; personalRequests: boolean;
    reviewQueue: boolean; requestsEnabled: boolean; approvalsEnabled: boolean }
export async function loadCapabilities(signal?: AbortSignal): Promise<Capabilities> {
    const d = expectRecord(await apiRequest('/api/erasure/requests/capabilities', { method: 'GET', signal }), 'erasure.capabilities')
    function flag(key: keyof Capabilities): boolean {
        if (typeof d[key] !== 'boolean') throw contractError('Некорректный ответ сервера.')
        return d[key] as boolean
    }
    return { platform: flag('platform'), tenantRegistry: flag('tenantRegistry'), personalRequests: flag('personalRequests'),
        reviewQueue: flag('reviewQueue'), requestsEnabled: flag('requestsEnabled'), approvalsEnabled: flag('approvalsEnabled') }
}
export type RegistryItem = { requestId: string; organization: string; requester: string | null;
    resourceType: string; state: string; holdActive: boolean; updatedAt: string;
    intakeState: string; reviewState: string | null; reviewVisible: boolean }
export type RegistryPage = { items: RegistryItem[]; page: number; pageSize: number; hasNext: boolean }
export async function loadRegistry(review: boolean, state: string, query: string, page: number,
    signal?: AbortSignal): Promise<RegistryPage> {
    const params = new URLSearchParams({ state, query, page: String(page) })
    const url = review ? '/api/admin/erasure/review-queue' : '/api/admin/erasure/registry'
    const d = expectRecord(await apiRequest(`${url}?${params}`, { method: 'GET', signal }), 'erasure.registry')
    if (!Array.isArray(d.items) || d.items.length > 25 || d.page !== page || d.pageSize !== 25 || typeof d.hasNext !== 'boolean' || (d.items.length === 0 && d.hasNext)) {
        throw contractError('Некорректный ответ реестра.')
    }
    const items = d.items.map((value: unknown): RegistryItem => {
        const x = expectRecord(value, 'erasure.registry.item')
        function text(k: string): string {
            const v = x[k]; if (typeof v !== 'string' || !v) throw contractError('Некорректная заявка.'); return v
        }
        if (typeof x.holdActive !== 'boolean' || (x.requester != null && typeof x.requester !== 'string')) throw contractError('Некорректная заявка.')
        if (typeof x.reviewVisible !== 'boolean' || (x.reviewState !== null && (typeof x.reviewState !== 'string' || !x.reviewState))) {
            throw contractError('Некорректная область заявки.')
        }
        const reviewVisible = x.reviewVisible, reviewState = x.reviewState
        if (!reviewVisible && (reviewState !== null || x.holdActive)) throw contractError('Некорректная область заявки.')
        return { requestId: text('requestId'), organization: text('organization'), requester: typeof x.requester === 'string' ? x.requester : null,
            resourceType: text('resourceType'), state: text('state'), holdActive: x.holdActive, updatedAt: text('updatedAt'),
            intakeState: text('intakeState'), reviewState, reviewVisible }
    })
    return { items, page, pageSize: 25, hasNext: d.hasNext }
}

export type HistoryItem = { requestId: string; organization: string; eventCode: string; createdAt: string; version: number; eventId: string; source: 'INTAKE' | 'REVIEW' }
export async function loadWorkspaceHistory(query: string, page: number, signal?: AbortSignal) {
    const params = new URLSearchParams({query,page:String(page)})
    const d = expectRecord(await apiRequest(`/api/admin/erasure/history?${params}`, {method:'GET',signal}), 'erasure.history')
    if (!Array.isArray(d.items)||d.items.length>25||d.page!==page||d.pageSize!==25||typeof d.hasNext!=='boolean') throw new Error('Некорректная история.')
    const items=d.items.map((value:unknown):HistoryItem=>{
        const x=expectRecord(value,'erasure.history.item')
        const text=(k:string):string=>{const v=x[k];if(typeof v!=='string'||!v)throw new Error('Некорректное событие.');return v}
        if(typeof x.version!=='number'||!Number.isSafeInteger(x.version)||x.version<0)throw new Error('Некорректная версия.')
        if(x.source!=='INTAKE'&&x.source!=='REVIEW')throw new Error('Некорректный источник события.')
        return {requestId:text('requestId'),organization:text('organization'),eventCode:text('eventCode'),createdAt:text('createdAt'),version:x.version,eventId:text('eventId'),source:x.source}
    })
    return {items,hasNext:d.hasNext}
}

function requiredText(value: unknown): string {
    if (typeof value !== 'string' || !value) throw contractError('Некорректный ответ сервера.')
    return value
}
function nullableText(value: unknown): string | null {
    return value === null ? null : requiredText(value)
}
function booleanValue(value: unknown): boolean {
    if (typeof value !== 'boolean') throw contractError('Некорректный ответ сервера.')
    return value
}
function count(value: unknown): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw contractError('Некорректная статистика.')
    return value
}
function nullableCount(value: unknown): number | null { return value === null ? null : count(value) }
export type RegistrySummary = { scope: 'PLATFORM' | 'TENANT'; total: number; registered: number; withdrawn: number;
    drafts: number | null; reviewed: number | null; pending: number | null; needsInformation: number | null; held: number | null }
export async function loadRegistrySummary(signal?: AbortSignal): Promise<RegistrySummary> {
    const d = expectRecord(await apiRequest('/api/admin/erasure/registry-summary', {method:'GET',signal}), 'erasure.registry.summary')
    if (d.scope !== 'PLATFORM' && d.scope !== 'TENANT') throw contractError('Некорректная область статистики.')
    const result: RegistrySummary = {scope:d.scope,total:count(d.total),registered:count(d.registered),withdrawn:count(d.withdrawn),
        drafts:nullableCount(d.drafts),reviewed:nullableCount(d.reviewed),pending:nullableCount(d.pending),
        needsInformation:nullableCount(d.needsInformation),held:nullableCount(d.held)}
    const review = [result.reviewed,result.pending,result.needsInformation,result.held]
    if (result.scope==='PLATFORM' ? result.drafts!==null||review.some(v=>v===null) : result.drafts===null||review.some(v=>v!==null)) {
        throw contractError('Некорректная область статистики.')
    }
    if (result.total!==result.registered+result.withdrawn+(result.drafts??0)) throw contractError('Некорректная статистика.')
    return result
}
export type RegistryDetail = {requestId:string;organization:string;resourceType:string;intakeState:string;reviewState:string|null;
    reviewVisible:boolean;holdActive:boolean|null;createdAt:string;updatedAt:string;executionState:'NOT_AVAILABLE';completionProven:false}
export async function loadRegistryDetail(id:string,signal?:AbortSignal):Promise<RegistryDetail> {
    const d=expectRecord(await apiRequest(`/api/admin/erasure/registry/${encodeURIComponent(id)}`,{method:'GET',signal}),'erasure.registry.detail')
    if(d.requestId!==id||d.executionState!=='NOT_AVAILABLE'||d.completionProven!==false)throw new Error('Некорректная карточка.')
    const reviewVisible=booleanValue(d.reviewVisible),reviewState=nullableText(d.reviewState)
    const holdActive=d.holdActive===null?null:booleanValue(d.holdActive)
    if(reviewVisible?holdActive===null:reviewState!==null||holdActive!==null)throw new Error('Некорректная область карточки.')
    return {requestId:id,organization:requiredText(d.organization),resourceType:requiredText(d.resourceType),intakeState:requiredText(d.intakeState),
        reviewState,reviewVisible,holdActive,createdAt:requiredText(d.createdAt),updatedAt:requiredText(d.updatedAt),executionState:'NOT_AVAILABLE',completionProven:false}
}
