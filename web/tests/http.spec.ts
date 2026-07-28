import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http, setToken, getToken, clearToken } from '../src/api/http'

// node 环境下模拟 localStorage
const store = new Map<string, string>()
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k)
})

function mockFetch(status: number, body: unknown) {
  const fn = vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body)
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

describe('http 封装', () => {
  beforeEach(() => {
    store.clear()
  })

  it('自动携带 satoken header 并返回 data 字段', async () => {
    setToken('tok-123')
    const fn = mockFetch(200, { code: 0, message: 'ok', data: { id: 1 } })
    const data = await http.get<{ id: number }>('/api/tasks/1')
    expect(data.id).toBe(1)
    const [, init] = fn.mock.calls[0]
    expect((init.headers as Record<string, string>).satoken).toBe('tok-123')
  })

  it('HTTP 401 时清空本地 token 并抛错', async () => {
    setToken('expired')
    mockFetch(401, { code: 401, message: '未登录或登录已过期' })
    await expect(http.get('/api/projects')).rejects.toThrow('未登录')
    expect(getToken()).toBe('')
  })

  it('业务码非0抛出携带 message 的错误', async () => {
    setToken('tok')
    mockFetch(409, { code: 409, message: '任务状态为 done，不允许此操作' })
    await expect(http.post('/api/tasks/1/approve', { decision: 'pass' }))
      .rejects.toThrow('不允许此操作')
  })

  it('clearToken 后请求不带 satoken', async () => {
    setToken('tok')
    clearToken()
    const fn = mockFetch(200, { code: 0, message: 'ok', data: null })
    await http.get('/api/auth/ping')
    const [, init] = fn.mock.calls[0]
    expect((init.headers as Record<string, string>).satoken).toBeUndefined()
  })
})
