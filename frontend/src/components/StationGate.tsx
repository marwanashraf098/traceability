import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Delete } from 'lucide-react'
import {
  getStationRoster, switchPin, getMe, login, getRoleFromToken,
  StationRosterEntry,
} from '../api'
import { setAccessToken, clearAccessToken } from '../auth'
import { useStation } from './StationProvider'
import { Button, Input, cn } from './ui'
import { Logo } from './Logo'

type Step = 'roster' | 'pin' | 'exit'

/**
 * Worker Station Gate — mounted by RequireAuth INSTEAD OF children when
 * stationMode is on and no worker is currently signed in this session. Blocks
 * every scan screen from rendering at all, so it never competes for focus with
 * the SAFETY-CRITICAL scan-input refocus handlers in Fulfill/Returns — those
 * components simply never mount while the gate is up.
 */
export default function StationGate() {
  const { signInWorker, exitStationMode } = useStation()
  const navigate = useNavigate()
  const [step, setStep] = useState<Step>('roster')

  const [roster, setRoster] = useState<StationRosterEntry[]>([])
  const [rosterLoading, setRosterLoading] = useState(true)
  const [rosterError, setRosterError] = useState(false)

  const [selected, setSelected] = useState<StationRosterEntry | null>(null)
  const [pin, setPin] = useState('')
  const [pinStatus, setPinStatus] = useState<'idle' | 'submitting' | 'wrong'>('idle')
  const [lockedUntil, setLockedUntil] = useState<string | null>(null)
  const [shake, setShake] = useState(false)

  const loadRoster = useCallback(async () => {
    setRosterLoading(true)
    setRosterError(false)
    try {
      setRoster(await getStationRoster())
    } catch {
      setRosterError(true)
    } finally {
      setRosterLoading(false)
    }
  }, [])

  useEffect(() => { loadRoster() }, [loadRoster])

  function selectWorker(worker: StationRosterEntry) {
    if (worker.locked) return
    setSelected(worker)
    setPin('')
    setPinStatus('idle')
    setLockedUntil(null)
    setStep('pin')
  }

  function backToRoster() {
    setSelected(null)
    setPin('')
    setPinStatus('idle')
    setLockedUntil(null)
    setStep('roster')
    loadRoster()
  }

  async function submitPin(fullPin: string) {
    if (!selected) return
    setPinStatus('submitting')
    const result = await switchPin(selected.id, fullPin)
    if (result.ok) {
      setAccessToken(result.accessToken)
      try {
        const me = await getMe()
        signInWorker(me)
        navigate('/worker-home')
      } catch {
        // /me failed right after a successful PIN switch — extremely unlikely.
        // Fall back to the roster rather than leaving the gate stuck mid-submit.
        clearAccessToken()
        backToRoster()
      }
      return
    }
    if (result.status === 423) {
      // Re-fetch the roster for this worker's fresh lockedUntil — the 423 response
      // body is a plain message, not structured data (see api.ts switchPin()).
      try {
        const fresh = await getStationRoster()
        const match = fresh.find(w => w.id === selected.id)
        setLockedUntil(match?.lockedUntil ?? null)
      } catch {
        setLockedUntil(null)
      }
      setPin('')
      setPinStatus('idle')
      setStep('pin')
      return
    }
    // Wrong PIN — generic message, no attempt count (C1 scope).
    setPin('')
    setPinStatus('wrong')
    setShake(true)
    setTimeout(() => setShake(false), 400)
  }

  function pressDigit(d: string) {
    if (pinStatus === 'submitting' || lockedUntil) return
    const next = (pin + d).slice(0, 4)
    setPin(next)
    setPinStatus('idle')
    if (next.length === 4) submitPin(next)
  }

  function pressBackspace() {
    if (pinStatus === 'submitting' || lockedUntil) return
    setPin(p => p.slice(0, -1))
  }

  return (
    <div className="min-h-screen bg-bg flex items-center justify-center px-4 py-12">
      <div aria-hidden className="pointer-events-none fixed inset-0 overflow-hidden">
        <div className="absolute top-1/3 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] rounded-full bg-trace-blue/5 blur-3xl" />
      </div>

      <div className="w-full max-w-md relative z-10">
        <div className="text-center mb-8">
          <Logo variant="mark" size={28} className="justify-center mb-4" />
        </div>

        {step === 'roster' && (
          <RosterStep
            roster={roster}
            loading={rosterLoading}
            error={rosterError}
            onRetry={loadRoster}
            onSelect={selectWorker}
            onExit={() => setStep('exit')}
          />
        )}

        {step === 'pin' && selected && (
          <PinStep
            worker={selected}
            pin={pin}
            status={pinStatus}
            lockedUntil={lockedUntil}
            shake={shake}
            onDigit={pressDigit}
            onBackspace={pressBackspace}
            onBack={backToRoster}
          />
        )}

        {step === 'exit' && (
          <ExitStep onCancel={() => setStep('roster')} onExited={exitStationMode} />
        )}
      </div>
    </div>
  )
}

