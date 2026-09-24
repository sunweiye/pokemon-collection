import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http as mswHttp, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'

const server = setupServer()

describe('LoginApp HTTP integration', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
  afterEach(() => {
    server.resetHandlers()
    vi.resetModules()
  })
  afterAll(() => server.close())
  beforeEach(() => {
    document.head.innerHTML = `
      <meta name="_csrf" content="masked-token">
      <meta name="_csrf_header" content="X-CSRF-TOKEN">
    `
  })

  it('runs the component through client and http with MSW', async () => {
    let requestBody: unknown
    let csrfHeader: string | null = null
    server.use(mswHttp.post('/api/auth/login', async ({ request }) => {
      requestBody = await request.json()
      csrfHeader = request.headers.get('X-CSRF-TOKEN')
      return HttpResponse.json(
        { error: 'UNAUTHORIZED', message: 'Invalid username or password.' },
        { status: 401 },
      )
    }))

    const { LoginApp } = await import('./LoginApp')
    const user = userEvent.setup()
    render(<LoginApp />)
    await user.type(screen.getByLabelText('Username'), 'ash')
    await user.type(screen.getByLabelText('Password'), 'wrong')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password')
    expect(requestBody).toEqual({ username: 'ash', password: 'wrong' })
    expect(csrfHeader).toBe('masked-token')
  })
})
