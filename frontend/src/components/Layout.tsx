import { useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useState, useRef, useEffect, ReactNode } from 'react'
import { LogOut, Globe, Search, ChevronDown, Bell, Menu } from 'lucide-react'
import {
  getRoleFromToken, logoutThisDevice, getMe, getExceptionsCount, getOnboardingStatus,
  type Me, type OnboardingStatus,
} from '../api'
import { clearAccessToken } from '../auth'
import Sidebar, { avatarInitials } from './Sidebar'
import { cn, MeProvider } from './ui'
import { useStation } from './StationProvider'
import { PhoneTopbarIcon } from '../phone/PhoneScanButton'
import { stationDeviceId } from '../phone/PhoneScanProvider'

// ── Layout ────────────────────────────────────────────────────────────────────

export default function Layout({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation()
  const navigate    = useNavigate()
  const { pathname } = useLocation()
  // Below 900 px the sidebar is a slide-in menu (the top-bar button opens it); at 900 px and up it
  // is the fixed sidebar (collapsible to an icon rail, see Sidebar.tsx). Any navigation, Escape or
  // the scrim closes the menu.
  const [navOpen, setNavOpen] = useState(false)
  useEffect(() => { setNavOpen(false) }, [pathname])
  useEffect(() => {
    if (!navOpen) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setNavOpen(false) }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [navOpen])
  const [searchQ, setSearchQ]   = useState('')
  const [menuOpen, setMenuOpen] = useState(false)
  const [me, setMe]             = useState<Me | null>(null)
  const [exceptionsCount, setExceptionsCount] = useState<number | null>(null)
  const [onboarding, setOnboarding] = useState<OnboardingStatus | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const menuRef   = useRef<HTMLDivElement>(null)
  const role      = getRoleFromToken()
  const { stationMode, signOutWorker } = useStation()

  // A worker's control hands the station to the next worker rather than logging
  // the whole device out — the refresh cookie and stationMode stay untouched.
  // signOutWorker() clears currentWorker in-memory only; RequireAuth reacts to
  // that and re-renders <StationGate/> in place. Owner/manager (or a device not
  // in station mode) keep the real full logout, unchanged.
  const isWorkerAtStation = role === 'worker' && stationMode

  async function logout() {
    // This device only — a station tablet running on the same account keeps working.
    let deviceId: string | undefined
    try { deviceId = stationDeviceId() } catch { deviceId = undefined }
    try { await logoutThisDevice(deviceId) } catch { /* ignore */ }
    clearAccessToken()
    navigate('/login')
  }

  function logoutControl() {
    setMenuOpen(false)
    if (isWorkerAtStation) {
      signOutWorker()
    } else {
      logout()
    }
  }

  function handleSearch(e: React.FormEvent) {
    e.preventDefault()
    const q = searchQ.trim()
    if (!q) return
    navigate(`/lookup?q=${encodeURIComponent(q)}`)
    setSearchQ('')
    searchRef.current?.blur()
  }

  function toggleLang() {
    const next = i18n.language === 'en' ? 'ar' : 'en'
    i18n.changeLanguage(next)
    localStorage.setItem('lang', next)
    document.documentElement.dir  = next === 'ar' ? 'rtl' : 'ltr'
    document.documentElement.lang = next
  }

  // ⌘K / Ctrl+K focuses the topbar search — visual hint chip only otherwise.
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        searchRef.current?.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [])

  // User-menu dropdown: click-outside to close.
  useEffect(() => {
    function onOutside(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false)
    }
    if (menuOpen) document.addEventListener('mousedown', onOutside)
    return () => document.removeEventListener('mousedown', onOutside)
  }, [menuOpen])

  // Shell identity — decorative, non-blocking: on failure, keep the role-only
  // placeholder from the Shell pass rather than surfacing an error.
  useEffect(() => {
    getMe().then(setMe).catch(err => console.error('Failed to load /me', err))
  }, [])

  // Open-exceptions count for the notification bell — decorative, non-blocking:
  // on failure the bell renders with no badge (never NaN/stale), never throws
  // into the shell. Restricted to Owner/Manager, matching the backend endpoint's
  // access control (workers get 403 on /exceptions and /exceptions/count).
  useEffect(() => {
    if (role === 'worker') return
    getExceptionsCount()
      .then(r => setExceptionsCount(r.count))
      .catch(err => console.error('Failed to load exceptions count', err))
  }, [role])

  // Setup N/5 chip — decorative, non-blocking: on failure the chip just doesn't
  // render (never a stale/NaN count). Owner/Manager only, matching the endpoint's
  // access control (workers get 403 on /onboarding/status).
  useEffect(() => {
    if (role === 'worker') return
    getOnboardingStatus()
      .then(setOnboarding)
      .catch(err => console.error('Failed to load onboarding status', err))
  }, [role])

  return (
    <MeProvider me={me}>
    <div className="flex h-screen bg-bg overflow-hidden">

      {/* ── Sidebar ── */}
      {navOpen && (
        <div className="min-[900px]:hidden fixed inset-0 bg-black/45 z-overlay" onClick={() => setNavOpen(false)} data-testid="nav-scrim" />
      )}
      <Sidebar role={role} me={me} navOpen={navOpen} onCloseNav={() => setNavOpen(false)} />

      {/* ── Main area ── */}
      <div className="flex-1 flex flex-col overflow-hidden min-w-0">

        {/* Top bar */}
        <header className="h-14 border-b border-line flex items-center justify-between px-[18px] max-[899px]:px-3 gap-4 max-[899px]:gap-2.5 flex-shrink-0">
          <button type="button" onClick={() => setNavOpen(true)} aria-label={t('nav.openMenu')}
            aria-expanded={navOpen} aria-controls="app-nav" data-testid="nav-menu-button"
            className="min-[900px]:hidden flex-shrink-0 w-9 h-9 -ms-1 rounded-lg flex items-center justify-center text-primary hover:bg-elevated">
            <Menu size={20} strokeWidth={1.75} />
          </button>
          <form onSubmit={handleSearch} className="flex-1 min-w-0 max-w-[360px]">
            <div className="relative">
              <span className="absolute start-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none">
                <Search size={14} strokeWidth={2} />
              </span>
              <input
                ref={searchRef}
                type="text"
                value={searchQ}
                onChange={e => setSearchQ(e.target.value)}
                placeholder={t('nav.lookup')}
                className="input ps-9 pe-12 text-small py-1.5 w-full"
              />
              <span className="absolute end-3 top-1/2 -translate-y-1/2 text-[11px] font-mono text-muted bg-charcoal border border-line rounded-[5px] px-[5px] py-[1px] pointer-events-none">
                ⌘K
              </span>
            </div>
          </form>

          <div className="flex items-center gap-4 flex-shrink-0">
            {/* Phone as scanner — only while a phone is paired to this tablet: status + Unpair. */}
            <PhoneTopbarIcon />
            {/* Notification bell — count = open exceptions, our single "needs attention"
                surface. No badge on the Exceptions nav item — one number, one place.
                Owner/Manager only, matching the backend endpoint's access control. */}
            {role !== 'worker' && (
              <button
                type="button"
                onClick={() => navigate('/exceptions')}
                className="relative text-muted hover:text-primary transition-colors"
              >
                <Bell size={17} strokeWidth={1.75} />
                {!!exceptionsCount && (
                  <span className="absolute -top-1 -end-1.5 bg-critical text-white text-[9px] font-bold rounded-full px-[4px] leading-[1.2] font-mono">
                    {exceptionsCount > 99 ? '99+' : exceptionsCount}
                  </span>
                )}
              </button>
            )}

            {/* User menu — avatar trigger, dropdown shows real identity + hosts lang toggle + logout */}
            <div ref={menuRef} className="relative">
              <button
                type="button"
                onClick={() => setMenuOpen(o => !o)}
                className="flex items-center gap-1.5"
              >
                <span className="w-[26px] h-[26px] rounded-full bg-trace-blue text-white text-[11px] font-bold flex items-center justify-center">
                  {avatarInitials(me, role)}
                </span>
                <ChevronDown
                  size={13}
                  strokeWidth={2}
                  className={cn('text-muted transition-transform', menuOpen && 'rotate-180')}
                />
              </button>

              {menuOpen && (
                <div className="absolute end-0 top-full mt-2 w-48 bg-surface border border-line rounded-lg shadow-e3 overflow-hidden z-dropdown py-1">
                  {me && (
                    <div className="px-3 py-2 border-b border-line min-w-0">
                      <p className="text-small text-primary truncate">{me.name}</p>
                      {me.email && <p className="text-caption text-muted truncate">{me.email}</p>}
                    </div>
                  )}
                  {onboarding && !onboarding.allDone && (
                    <button
                      type="button"
                      onClick={() => { setMenuOpen(false); navigate('/overview') }}
                      className="w-full flex items-center justify-between px-3 py-2 text-caption text-start hover:bg-black/[0.04] transition-colors border-b border-line"
                    >
                      <span className="text-muted">{t('nav.setupProgress')}</span>
                      <span className="font-bold text-trace-blue bg-trace-blue/15 rounded-full px-2 py-0.5">
                        {t('nav.setupCount', { done: onboarding.steps.filter(s => s.done).length, total: onboarding.steps.length })}
                      </span>
                    </button>
                  )}
                  <button
                    type="button"
                    onClick={() => { toggleLang(); setMenuOpen(false) }}
                    className="w-full flex items-center gap-2.5 px-3 py-2 text-body text-start text-muted hover:bg-black/[0.04] hover:text-primary transition-colors"
                  >
                    <Globe size={16} strokeWidth={1.75} />
                    {i18n.language === 'en' ? 'العربية' : 'English'}
                  </button>
                  <button
                    type="button"
                    onClick={logoutControl}
                    className="w-full flex items-center gap-2.5 px-3 py-2 text-body text-start text-danger hover:bg-danger/10 transition-colors"
                  >
                    <LogOut size={16} strokeWidth={1.75} />
                    {isWorkerAtStation ? t('nav.switchLock') : t('nav.logout')}
                  </button>
                </div>
              )}
            </div>
          </div>
        </header>

        {/* Page content */}
        <main className="flex-1 overflow-y-auto p-6 max-[899px]:p-4">
          {children}
        </main>
      </div>
    </div>
    </MeProvider>
  )
}
