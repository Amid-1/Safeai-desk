// ============================================================
// frontend/src/api/knowledgeApi.ts
// Публичный фасад: сохраняет прежние импорты потребителей.
// ============================================================

export type { KnowledgeBaseVisibility, KnowledgeBaseAccessLevel, KnowledgeBaseAccess, KnowledgeBase, KnowledgeBaseMember, KnowledgeMemberCandidate, CreateKnowledgeBaseRequest, UpdateKnowledgeBaseRequest, CreateKnowledgeBaseMemberRequest, UpdateKnowledgeBaseMemberRequest, KnowledgeBaseListFilters } from './knowledge/knowledgeTypes'
export { searchKnowledgeBases, getKnowledgeBases, getKnowledgeBase, getKnowledgeBaseAccess, createKnowledgeBase, updateKnowledgeBase, getKnowledgeBaseMembers, searchKnowledgeMemberCandidates, addKnowledgeBaseMember, updateKnowledgeBaseMember, removeKnowledgeBaseMember } from './knowledge/knowledgeClient'
export { parseKnowledgeBase, parseKnowledgeBaseAccess, parseKnowledgeBaseMember } from './knowledge/knowledgeParsers'
