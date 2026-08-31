import { useState, FormEvent, useEffect } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { login } from '../api'
import { setAccessToken } from '../auth'
import AuthLayout from '../components/AuthLayout'
import { Input, Button } from '../components/ui'
import { useStation } from '../components/StationProvider'

export default function Login() {
  const { t }      = useTranslation()
  const navigate   = useNavigate()
  const location   = useLocation()
  const { exitStationMode } = useStation()
  const [email,        setEmail]        = useState('')
  const [password,     setPassword]     = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error,        setError]        = useState('')
  const [loading,      setLoading]      = useState(false)
  const resetSuccess = !!(location.state as { resetSuccess?: boolean } | null)?.resetSuccess

  // Remove any stale key left from the pre-cookie auth system.
  useEffect(() => { localStorage.removeItem('token') }, [])

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const res = await login(email, password)
      setAccessToken(res.accessToken)
      // A full email+password login is always an owner/manager action (workers
      // sign in via the station PIN gate) — clear any persisted stationMode flag
      // so this device never lands back in the gate instead of the app.
      exitStationMode()
      navigate('/overview')
    } catch {
      setError(t('login.error'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout>
      <div className="card p-6">
        <div className="mb-5">
          <h2 className="text-h3 text-primary">{t('login.cardTitle')}</h2>
          <p className="text-small text-muted mt-1">{t('login.cardSubtitle')}</p>
        </div>

        {resetSuccess && !error && (
          <div role="status" className="text-small text-success bg-success/10 border border-success/25 rounded-lg px-3 py-2 mb-4">
            {t('login.resetSuccess')}
          </div>
        )}

        {error && (
          <div role="alert" className="text-small text-critical bg-critical/10 border border-critical/25 rounded-lg px-3 py-2 mb-4">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
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
            <div className="flex items-center justify-between">
              <label className="block text-small text-muted">{t('login.password')}</label>
              <Link to="/forgot-password" className="text-caption text-trace-blue hover:underline transition-colors">
                {t('login.forgotPassword')}
              </Link>
            </div>
            <Input
              type={showPassword ? 'text' : 'password'}
              required
              value={password}
              onChange={e => setPassword(e.target.value)}
              autoComplete="current-password"
              invalid={!!error}
              error={error || undefined}
              endAdornment={
                <button
                  type="button"
                  onClick={() => setShowPassword(v => !v)}
                  className="text-caption font-medium text-muted hover:text-primary transition-colors px-2 py-1"
                >
                  {showPassword ? t('common.hidePassword') : t('common.showPassword')}
                </button>
              }
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
      </div>

      <p className="text-center text-small text-muted mt-5">
        {t('login.noAccount')}{' '}
        <Link to="/signup" className="text-trace-blue hover:underline transition-colors">
          {t('login.signUp')}
        </Link>
      </p>
    </AuthLayout>
  )
}
