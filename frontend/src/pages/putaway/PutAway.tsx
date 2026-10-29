import { useEffect, useState } from 'react'
import { useNavigate, Navigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ScanLine } from 'lucide-react'
import {
  closePutAway, getOpenPutAwaySessions, getPutAwaySummary, getPutAwayWaiting, getRoleFromToken, startPutAway,
  PutAwayOpenSession, PutAwaySource, PutAwaySummary, PutAwayWaiting, ServerMessageError,
} from '../../api'
import { Alert, Button, DataTable, EmptyState, Modal, Select, TableSkeleton, cn, useToast, type DataTableColumn } from '../../components/ui'
import { resetPutAwayWaitingCache } from './usePutAwayWaiting'

// Warehouse places S2 — Put away, the WAITING LIST (in-shell; the scan loop is /put-away/:id/scan, full-screen).
// Pieces at a location that have no shelf or box yet: counts per source, a filter, each piece's suggested place
// (its last place, else where most of its product sits). "Start put away" opens the caller's session — or "Resume" when
// they already have one open here (one open session per person per location). Owner / manager: "Open sessions" —
// who, where, since when, placed — and Close for any of them (pieces stay placed).

const SOURCES: PutAwaySource[] = ['receiving', 'returns', 'transfers', 'stock_take', 'other']

/** "45 min" / "5 h" / "3 days" since the piece started waiting. */
export function waitingFor(since: string, t: (k: string, o?: Record<string, unknown>) => string, now = Date.now()): string {
  const minutes = Math.max(0, Math.floor((now - new Date(since).getTime()) / 60_000))
  if (minutes < 60) return t('putAway.waitingMinutes', { count: Math.max(1, minutes) })
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return t('putAway.waitingHours', { count: hours })
  return t('putAway.waitingDays', { count: Math.floor(hours / 24) })
}

type Row = PutAwayWaiting['rows'][number] & { id: string }

