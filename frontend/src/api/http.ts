export function getToken(): string | null {
    return localStorage.getItem('safeai_token')
}

export function setToken(token: string): void {
    localStorage.setItem('safeai_token', token)
}

export function clearToken(): void {
    localStorage.removeItem('safeai_token')
}

export async function apiRequest<T>(
    url: string,
    options: RequestInit = {}
): Promise<T> {
    const token = getToken()

    const headers = new Headers(options.headers)

    if (!headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json')
    }

    if (token) {
        headers.set('Authorization', `Bearer ${token}`)
    }

    const response = await fetch(url, {
        ...options,
        headers,
    })

    if (!response.ok) {
        if (response.status === 401) {
            clearToken()
        }

        const text = await response.text()
        throw new Error(text || `Request failed with status ${response.status}`)
    }

    if (response.status === 204) {
        return undefined as T
    }

    const data = await response.json()

    return data as T
}