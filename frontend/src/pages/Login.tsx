import { useState, FormEvent, useEffect } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { login, refreshAccessToken } from '../api'
import { setAccessToken } from '../auth'
import AuthLayout from '../components/AuthLayout'
import { Input, Button } from '../components/ui'
import { DEMO_SESSION_MARKER, DEMO_ACCESS_TOKEN_KEY } from '../demoConstants'
import { returnPath } from './loginReturnPath'

export default function Login() {
  const { t }      = useTranslation()
  const navigate   = useNavigate()
  const location   = useLocation()
  const [email,        setEmail]        = useState('')
  const [password,     setPassword]     = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error,        setError]        = useState('')
  const [loading,      setLoading]      = useState(false)
  const resetSuccess = !!(location.state as { resetSuccess?: boolean } | null)?.resetSuccess

  // Remove any stale key left from the pre-cookie auth system.
  useEffect(() => { localStorage.removeItem('token') }, [])

  // Silent refresh first: a device bounced here while its refresh cookie is still good (a lost
  // rotation, a second tab, a one-off 401) goes straight back in — a station tablet lands on its
  // PIN gate again instead of waiting for someone's password. A real logout cleared the cookie,
  // so this 401s and the form below stays. The form renders meanwhile (no spinner).
  useEffect(() => {
    let cancelled = false
    refreshAccessToken()
      .then(token => {
        if (!cancelled && token) navigate(returnPath(location.state) ?? '/overview', { replace: true })
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const res = await login(email, password)
      setAccessToken(res.accessToken)
      // A real login means this is no longer (if it ever was) a demo visitor —
      // clear the marker AND any stashed demo token so a later session loss
      // bounces to /login, not /demo, and no stale demo token lingers.
      sessionStorage.removeItem(DEMO_SESSION_MARKER)
      sessionStorage.removeItem(DEMO_ACCESS_TOKEN_KEY)
      // Station mode is NOT cleared here: a station tablet that bounced to /login stays a
      // station (RequireAuth shows its PIN gate). Station mode ends only through the gate's
      // Exit step (StationGate ExitStep, owner/manager password).
      navigate(returnPath(location.state) ?? '/overview')
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