// ── Roster step ─────────────────────────────────────────────────────────────────

function RosterStep({
  roster, loading, error, onRetry, onSelect, onExit,
}: {
  roster: StationRosterEntry[]
  loading: boolean
  error: boolean
  onRetry: () => void
  onSelect: (w: StationRosterEntry) => void
  onExit: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="card p-6">
      <div className="text-center mb-6">
        <h2 className="text-h3 text-primary">{t('station.roster.title')}</h2>
        <p className="text-small text-muted mt-1">{t('station.roster.subtitle')}</p>
      </div>

      {loading && (
        <div className="flex items-center justify-center py-10">
          <div className="w-6 h-6 rounded-full border-2 border-brand/30 border-t-brand animate-spin" />
        </div>
      )}

      {!loading && error && (
        <div className="text-center space-y-3 py-6">
          <p className="text-small text-critical">{t('station.roster.loadError')}</p>
          <Button variant="secondary" onClick={onRetry}>{t('station.roster.retry')}</Button>
        </div>
      )}

      {!loading && !error && (
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
          {roster.map(worker => (
            <button
              key={worker.id}
              type="button"
              onClick={() => onSelect(worker)}
              disabled={worker.locked}
              className="flex flex-col items-center justify-center gap-2 rounded-xl border border-line bg-surface px-3 py-6 text-center transition-colors hover:border-brand/50 hover:bg-black/[0.04] disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:border-line disabled:hover:bg-surface"
            >
              <span className="w-12 h-12 rounded-full bg-trace-blue/15 text-trace-blue font-medium flex items-center justify-center text-h4">
                {initials(worker.name)}
              </span>
              <span className="text-body text-primary leading-tight">{worker.name}</span>
              {worker.locked && (
                <span className="text-caption text-critical">{t('station.roster.locked')}</span>
              )}
            </button>
          ))}
        </div>
      )}

      <button
        type="button"
        onClick={onExit}
        className="w-full text-center text-caption text-muted hover:text-primary transition-colors mt-6"
      >
        {t('station.roster.exit')}
      </button>
    </div>
  )
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/)
  return parts.slice(0, 2).map(p => p[0]?.toUpperCase() ?? '').join('')
}

// ── PIN pad step ─────────────────────────────────────────────────────────────────

