// ============================================================
// frontend/src/api/http.ts
// Публичный фасад: сохраняет прежние импорты потребителей.
// ============================================================

export { ApiError, getApiErrorMessage, getApiErrorPresentation, parseRetryAfter } from './httpErrors'
export type { ApiErrorBody, ApiErrorPresentation } from './httpErrors'
export type { ApiResponseType, ApiRequestOptions } from './http/httpClient'
export type { UnauthorizedReason } from './http/httpAuthFlow'
export { API_TIMEOUTS } from './http/httpConfig'
export { validateApiBasePath, buildApiUrl } from './http/httpPaths'
export { subscribeUnauthorized, ensureCsrfToken, rotateCsrfToken } from './http/httpAuthFlow'
export { apiRequest } from './http/httpClient'
