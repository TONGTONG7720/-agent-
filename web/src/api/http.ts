const TOKEN_KEY = 'magent_token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

interface Result<T> {
  code: number
  message: string
  data: T
}

async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init.headers as Record<string, string> | undefined)
  }
  const token = getToken()
  if (token) {
    headers.satoken = token
  }
  const resp = await fetch(url, { ...init, headers })
  if (resp.status === 401) {
    clearToken()
  }
  const body = (await resp.json()) as Result<T>
  if (!resp.ok || body.code !== 0) {
    throw new Error(body.message || `请求失败(${resp.status})`)
  }
  return body.data
}

export const http = {
  get<T>(url: string): Promise<T> {
    return request<T>(url)
  },
  post<T>(url: string, body?: unknown): Promise<T> {
    return request<T>(url, { method: 'POST', body: JSON.stringify(body ?? {}) })
  },
  put<T>(url: string, body?: unknown): Promise<T> {
    return request<T>(url, { method: 'PUT', body: JSON.stringify(body ?? {}) })
  }
}