function PinStep({
  worker, pin, status, lockedUntil, shake, onDigit, onBackspace, onBack,
}: {
  worker: StationRosterEntry
  pin: string
  status: 'idle' | 'submitting' | 'wrong'
  lockedUntil: string | null
  shake: boolean
  onDigit: (d: string) => void
  onBackspace: () => void
  onBack: () => void
}) {
  const { t } = useTranslation()
  const [, forceTick] = useState(0)

  // Re-render once a minute so the locked countdown stays roughly live.
  useEffect(() => {
    if (!lockedUntil) return
    const id = setInterval(() => forceTick(n => n + 1), 30_000)
    return () => clearInterval(id)
  }, [lockedUntil])

  const minutesLeft = lockedUntil
    ? Math.max(1, Math.ceil((new Date(lockedUntil).getTime() - Date.now()) / 60_000))
    : null

  return (
    <div className="card p-6">
      <div className="text-center mb-6">
        <span className="w-14 h-14 rounded-full bg-trace-blue/15 text-trace-blue font-medium flex items-center justify-center text-h3 mx-auto mb-3">
          {initials(worker.name)}
        </span>
        <h2 className="text-h3 text-primary">{worker.name}</h2>
        {!lockedUntil && <p className="text-small text-muted mt-1">{t('station.pin.subtitle')}</p>}
      </div>

      {lockedUntil ? (
        <div className="text-center space-y-4 py-4">
          <p className="text-body text-critical font-medium">{t('station.pin.locked.title')}</p>
          <p className="text-small text-muted">
            {t('station.pin.locked.body', { minutes: minutesLeft })}
          </p>
        </div>
      ) : (
        <>
          <div className={cn('flex items-center justify-center gap-4 mb-2', shake && 'animate-shake')}>
            {[0, 1, 2, 3].map(i => (
              <span
                key={i}
                className={cn(
                  'w-4 h-4 rounded-full border-2',
                  i < pin.length
                    ? (status === 'wrong' ? 'bg-critical border-critical' : 'bg-brand border-brand')
                    : 'border-line'
                )}
              />
            ))}
          </div>
          <p
            role="alert"
            className={cn(
              'text-center text-small text-critical mb-4 min-h-[1.25rem]',
              status !== 'wrong' && 'invisible'
            )}
          >
            {t('station.pin.wrong')}
          </p>

          <div className="grid grid-cols-3 gap-3 max-w-[280px] mx-auto">
            {['1', '2', '3', '4', '5', '6', '7', '8', '9'].map(d => (
              <button
                key={d}
                type="button"
                onClick={() => onDigit(d)}
                disabled={status === 'submitting'}
                className="aspect-square rounded-xl border border-line bg-surface text-h3 text-primary transition-colors hover:border-brand/50 hover:bg-black/[0.04] disabled:opacity-50"
              >
                {d}
              </button>
            ))}
            <span />
            <button
              type="button"
              onClick={() => onDigit('0')}
              disabled={status === 'submitting'}
              className="aspect-square rounded-xl border border-line bg-surface text-h3 text-primary transition-colors hover:border-brand/50 hover:bg-white/5 disabled:opacity-50"
            >
              0
            </button>
            <button
              type="button"
              onClick={onBackspace}
              disabled={status === 'submitting'}
              aria-label={t('station.pin.backspace')}
              className="aspect-square rounded-xl border border-line bg-surface text-primary flex items-center justify-center transition-colors hover:border-brand/50 hover:bg-black/[0.04] disabled:opacity-50"
            >
              <Delete size={18} strokeWidth={2} />
            </button>
          </div>
        </>
      )}

      <button
        type="button"
        onClick={onBack}
        className="w-full text-center text-caption text-muted hover:text-primary transition-colors mt-6"
      >
        {lockedUntil ? t('station.pin.locked.back') : t('station.pin.back')}
      </button>
    </div>
  )
}

// ── Exit station mode step ────────────────────────────────────────────────────────

function ExitStep({ onCancel, onExited }: { onCancel: () => void; onExited: () => void }) {
  const { t } = useTranslation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const res = await login(email, password)
      setAccessToken(res.accessToken)
      const role = getRoleFromToken()
      if (role !== 'owner' && role !== 'manager') {
        clearAccessToken()
        setError(t('station.exit.workerError'))
        return
      }
      onExited()
    } catch {
      setError(t('station.exit.error'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="card p-6">
      <div className="text-center mb-6">
        <h2 className="text-h3 text-primary">{t('station.exit.title')}</h2>
        <p className="text-small text-muted mt-1">{t('station.exit.subtitle')}</p>
      </div>

      {error && (
        <div role="alert" className="text-small text-critical bg-critical/10 border border-critical/25 rounded-lg px-3 py-2 mb-4">
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-4" noValidate>
        <div className="space-y-1.5">
          <label className="block text-small text-muted">{t('station.exit.email')}</label>
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
          <label className="block text-small text-muted">{t('station.exit.password')}</label>
          <Input
            type="password"
            required
            value={password}
            onChange={e => setPassword(e.target.value)}
            autoComplete="current-password"
            invalid={!!error}
          />
        </div>
        <div className="flex gap-3">
          <Button type="button" variant="secondary" className="flex-1" onClick={onCancel}>
            {t('station.exit.cancel')}
          </Button>
          <Button type="submit" variant="primary" loading={loading} className="flex-1">
            {t('station.exit.submit')}
          </Button>
        </div>
      </form>
    </div>
  )
}
