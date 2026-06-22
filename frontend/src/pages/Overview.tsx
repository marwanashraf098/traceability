import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { getInventorySummary, InventorySummary, request } from '../api'
import { SeverityBadge, Spinner } from '../components/ui'

// ── Inventory tile ────────────────────────────────────────────────────────────

function InventoryTile({
  status,
  count,
  windowed,
  loading,
}: {
  status: string
  count: number
  windowed: boolean
  loading: boolean
}) {
  const { t } = useTranslation()
  const dest = `/inventory?status=${status}${windowed ? '&within30d=true' : ''}`

  return (
    <Link
      to={dest}
      data-testid={`tile-${status}`}
      className="card p-4 flex flex-col gap-2 hover:bg-elevated transition-colors group"
    >
      <p className="text-caption text-muted uppercase tracking-wider">
        {t(`catalog.statuses.${status}`, status)}
      </p>
      {loading ? (
        <div className="h-8 w-12 bg-elevated rounded animate-pulse" />
      ) : (
        <p className="text-display font-light text-primary group-hover:text-brand transition-colors">
          {count.toLocaleString()}
        </p>
      )}
    </Link>
  )
}

// ── Section header ────────────────────────────────────────────────────────────

function SectionHeader({ title, note, badge }: { title: string; note: string; badge?: string }) {
  return (
    <div className="flex items-center gap-3 mb-3">
      <div>
        <h2 className="text-h3 text-primary inline-flex items-center gap-2">
          {title}
          {badge && (
            <span className="text-caption font-medium text-brand bg-brand/10 border border-brand/20 rounded-full px-2 py-0.5">
              {badge}
            </span>
          )}
        </h2>
        <p className="text-caption text-muted mt-0.5">{note}</p>
      </div>
    </div>
  )
}

// ── Exception snippet ─────────────────────────────────────────────────────────

interface ExceptionItem {
  type: string
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'
  descriptionEn: string
  descriptionAr: string
  actionUrl: string
}

// ── Root ──────────────────────────────────────────────────────────────────────

export default function Overview() {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'

  const [summary, setSummary]     = useState<InventorySummary | null>(null)
  const [invLoading, setInvLoading] = useState(true)
  const [invError,   setInvError]   = useState('')

  const [exceptions, setExceptions]   = useState<ExceptionItem[]>([])
  const [excLoading, setExcLoading]   = useState(true)

  useEffect(() => {
    getInventorySummary()
      .then(s => setSummary(s))
      .catch(() => setInvError(t('common.error')))
      .finally(() => setInvLoading(false))
  }, [t])

  useEffect(() => {
    request<{ items: ExceptionItem[] }>('/exceptions?size=5')
      .then(r => setExceptions(r.items))
      .catch(() => {})
      .finally(() => setExcLoading(false))
  }, [])

  const groupA = summary?.groupA ?? []
  const groupB = summary?.groupB ?? []

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div>
        <h1 className="text-h1 text-primary">{t('overview.title')}</h1>
        <p className="text-small text-muted mt-0.5">nothing moves untraced</p>
      </div>

      {/* ── Inventory error ── */}
      {invError && (
        <div
          role="alert"
          data-testid="inventory-error"
          className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2"
        >
          {invError}
        </div>
      )}

      {/* ── Group A: live inventory ── */}
      {!invError && (
        <div>
          <SectionHeader
            title={t('inventory.live')}
            note={t('inventory.liveNote')}
          />
          <div
            data-testid="group-a"
            className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3"
          >
            {invLoading
              ? Array.from({ length: 6 }).map((_, i) => (
                  <InventoryTile key={i} status="" count={0} windowed={false} loading />
                ))
              : groupA.map(({ status, count }) => (
                  <InventoryTile key={status} status={status} count={count} windowed={false} loading={false} />
                ))}
          </div>
        </div>
      )}

      {/* ── Group B: last 30 days ── */}
      {!invError && (
        <div>
          <SectionHeader
            title={t('inventory.window30d')}
            note={t('inventory.window30dNote')}
            badge="30d"
          />
          <div
            data-testid="group-b"
            className="grid grid-cols-2 sm:grid-cols-3 gap-3"
          >
            {invLoading
              ? Array.from({ length: 3 }).map((_, i) => (
                  <InventoryTile key={i} status="" count={0} windowed={true} loading />
                ))
              : groupB.map(({ status, count }) => (
                  <InventoryTile key={status} status={status} count={count} windowed={true} loading={false} />
                ))}
          </div>
        </div>
      )}

      {/* ── Recent exceptions ── */}
      <div className="card p-5">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-h3 text-primary">{t('overview.recentExceptions')}</h2>
          <Link to="/exceptions" className="text-small text-brand hover:text-brand-hover transition-colors">
            {t('overview.viewAll')} →
          </Link>
        </div>

        {excLoading ? (
          <div className="flex justify-center py-8"><Spinner /></div>
        ) : exceptions.length === 0 ? (
          <div className="flex flex-col items-center py-8 gap-2 text-muted">
            <span className="text-2xl opacity-40">✓</span>
            <p className="text-body">{t('overview.noExceptions')}</p>
          </div>
        ) : (
          <div className="divide-y divide-line">
            {exceptions.map((exc, i) => (
              <div key={i} className="flex items-start justify-between gap-4 py-3">
                <div className="flex items-start gap-3 min-w-0">
                  <SeverityBadge severity={exc.severity} />
                  <p className="text-body text-primary truncate">
                    {isAr ? exc.descriptionAr : exc.descriptionEn}
                  </p>
                </div>
                <Link
                  to={exc.actionUrl}
                  className="text-small text-brand hover:text-brand-hover whitespace-nowrap shrink-0 transition-colors"
                >
                  View →
                </Link>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
