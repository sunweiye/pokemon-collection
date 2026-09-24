import { FormEvent, useState } from 'react'
import axios from 'axios'
import { LogIn, Sparkles } from 'lucide-react'
import { login } from '../api/client'
import { Button } from '../components/ui/button'
import { Card, CardContent, CardHeader } from '../components/ui/card'
import { Input } from '../components/ui/input'
import { t } from '../i18n'
import type { ErrorResponse } from '../types/api'

export function LoginApp() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [usernameError, setUsernameError] = useState('')
  const [passwordError, setPasswordError] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const normalizedUsername = username.trim()
    setUsername(normalizedUsername)
    setError('')
    setUsernameError(normalizedUsername ? '' : t('login.validation.usernameRequired'))
    setPasswordError(password ? '' : t('login.validation.passwordRequired'))
    if (!normalizedUsername || !password) return

    setIsSubmitting(true)
    try {
      await login(normalizedUsername, password)
      window.location.href = '/'
    } catch (requestError) {
      const response = axios.isAxiosError<ErrorResponse>(requestError) ? requestError.response : undefined
      if (response?.status === 401 || response?.data?.error === 'UNAUTHORIZED') {
        setError(t('login.errors.invalidCredentials'))
      } else if (response?.status === 403 || response?.data?.error === 'CSRF_FORBIDDEN') {
        setError(t('login.errors.csrfExpired'))
      } else {
        setError(t('login.errors.unavailable'))
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <main className="auth-layout flex min-h-screen items-center justify-center px-4 py-10">
      <Card className="w-full max-w-md overflow-hidden">
        <CardHeader className="bg-gradient-to-br from-indigo-50 via-white to-sky-50 pb-5">
          <div className="mb-6 flex h-12 w-12 items-center justify-center rounded-2xl bg-indigo-600 text-white shadow-lg shadow-indigo-200">
            <Sparkles size={22} />
          </div>
          <p className="mb-2 text-xs font-bold uppercase tracking-[0.18em] text-indigo-600">{t('login.brand')}</p>
          <h1 id="login-title" className="text-3xl font-bold tracking-tight text-slate-950">{t('login.title')}</h1>
          <p className="mt-2 text-sm leading-6 text-slate-500">{t('login.subtitle')}</p>
        </CardHeader>
        <CardContent className="pt-6">
          <form onSubmit={handleSubmit} noValidate className="space-y-5" aria-labelledby="login-title">
            <div className="space-y-2">
              <label htmlFor="username" className="text-sm font-semibold text-slate-700">{t('login.username')}</label>
              <Input
                id="username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                autoComplete="username"
                placeholder={t('login.usernamePlaceholder')}
                aria-invalid={Boolean(usernameError)}
                aria-describedby={usernameError ? 'username-error' : undefined}
              />
              {usernameError && <p id="username-error" className="text-sm text-rose-600">{usernameError}</p>}
            </div>
            <div className="space-y-2">
              <label htmlFor="password" className="text-sm font-semibold text-slate-700">{t('login.password')}</label>
              <Input
                id="password"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete="current-password"
                placeholder={t('login.passwordPlaceholder')}
                aria-invalid={Boolean(passwordError)}
                aria-describedby={passwordError ? 'password-error' : undefined}
              />
              {passwordError && <p id="password-error" className="text-sm text-rose-600">{passwordError}</p>}
            </div>
            {error && <div className="rounded-xl border border-rose-100 bg-rose-50 px-3.5 py-3 text-sm text-rose-700" role="alert">{error}</div>}
            <Button type="submit" size="lg" className="w-full" disabled={isSubmitting}>
              <LogIn size={17} />
              {isSubmitting ? t('login.submitting') : t('login.submit')}
            </Button>
          </form>
        </CardContent>
      </Card>
    </main>
  )
}
