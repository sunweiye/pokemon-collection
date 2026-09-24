import axios from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

function setMetaToken(token?: string) {
  document.head.innerHTML = `
    ${token ? `<meta name="_csrf" content="${token}">` : ''}
    <meta name="_csrf_header" content="X-CSRF-TOKEN">
  `
}

function makeAdapter() {
  return vi.fn().mockImplementation(async (config) => ({
    data: {},
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  }))
}

function makeAxiosError(status: number, url: string) {
  const error = new axios.AxiosError(`HTTP ${status}`)
  error.config = { url } as never
  error.response = {
    status,
    statusText: `HTTP ${status}`,
    headers: {},
    config: error.config,
    data: { error: 'UNAUTHORIZED', message: 'Unauthorized' },
  }
  return error
}

describe('http CSRF handling', () => {
  beforeEach(() => {
    vi.resetModules()
    setMetaToken('masked-token')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('sends the CSRF token rendered in the meta tag', async () => {
    const { http } = await import('./http')
    const adapter = makeAdapter()
    http.defaults.adapter = adapter

    await http.post('/api/auth/login', { username: 'ash', password: 'password' })

    expect(adapter).toHaveBeenCalledOnce()
    expect(adapter.mock.calls[0][0].headers.get('X-CSRF-TOKEN')).toBe('masked-token')
  })

  it.each(['post', 'put', 'patch', 'delete'] as const)('sends the meta token on %s requests', async (method) => {
    const { http } = await import('./http')
    const adapter = makeAdapter()
    http.defaults.adapter = adapter

    await http.request({ method, url: '/api/collection' })

    expect(adapter.mock.calls[0][0].headers.get('X-CSRF-TOKEN')).toBe('masked-token')
  })

  it('uses the header name rendered by the server instead of a hard-coded one', async () => {
    document.head.innerHTML = `
      <meta name="_csrf" content="masked-token">
      <meta name="_csrf_header" content="X-Custom-Csrf">
    `
    const { http } = await import('./http')
    const adapter = makeAdapter()
    http.defaults.adapter = adapter

    await http.post('/api/auth/logout')

    expect(adapter.mock.calls[0][0].headers.get('X-Custom-Csrf')).toBe('masked-token')
    expect(adapter.mock.calls[0][0].headers.get('X-CSRF-TOKEN')).toBeUndefined()
  })

  it('rejects a write request without sending it when the page has no CSRF token', async () => {
    setMetaToken()
    const fetchShell = vi.spyOn(axios, 'get')
    const { http } = await import('./http')
    const adapter = makeAdapter()
    http.defaults.adapter = adapter

    await expect(http.post('/api/auth/logout')).rejects.toThrow('did not provide a CSRF token')

    expect(adapter).not.toHaveBeenCalled()
    expect(fetchShell).not.toHaveBeenCalled()
  })

  it('does not need a CSRF token for a GET request', async () => {
    setMetaToken()
    const { http } = await import('./http')
    const adapter = makeAdapter()
    http.defaults.adapter = adapter

    await http.get('/api/pokemon/search', { params: { query: 'pikachu' } })

    expect(adapter).toHaveBeenCalledOnce()
    expect(adapter.mock.calls[0][0].headers.get('X-CSRF-TOKEN')).toBeUndefined()
  })

  it('redirects authenticated API 401 responses to the login page', async () => {
    const testWindow = Object.create(window) as Window & typeof globalThis
    Object.defineProperty(testWindow, 'location', { value: { href: '/' }, configurable: true })
    vi.stubGlobal('window', testWindow)
    const { http } = await import('./http')
    http.defaults.adapter = vi.fn().mockRejectedValue(makeAxiosError(401, '/api/collection'))

    await expect(http.get('/api/collection')).rejects.toMatchObject({ response: { status: 401 } })

    expect(testWindow.location.href).toBe('/login')
  })

  it('does not redirect the login request when its own response is 401', async () => {
    const testWindow = Object.create(window) as Window & typeof globalThis
    Object.defineProperty(testWindow, 'location', { value: { href: '/login' }, configurable: true })
    vi.stubGlobal('window', testWindow)
    const { http } = await import('./http')
    http.defaults.adapter = vi.fn().mockRejectedValue(makeAxiosError(401, '/api/auth/login'))

    await expect(http.post('/api/auth/login')).rejects.toMatchObject({ response: { status: 401 } })

    expect(testWindow.location.href).toBe('/login')
  })
})
