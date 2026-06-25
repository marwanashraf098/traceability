import { useState, FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { login } from '../api'
import { Logo } from '../components/Logo'

export default function Login() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [email, setEmail]       = useState('')
  const [password, setPassword] = useState('')
  const [error, setError]       = useState('')
  const [loading, setLoading]   = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const res = await login(email, password)
      localStorage.setItem('token', res.accessToken)
      navigate('/overview')
    } catch {
      setError(t('login.error'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-base flex items-center justify-center px-4">
      {/* Background glow */}
      <div
        aria-hidden
        className="pointer-events-none fixed inset-0 overflow-hidden"
      >
        <div className="absolute top-1/3 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] rounded-full bg-brand/5 blur-3xl" />
      </div>

      <div className="w-full max-w-sm relative z-10">
        {/* Wordmark */}
        <div className="text-center mb-10">
          <Logo variant="icon" size={56} className="mb-5" />
          <h1 className="text-display font-light text-primary tracking-tight">
            <span className="text-brand">tr</span>aced
          </h1>
          <p className="text-small text-muted mt-2 tracking-wide">{t('login.subtitle')}</p>
        </div>

        {/* Form card */}
        <div className="card p-6 space-y-4">
          {error && (
            <div className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2">
              {error}
            </div>
          )}
          <div>
            <label className="block text-small text-muted mb-1.5">
              {t('login.email')}
            </label>
            <input
              type="email"
              required
              value={email}
              onChange={e => setEmail(e.target.value)}
              className="input"
              autoComplete="email"
              autoFocus
            />
          </div>
          <div>
            <label className="block text-small text-muted mb-1.5">
              {t('login.password')}
            </label>
            <input
              type="password"
              required
              value={password}
              onChange={e => setPassword(e.target.value)}
              className="input"
              autoComplete="current-password"
            />
          </div>
          <button
            type="submit"
            onClick={handleSubmit}
            disabled={loading}
            className="btn-brand btn w-full py-2.5"
          >
            {loading ? t('common.loading') : t('login.submit')}
          </button>
        </div>

        <p className="text-center text-small text-muted mt-5">
          {t('login.noAccount')}{' '}
          <Link to="/signup" className="text-brand hover:text-brand-hover transition-colors">
            {t('login.signUp')}
          </Link>
        </p>
      </div>
    </div>
  )
}