export default function PutAway() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [summary, setSummary] = useState<PutAwaySummary | null>(null)
  const [locationId, setLocationId] = useState<string | undefined>(undefined)
  const [source, setSource] = useState<PutAwaySource | 'all'>('all')
  const [data, setData] = useState<PutAwayWaiting | null>(null)
  const [loading, setLoading] = useState(true)
  const [missing, setMissing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)
  const manager = ['owner', 'manager'].includes(getRoleFromToken() ?? '')
  const [open, setOpen] = useState<PutAwayOpenSession[] | null>(null)
  const [closing, setClosing] = useState<PutAwayOpenSession | null>(null)
  const [closeBusy, setCloseBusy] = useState(false)
  const [reload, setReload] = useState(0)
  const { toast } = useToast()

  const readable = (e: unknown) => e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('putAway.error')

  useEffect(() => {
    getPutAwaySummary().then(setSummary).catch(e => {
      if (e instanceof ServerMessageError && e.status === 404) setMissing(true)
    })
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    getPutAwayWaiting(locationId, source)
      .then(d => { if (!cancelled) { setData(d); setError(null) } })
      .catch(e => {
        if (cancelled) return
        if (e instanceof ServerMessageError && e.status === 404) setMissing(true)
        else setError(readable(e))
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locationId, source, reload])

  useEffect(() => {
    if (!manager) return
    getOpenPutAwaySessions().then(setOpen).catch(() => setOpen([]))
  }, [manager, reload])

  async function closeSession() {
    if (!closing) return
    setCloseBusy(true)
    try {
      await closePutAway(closing.id)
      resetPutAwayWaitingCache()
      toast({ tone: 'success', message: t('putAway.open.closed', { name: closing.openedByName ?? '' }) })
      setClosing(null)
      setReload(n => n + 1)
    } catch (e: unknown) {
      toast({ tone: 'error', message: readable(e) })
    } finally {
      setCloseBusy(false)
    }
  }

  // No places for this tenant: the screen doesn't exist.
  if (missing) return <Navigate to="/" replace />

  async function start() {
    setStarting(true)
    setError(null)
    try {
      const s = await startPutAway(data?.locationId ?? locationId)
      navigate(`/put-away/${s.id}/scan`)
    } catch (e: unknown) {
      setError(readable(e))
      setStarting(false)
    }
  }

  const counts = data?.counts
  const rows: Row[] = (data?.rows ?? []).map(r => ({ ...r, id: r.pieceId }))
  const columns: DataTableColumn<Row>[] = [
    { key: 'piece', header: t('putAway.col.piece'), mono: true,
      render: r => <span dir="ltr" data-testid={`put-away-row-${r.code}`}>{r.code}</span> },
    { key: 'product', header: t('putAway.col.product'),
      render: r => (
        <span className="text-primary">
          <bdi>{r.productTitle}</bdi>
          {r.variantTitle && r.variantTitle !== 'Default Title' && <span className="text-muted"> · <bdi>{r.variantTitle}</bdi></span>}
        </span>
      ) },
    { key: 'from', header: t('putAway.col.from'),
      render: r => (
        <span className="text-secondary">
          {t(`putAway.source.${r.source}`)}
          {r.sourceRef && <span className="text-muted"> · <bdi>{r.sourceRef}</bdi></span>}
        </span>
      ) },
    { key: 'waiting', header: t('putAway.col.waiting'),
      render: r => <span className="text-muted tabular-nums">{waitingFor(r.waitingSince, t)}</span> },
    { key: 'suggest', header: t('putAway.col.suggested'),
      render: r => r.suggestion ? (
        <span className="text-trace-blue">
          <span className="font-mono" dir="ltr">{r.suggestion.place.code}</span>
          {r.suggestion.reason === 'last_place' && <span className="text-muted"> ({t('putAway.lastPlace')})</span>}
        </span>
      ) : <span className="text-muted">{t('putAway.noSuggestion')}</span> },
  ]

  const locations = summary?.locations ?? []

  return (
    <div className="space-y-5" data-testid="put-away-page">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="space-y-1">
          <h1 className="text-h1 text-primary">{t('putAway.title')}</h1>
          <p className="text-body text-muted">{t('putAway.subtitle')}</p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          {locations.length > 1 && (
            <Select
              value={data?.locationId ?? ''}
              onChange={v => setLocationId(v || undefined)}
              options={locations.map(l => ({ value: l.id, label: `${l.name} · ${l.waiting}` }))}
            />
          )}
          <Button size="lg" loading={starting} disabled={!data?.hasPlaces} onClick={start}>
            <ScanLine size={18} strokeWidth={2} />
            {data?.mine ? t('putAway.resume', { count: data.mine.placed }) : t('putAway.start')}
          </Button>
        </div>
      </div>

      {error && <Alert tone="critical" title={error} />}

      {data && !data.hasPlaces && (
        <Alert tone="info" title={t('putAway.noPlacesTitle')}>{t('putAway.noPlacesBody')}</Alert>
      )}

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3" data-testid="put-away-counts">
        <div className="rounded-xl border border-warning/40 bg-warning/[0.08] p-4">
          <p className="text-h2 font-bold text-warning-text tabular-nums" data-testid="put-away-total">{data?.total ?? '–'}</p>
          <p className="text-small text-warning-text">{t('putAway.countWaiting')}</p>
        </div>
        <div className="card p-4">
          <p className="text-h2 font-bold text-primary tabular-nums">{counts?.receiving ?? '–'}</p>
          <p className="text-small text-muted">{t('putAway.countFrom.receiving')}</p>
        </div>
        <div className="card p-4">
          <p className="text-h2 font-bold text-primary tabular-nums">{counts?.returns ?? '–'}</p>
          <p className="text-small text-muted">{t('putAway.countFrom.returns')}</p>
        </div>
        <div className="card p-4">
          <p className="text-h2 font-bold text-primary tabular-nums">
            {counts ? counts.transfers + counts.stock_take + counts.other : '–'}
          </p>
          <p className="text-small text-muted">{t('putAway.countFrom.rest')}</p>
        </div>
      </div>

      <div className="card overflow-hidden">
        <div className="flex flex-wrap gap-1.5 px-4 py-3 border-b border-line" role="group" aria-label={t('putAway.filter')}>
          {(['all', ...SOURCES] as const).map(s => {
            const n = s === 'all' ? data?.total : counts?.[s]
            if (s !== 'all' && !n && source !== s) return null
            const active = source === s
            return (
              <button key={s} type="button" aria-pressed={active} data-testid={`put-away-filter-${s}`}
                onClick={() => setSource(s)}
                className={cn('h-8 px-3 rounded-full text-small transition-colors',
                  active ? 'bg-primary text-surface font-semibold' : 'border border-line bg-surface text-secondary hover:bg-elevated')}>
                {t(s === 'all' ? 'putAway.filterAll' : `putAway.source.${s}`)} · {n ?? 0}
              </button>
            )
          })}
        </div>
        {loading && !data ? <TableSkeleton rows={6} cols={5} /> : rows.length === 0 ? (
          <EmptyState message={t('putAway.emptyTitle')} icon="✓" />
        ) : (
          <DataTable columns={columns} rows={rows} />
        )}
        {data?.truncated && (
          <p className="px-4 py-3 border-t border-line text-small text-muted">{t('putAway.truncated', { count: rows.length })}</p>
        )}
      </div>

      <p className="text-small text-muted">{t('putAway.suggestionNote')}</p>

      {manager && open && open.length > 0 && (
        <div className="card overflow-hidden" data-testid="put-away-open-sessions">
          <div className="px-4 py-3 border-b border-line">
            <p className="text-body font-semibold text-primary">{t('putAway.open.title')}</p>
            <p className="text-small text-muted">{t('putAway.open.note', { hours: 12 })}</p>
          </div>
          <ul className="divide-y divide-line">
            {open.map(o => (
              <li key={o.id} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3" data-testid={`put-away-open-${o.id}`}>
                <span className="min-w-0">
                  <span className="block text-body text-primary"><bdi>{o.openedByName ?? t('putAway.open.someone')}</bdi>
                    <span className="text-muted"> · <bdi>{o.locationName}</bdi></span></span>
                  <span className="block text-small text-muted">
                    {t('putAway.open.meta', { count: o.placed, since: waitingFor(o.createdAt, t), last: waitingFor(o.lastActivityAt, t) })}
                  </span>
                </span>
                <Button variant="secondary" size="sm" onClick={() => setClosing(o)}>{t('putAway.open.close')}</Button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {closing && (
        <Modal title={t('putAway.open.confirmTitle')} onClose={() => { if (!closeBusy) setClosing(null) }}>
          <div className="space-y-4">
            <p className="text-body text-primary">
              {t('putAway.open.confirmBody', { name: closing.openedByName ?? t('putAway.open.someone'), count: closing.placed })}
            </p>
            <div className="flex gap-2 justify-end">
              <Button variant="secondary" disabled={closeBusy} onClick={() => setClosing(null)}>{t('common.cancel')}</Button>
              <Button loading={closeBusy} onClick={closeSession}>{t('putAway.open.close')}</Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

