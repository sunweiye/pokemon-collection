import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LoginApp } from './LoginApp'
import { login } from '../api/client'
import { makeApiError } from '../test/apiError'

vi.mock('../api/client', () => ({
  login: vi.fn(),
}))

describe('LoginApp', () => {
  beforeEach(() => vi.clearAllMocks())
  afterEach(() => vi.unstubAllGlobals())

  it('trims the username and shows the authentication error inline', async () => {
    const error = makeApiError(401, 'UNAUTHORIZED', '/api/auth/login')
    vi.mocked(login).mockRejectedValue(error)
    const user = userEvent.setup()

    render(<LoginApp />)
    await user.type(screen.getByLabelText('Username'), '  ash  ')
    await user.type(screen.getByLabelText('Password'), 'wrong')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password')
    expect(login).toHaveBeenCalledWith('ash', 'wrong')
  })

  it('requires a trimmed username and password before submitting', async () => {
    const user = userEvent.setup()

    render(<LoginApp />)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(screen.getByText('Please enter your username.')).toBeInTheDocument()
    expect(screen.getByText('Please enter your password.')).toBeInTheDocument()
    expect(login).not.toHaveBeenCalled()
  })

  it('explains a CSRF 403 separately from invalid credentials', async () => {
    const error = makeApiError(403, 'CSRF_FORBIDDEN', '/api/auth/login')
    vi.mocked(login).mockRejectedValue(error)
    const user = userEvent.setup()

    render(<LoginApp />)
    await user.type(screen.getByLabelText('Username'), 'ash')
    await user.type(screen.getByLabelText('Password'), 'password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('security token has expired')
  })

  it('redirects to the app after a successful login', async () => {
    const testWindow = Object.create(window) as Window & typeof globalThis
    Object.defineProperty(testWindow, 'location', { value: { href: '/login' }, configurable: true })
    vi.stubGlobal('window', testWindow)
    vi.mocked(login).mockResolvedValue({ id: 1, username: 'ash' })
    const user = userEvent.setup()

    render(<LoginApp />)
    await user.type(screen.getByLabelText('Username'), 'ash')
    await user.type(screen.getByLabelText('Password'), 'password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(testWindow.location.href).toBe('/'))
  })

  it('shows the unavailable message for network and server errors', async () => {
    vi.mocked(login).mockRejectedValue(new Error('Network down'))
    const user = userEvent.setup()

    render(<LoginApp />)
    await user.type(screen.getByLabelText('Username'), 'ash')
    await user.type(screen.getByLabelText('Password'), 'password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in is temporarily unavailable')
  })

  it('shows the unavailable message for a 500 response', async () => {
    const error = makeApiError(500, 'INTERNAL_ERROR', '/api/auth/login')
    vi.mocked(login).mockRejectedValue(error)
    const user = userEvent.setup()

    render(<LoginApp />)
    await user.type(screen.getByLabelText('Username'), 'ash')
    await user.type(screen.getByLabelText('Password'), 'password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in is temporarily unavailable')
  })

  it('disables the submit button while login is pending', async () => {
    let rejectLogin!: (error: Error) => void
    vi.mocked(login).mockReturnValue(new Promise((_resolve, reject) => { rejectLogin = reject }))
    const user = userEvent.setup()

    render(<LoginApp />)
    await user.type(screen.getByLabelText('Username'), 'ash')
    await user.type(screen.getByLabelText('Password'), 'password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(screen.getByRole('button', { name: 'Signing in…' })).toBeDisabled()
    rejectLogin(new Error('server error'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in is temporarily unavailable')
  })
})
