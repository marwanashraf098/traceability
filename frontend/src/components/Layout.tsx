import { NavLink, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useState, useRef, ReactNode } from 'react'

// ── Inline SVG icons ──────────────────────────────────────────────────────────

function IconOverview()   { return <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.75" viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg> }
function IconOrders()     { return <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.75" viewBox="0 0 24 24"><path d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"/></svg> }
function IconInventory()  { return <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.75" viewBox="0 0 24 24"><path d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4"/></svg> }
function IconReceiving()  { return <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.75" viewBox="0 0 24 24"><path d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"/></svg> }
function IconFulfill()    { return <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.75" viewBox="0 0 24 24"><path d="M4 6h16M4 10h16M4 14h16M4 18h7"/><path d="M15 14l2 2 4-4"/></svg> }
function IconReturns()    { return <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.75" viewBox="0 0 24 24"><path d="M3 10h10a8 8 0 018 8v2M3 10l6 6m-6-6l6-6"/></svg> }
function IconExceptions() { return <svg width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.75" viewBox="0 0 24 24"><path d="M12 9v4m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/></svg> }
function IconSearch()     { return <svg width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/></svg> }
function IconLogout()     { return <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.75" viewBox="0 0 24 24"><path d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"/></svg> }

// ── Nav link helper ───────────────────────────────────────────────────────────

function SideNavLink({ to, icon, label }: { to: string; icon: ReactNode; label: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        isActive ? 'nav-item-active' : 'nav-item'
      }
    >
      {icon}
      <span>{label}</span>
    </NavLink>
  )
}

// ── Layout ────────────────────────────────────────────────────────────────────

export default function Layout({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [searchQ, setSearchQ] = useState('')
  const searchRef = useRef<HTMLInputElement>(null)

  function logout() {
    localStorage.removeItem('token')
    navigate('/login')
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

  return (
    <div className="flex h-screen bg-base overflow-hidden">
      {/* ── Sidebar ── */}
      <aside className="w-56 flex-shrink-0 bg-panel border-e border-line flex flex-col">
        {/* Wordmark / logo slot */}
        <div className="flex items-center gap-2.5 px-5 py-5 border-b border-line">
          {/* Icon mark slot — replace this div with the real SVG when ready */}
          <div className="w-8 h-8 rounded-lg bg-brand/15 border border-brand/25 flex items-center justify-center flex-shrink-0">
            <span className="text-brand text-xs font-bold">T</span>
          </div>
          <span className="text-primary font-light text-xl tracking-tight select-none">
            <span className="text-brand">tr</span>aced
          </span>
        </div>

        {/* Nav links */}
        <nav className="flex-1 overflow-y-auto px-3 py-3 space-y-0.5">
          <SideNavLink to="/overview"   icon={<IconOverview />}   label={t('nav.overview')} />
          <SideNavLink to="/orders"     icon={<IconOrders />}     label={t('nav.orders')} />
          <SideNavLink to="/catalog"    icon={<IconInventory />}  label={t('nav.catalog')} />
          <SideNavLink to="/receiving"  icon={<IconReceiving />}  label={t('nav.receiving')} />
          <SideNavLink to="/fulfill"    icon={<IconFulfill />}    label={t('nav.fulfill')} />
          <SideNavLink to="/returns"    icon={<IconReturns />}    label={t('nav.returns')} />
          <SideNavLink to="/exceptions" icon={<IconExceptions />} label={t('nav.exceptions')} />
        </nav>

        {/* Bottom: lang toggle + logout */}
        <div className="px-3 py-3 border-t border-line space-y-1">
          <button
            onClick={toggleLang}
            className="nav-item w-full text-start"
          >
            <span className="text-base">🌐</span>
            <span>{i18n.language === 'en' ? 'العربية' : 'English'}</span>
          </button>
          <button
            onClick={logout}
            className="nav-item w-full text-start text-danger hover:text-danger"
          >
            <IconLogout />
            <span>{t('nav.logout')}</span>
          </button>
        </div>
      </aside>

      {/* ── Main area ── */}
      <div className="flex-1 flex flex-col overflow-hidden min-w-0">
        {/* Top bar: search + header context */}
        <header className="h-14 border-b border-line bg-panel/50 backdrop-blur flex items-center px-6 gap-4 flex-shrink-0">
          <form onSubmit={handleSearch} className="flex items-center gap-2 flex-1 max-w-sm">
            <div className="relative flex-1">
              <span className="absolute start-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none">
                <IconSearch />
              </span>
              <input
                ref={searchRef}
                type="text"
                value={searchQ}
                onChange={e => setSearchQ(e.target.value)}
                placeholder={t('nav.lookup')}
                className="input ps-9 text-small py-1.5"
              />
            </div>
          </form>
        </header>

        {/* Page content */}
        <main className="flex-1 overflow-y-auto p-6">
          {children}
        </main>
      </div>
    </div>
  )
}
