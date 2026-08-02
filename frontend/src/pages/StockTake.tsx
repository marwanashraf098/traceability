import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Badge, Button, EmptyState, Input, TableSkeleton, Alert } from '../components/ui'
import {
  listStockTakeSessions, openStockTakeSession, searchVariants,
  StockTakeSessionSummary, StockTakeScopeType, VariantMatch,
} from '../api'

// ── Helpers ───────────────────────────────────────────────────────────────────

function sessionTone(status: string): 'warning' | 'success' | 'neutral' {
  if (status === 'open') return 'warning'
  if (status === 'finalized') return 'success'
  return 'neutral'
}

function relativeTime(iso: string, t: (k: string, o?: Record<string, unknown>) => string): string {
  const diffMs = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(diffMs / 60000)
  if (mins < 1) return t('stocktake.time.justNow')
  if (mins < 60) return t('stocktake.time.minutesAgo', { count: mins })
  const hours = Math.floor(mins / 60)
  if (hours < 24) return t('stocktake.time.hoursAgo', { count: hours })
  const days = Math.floor(hours / 24)
  return t('stocktake.time.daysAgo', { count: days })
}

// ── List + Create ────────────────────────────────────────────────────────────

export default function StockTake() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [sessions, setSessions] = useState<StockTakeSessionSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [view, setView] = useState<'list' | 'create'>('list')

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    try { setSessions(await listStockTakeSessions()) }
    catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setLoading(false) }
  }

  function openRow(s: StockTakeSessionSummary) {
    if (s.status === 'open') navigate(`/stock-take/${s.sessionId}/scan`)
    else navigate(`/stock-take/${s.sessionId}/review`)
  }

  if (view === 'create') {
    return (
      <CreateSessionForm
        onCreated={(id) => navigate(`/stock-take/${id}/scan`)}
        onCancel={() => setView('list')}
      />
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-h1 text-primary">{t('stocktake.title')}</h1>
        <Button size="sm" onClick={() => setView('create')}>
          + {t('stocktake.new')}
        </Button>
      </div>

      {error && <Alert tone="critical" title={error} />}

      {loading ? (
        <div className="card overflow-hidden">
          <TableSkeleton rows={3} cols={5} />
        </div>
      ) : sessions.length === 0 ? (
        <EmptyState
          message={t('stocktake.empty')}
          icon="📋"
          action={{ label: '+ ' + t('stocktake.new'), onClick: () => setView('create') }}
        />
      ) : (
        <div className="card overflow-hidden">
          <table className="min-w-full">
            <thead>
              <tr className="border-b border-line">
                {['stocktake.col.status', 'stocktake.col.scope', 'stocktake.col.openedBy',
                  'stocktake.col.opened', 'stocktake.col.coverage'].map(k => (
                  <th key={k} className="tbl-header">{t(k)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sessions.map(s => (
                <tr key={s.sessionId} className="tbl-row cursor-pointer" onClick={() => openRow(s)}>
                  <td className="tbl-cell">
                    <Badge tone={sessionTone(s.status)} label={t(`stocktake.status.${s.status}`)} />
                  </td>
                  <td className="tbl-cell text-muted">
                    {t(s.scopeType === 'all' ? 'stocktake.scope.all' : 'stocktake.scope.variantSubset')}
                  </td>
                  <td className="tbl-cell text-primary">{s.openedByName ?? t('common.na')}</td>
                  <td className="tbl-cell text-muted text-small">{relativeTime(s.openedAt, t)}</td>
                  <td className="tbl-cell text-primary">
                    {s.status === 'open' ? `${s.coveragePercent}% (${s.counted}/${s.expected})` : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ── Create Session Form ───────────────────────────────────────────────────────

function CreateSessionForm({ onCreated, onCancel }: {
  onCreated: (id: string) => void; onCancel: () => void
}) {
  const { t } = useTranslation()
  const [scopeType, setScopeType] = useState<StockTakeScopeType>('all')
  const [note, setNote] = useState('')
  const [query, setQuery] = useState('')
  const [matches, setMatches] = useState<VariantMatch[]>([])
  const [selected, setSelected] = useState<VariantMatch[]>([])
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  function doSearch(q: string) {
    setQuery(q)
    if (searchTimer.current) clearTimeout(searchTimer.current)
    if (q.length < 2) { setMatches([]); return }
    searchTimer.current = setTimeout(async () => {
      try { setMatches(await searchVariants(q)) }
      catch { setMatches([]) }
    }, 250)
  }

  function addVariant(v: VariantMatch) {
    if (!selected.some(s => s.id === v.id)) setSelected([...selected, v])
    setQuery(''); setMatches([])
  }

  function removeVariant(id: string) {
    setSelected(selected.filter(s => s.id !== id))
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (scopeType === 'variant_subset' && selected.length === 0) {
      setError(t('stocktake.create.variantRequired'))
      return
    }
    setSaving(true); setError(null)
    try {
      const res = await openStockTakeSession({
        scopeType,
        variantIds: scopeType === 'variant_subset' ? selected.map(s => s.id) : undefined,
        note: note || undefined,
      })
      onCreated(res.sessionId)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="max-w-lg space-y-4">
      <h1 className="text-h1 text-primary">{t('stocktake.create.title')}</h1>
      {error && <Alert tone="critical" title={error} />}
      <form onSubmit={submit} className="card p-5 space-y-4">
        <div className="space-y-1.5">
          <label className="text-small text-muted">{t('stocktake.create.scope')}</label>
          <div className="flex gap-2">
            <button
              type="button"
              data-testid="scope-all-btn"
              onClick={() => setScopeType('all')}
              className={`px-3 py-1.5 rounded-lg text-small font-medium border transition-colors ${
                scopeType === 'all'
                  ? 'bg-trace-blue text-white border-trace-blue'
                  : 'border-line text-muted hover:border-trace-blue'
              }`}
            >
              {t('stocktake.scope.all')}
            </button>
            <button
              type="button"
              data-testid="scope-subset-btn"
              onClick={() => setScopeType('variant_subset')}
              className={`px-3 py-1.5 rounded-lg text-small font-medium border transition-colors ${
                scopeType === 'variant_subset'
                  ? 'bg-trace-blue text-white border-trace-blue'
                  : 'border-line text-muted hover:border-trace-blue'
              }`}
            >
              {t('stocktake.scope.variantSubset')}
            </button>
          </div>
        </div>

        {scopeType === 'variant_subset' && (
          <div className="space-y-2">
            <label className="text-small text-muted">{t('stocktake.create.variants')}</label>
            <div className="relative">
              {/* Standard type-ahead search — NOT a HID/scan input; Input component is safe */}
              <Input
                value={query}
                onChange={e => doSearch(e.target.value)}
                placeholder={t('receiving.searchVariant')}
              />
              {matches.length > 0 && (
                <ul className="absolute z-10 start-0 end-0 mt-1 bg-panel border border-line rounded-lg shadow-elevated max-h-48 overflow-y-auto">
                  {matches.map(v => (
                    <li key={v.id}
                      className="px-3 py-2.5 text-body hover:bg-elevated cursor-pointer border-b border-line last:border-0"
                      onClick={() => addVariant(v)}>
                      <span className="font-medium text-primary">{v.product_title}</span>
                      <span className="text-muted"> · {v.title}</span>
                      {v.sku && <span className="font-mono text-caption text-muted ms-2">{v.sku}</span>}
                    </li>
                  ))}
                </ul>
              )}
            </div>
            {selected.length > 0 && (
              <div className="flex flex-wrap gap-2">
                {selected.map(v => (
                  <span key={v.id} className="badge inline-flex items-center gap-1.5 bg-elevated">
                    {v.product_title} · {v.title}
                    <button type="button" onClick={() => removeVariant(v.id)} className="text-danger">✕</button>
                  </span>
                ))}
              </div>
            )}
          </div>
        )}

        <div className="space-y-1.5">
          <label className="text-small text-muted">{t('stocktake.create.note')}</label>
          <Input value={note} onChange={e => setNote(e.target.value)} placeholder={t('stocktake.create.notePlaceholder')} />
        </div>

        <div className="flex gap-3 pt-1">
          <Button type="submit" loading={saving}>
            {t('stocktake.create.submit')}
          </Button>
          <Button type="button" variant="secondary" onClick={onCancel}>
            {t('common.cancel')}
          </Button>
        </div>
      </form>
    </div>
  )
}
