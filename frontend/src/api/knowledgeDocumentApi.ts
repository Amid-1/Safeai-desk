import { API_TIMEOUTS, apiRequest } from './http'
import { uuidPathSegment, buildQueryString, normalizePage, normalizePageSize } from './query'
import { expectBoolean, expectEnum, expectInstant, expectNonNegativeInteger, expectNullableString, expectRecord, expectString, expectUuid, parsePageResponse } from './runtime'
import type { PageResponse } from '../utils/page'

const STATUSES=['PENDING','VALIDATING','EXTRACTING','READY','FAILED'] as const
export type KnowledgeIngestionStatus=typeof STATUSES[number]
export type KnowledgeDocument={id:string;knowledgeBaseId:string;name:string;enabled:boolean;version:number;currentVersionId:string|null;versionNumber:number|null;originalFilename:string|null;mediaType:string|null;sizeBytes:number;status:KnowledgeIngestionStatus|null;createdAt:string;updatedAt:string}

const path=(kb:string)=>`/api/knowledge-bases/${uuidPathSegment(kb)}/documents`
export async function getKnowledgeDocuments(kb:string,page=0,size=50,signal?:AbortSignal):Promise<PageResponse<KnowledgeDocument>>{
    const value=await apiRequest<unknown>(path(kb)+buildQueryString({page:normalizePage(page),size:normalizePageSize(size,50,100)}),{method:'GET',signal,timeoutMs:API_TIMEOUTS.default})
    return parsePageResponse(value,parseKnowledgeDocument)
}
export async function uploadKnowledgeDocument(kb:string,file:File,name:string):Promise<KnowledgeDocument>{
    const form=new FormData();form.append('file',file);if(name.trim())form.append('name',name.trim())
    return parseKnowledgeDocument(await apiRequest<unknown>(path(kb),{method:'POST',body:form,timeoutMs:API_TIMEOUTS.default}),'document')
}
export async function uploadKnowledgeDocumentVersion(kb:string,documentId:string,file:File):Promise<KnowledgeDocument>{
    const form=new FormData();form.append('file',file)
    return parseKnowledgeDocument(await apiRequest<unknown>(`${path(kb)}/${uuidPathSegment(documentId)}/versions`,{method:'POST',body:form,timeoutMs:API_TIMEOUTS.default}),'document')
}
export function knowledgeDocumentDownloadUrl(kb:string,documentId:string):string{return `${path(kb)}/${uuidPathSegment(documentId)}/download`}

export function parseKnowledgeDocument(value:unknown,field='document'):KnowledgeDocument{
    const r=expectRecord(value,field);const nullableUuid=(v:unknown,n:string)=>v==null?null:expectUuid(v,n);const nullableInt=(v:unknown,n:string)=>v==null?null:expectNonNegativeInteger(v,n);const nullableStatus=(v:unknown,n:string)=>v==null?null:expectEnum(v,n,STATUSES)
    return {id:expectUuid(r.id,`${field}.id`),knowledgeBaseId:expectUuid(r.knowledgeBaseId,`${field}.knowledgeBaseId`),name:expectString(r.name,`${field}.name`,{maxLength:255}),enabled:expectBoolean(r.enabled,`${field}.enabled`),version:expectNonNegativeInteger(r.version,`${field}.version`),currentVersionId:nullableUuid(r.currentVersionId,`${field}.currentVersionId`),versionNumber:nullableInt(r.versionNumber,`${field}.versionNumber`),originalFilename:expectNullableString(r.originalFilename,`${field}.originalFilename`,{maxLength:255}),mediaType:expectNullableString(r.mediaType,`${field}.mediaType`,{maxLength:127}),sizeBytes:expectNonNegativeInteger(r.sizeBytes,`${field}.sizeBytes`),status:nullableStatus(r.status,`${field}.status`),createdAt:expectInstant(r.createdAt,`${field}.createdAt`),updatedAt:expectInstant(r.updatedAt,`${field}.updatedAt`)}
}
