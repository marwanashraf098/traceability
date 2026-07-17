import { useState, FormEvent, useEffect } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { login } from '../api'
import { setAccessToken } from '../auth'
import { Logo } from '../components/Logo'
import { Input, Button } from '../components/ui'

export default function Login() {
  const { t }      = useTranslation()
  const navigate   = useNavigate()
  const [email,    setEmail]    = useState('')
  const [password, setPassword] = useState('')
  const [error,    setError]    = useState('')
  const [loading,  setLoading]  = useState(false)

  // Remove any stale key left from the pre-cookie auth system.
  useEffect(() => { localStorage.removeItem('token') }, [])

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const res = await login(email, password)
      setAccessToken(res.accessToken)
      navigate('/overview')
    } catch {
      setError(t('login.error'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-bg flex items-center justify-center px-4">

      {/* Brand glow — decorative, symmetric, not directional */}
      <div aria-hidden className="pointer-events-none fixed inset-0 overflow-hidden">
        <div className="absolute top-1/3 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] rounded-full bg-trace-blue/5 blur-3xl" />
      </div>

      <div className="w-full max-w-sm relative z-10">

        {/* Wordmark — letters text-primary, only trailing dot is trace-blue */}
        <div className="text-center mb-10">
          <Logo variant="icon" size={56} className="mb-5" />
          <h1 className="text-display font-light text-primary tracking-tight">
            traced<span className="text-trace-blue">.</span>
          </h1>
          <p className="text-small text-muted mt-2 tracking-wide">{t('login.subtitle')}</p>
        </div>

        {/* Form card */}
        <form onSubmit={handleSubmit} className="card p-6 space-y-4 bg-surface">

          <div className="space-y-1.5">
            <label className="block text-small text-muted">{t('login.email')}</label>
            <Input
              type="email"
              required
              value={email}
              onChange={e => setEmail(e.target.value)}
              autoComplete="email"
              autoFocus
              invalid={!!error}
            />
          </div>

          <div className="space-y-1.5">
            <label className="block text-small text-muted">{t('login.password')}</label>
            <Input
              type="password"
              required
              value={password}
              onChange={e => setPassword(e.target.value)}
              autoComplete="current-password"
              invalid={!!error}
              error={error || undefined}
            />
          </div>

          <Button
            type="submit"
            variant="primary"
            loading={loading}
            className="w-full"
          >
            {t('login.submit')}
          </Button>

        </form>

        <p className="text-center text-small text-muted mt-5">
          {t('login.noAccount')}{' '}
          <Link to="/signup" className="text-trace-blue hover:underline transition-colors">
            {t('login.signUp')}
          </Link>
        </p>

      </div>
    </div>
  )
}
