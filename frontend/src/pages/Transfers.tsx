import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Badge, Button, EmptyState, Input, Select, StatCard, TableSkeleton, Alert } from '../components/ui'
import {
  listOpenTransfers, listTransferDestinations, createTransfer,
  TransferSummary, TransferType, TRANSFER_TYPES, LocationOption, TransferCommandError,
} from '../api'

// FR-22.10 — Relocate is a distinct top-level create action, not a TRANSFER_TYPES radio
// value: transfer_mode (workflow: round_trip vs relocate_out) is orthogonal to transfer_type
// (category: showroom/dryclean/repair/other — see V64's header comment). Relocate rides on
// transfer_type='other' under the hood; the user never sees a type picker for it.

// FR-22.9 — Consignment list ("out on transfer") + create-transfer form.
// Modeled directly on StockTake.tsx's list+create split (same shell, same
// relativeTime helper, same segmented-button pattern for a small closed enum).

// ── Helpers ───────────────────────────────────────────────────────────────────

function transferTone(status: string): 'warning' | 'success' | 'neutral' {
  if (status === 'open') return 'warning'
  if (status === 'reconciling') return 'warning'
  return 'neutral'
}

function relativeTime(iso: string, t: (k: string, o?: Record<string, unknown>) => string): string {
  const diffMs = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(diffMs / 60000)
  if (mins < 1) return t('transfers.time.justNow')
  if (mins < 60) return t('transfers.time.minutesAgo', { count: mins })
  const hours = Math.floor(mins / 60)
  if (hours < 24) return t('transfers.time.hoursAgo', { count: hours })
  const days = Math.floor(hours / 24)
  return t('transfers.time.daysAgo', { count: days })
}

// ── List + Create ────────────────────────────────────────────────────────────

export default function Transfers() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [transfers, setTransfers] = useState<TransferSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [view, setView] = useState<'list' | 'create' | 'relocate'>('list')
  // Gates the "Relocate" affordance's visibility: destinations are every non-fulfillment
  // location, so an empty list already means "tenant has <2 locations" — no separate count
  // query needed, this is the exact same fetch CreateTransferForm makes for its own picker.
  const [destinationCount, setDestinationCount] = useState<number | null>(null)

  useEffect(() => {
    load()
    listTransferDestinations().then(d => setDestinationCount(d.length)).catch(() => setDestinationCount(0))
  }, [])

  async function load() {
    setLoading(true)
    try { setTransfers(await listOpenTransfers()) }
    catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setLoading(false) }
  }

  function openRow(tr: TransferSummary) {
    navigate(`/transfers/${tr.id}`)
  }

  if (view === 'create') {
    return (
      <CreateTransferForm
        onCreated={(id) => navigate(`/transfers/${id}/scan-out`)}
        onCancel={() => setView('list')}
      />
    )
  }

  if (view === 'relocate') {
    return (
      <RelocateTransferForm
        onCreated={(id) => navigate(`/transfers/${id}/scan-out`)}
        onCancel={() => setView('list')}
      />
    )
  }

  const openCount = transfers.filter(tr => tr.status === 'open').length
  const reconcilingCount = transfers.filter(tr => tr.status === 'reconciling').length
  const outstandingTotal = transfers.reduce((sum, tr) => sum + tr.outstanding_count, 0)

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-2">
        <h1 className="text-h1 text-primary">{t('transfers.title')}</h1>
        <div className="flex items-center gap-2">
          {/* Single-location pilots never see this — no second location to relocate to. */}
          {destinationCount !== null && destinationCount > 0 && (
            <Button size="sm" variant="secondary" onClick={() => setView('relocate')}>
              {t('transfers.relocate.action')}
            </Button>
          )}
          <Button size="sm" onClick={() => setView('create')}>
            + {t('transfers.new')}
          </Button>
        </div>
      </div>

      {/* Summary tiles — derived client-side from the already-fetched open+reconciling
          set (listOpenTransfers() returns the full set with a per-row outstanding_count,
          not paginated), so no extra fetch is needed. Containers stay neutral (StatCard's
          default); tone lives on the number only. */}
      <div className="grid grid-cols-3 gap-3" data-testid="transfers-summary">
        <StatCard label={t('transfers.summary.open')} value={openCount} tone="neutral" />
        <StatCard label={t('transfers.summary.reconciling')} value={reconcilingCount} tone="warning" />
        <StatCard label={t('transfers.summary.outstanding')} value={outstandingTotal} tone="warning" />
      </div>

      {error && <Alert tone="critical" title={error} />}

      {loading ? (
        <div className="card overflow-hidden">
          <TableSkeleton rows={3} cols={5} />
        </div>
      ) : transfers.length === 0 ? (
        <EmptyState
          message={t('transfers.empty')}
          icon="📦"
          action={{ label: '+ ' + t('transfers.new'), onClick: () => setView('create') }}
        />
      ) : (
        <div className="card overflow-hidden">
          <table className="min-w-full">
            <thead>
              <tr className="border-b border-line">
                {['transfers.col.destination', 'transfers.col.type', 'transfers.col.status',
                  'transfers.col.since', 'transfers.col.outstanding'].map(k => (
                  <th key={k} className="tbl-header">{t(k)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {transfers.map(tr => (
                <tr key={tr.id} className="tbl-row cursor-pointer" onClick={() => openRow(tr)}>
                  <td className="tbl-cell text-primary">{tr.destination_location_name}</td>
                  <td className="tbl-cell text-muted">{t(`transfers.type.${tr.transfer_type}`)}</td>
                  <td className="tbl-cell">
                    <Badge tone={transferTone(tr.status)} label={t(`transfers.status.${tr.status}`)} />
                  </td>
                  <td className="tbl-cell text-muted text-small">{relativeTime(tr.created_at, t)}</td>
                  <td className="tbl-cell text-primary">{tr.outstanding_count}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ── Create Transfer Form ────────────────────────────────────────────────────

function CreateTransferForm({ onCreated, onCancel }: {
  onCreated: (id: string) => void; onCancel: () => void
}) {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
  const [destinations, setDestinations] = useState<LocationOption[]>([])
  const [loadingDest, setLoadingDest] = useState(true)
  const [destinationId, setDestinationId] = useState('')
  const [transferType, setTransferType] = useState<TransferType>('showroom')
  const [expectedReturnAt, setExpectedReturnAt] = useState('')
  const [note, setNote] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listTransferDestinations()
      .then(setDestinations)
      .catch(() => setDestinations([]))
      .finally(() => setLoadingDest(false))
  }, [])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!destinationId) {
      setError(t('transfers.create.destinationRequired'))
      return
    }
    setSaving(true); setError(null)
    try {
      const res = await createTransfer({
        transferType,
        destinationLocationId: destinationId,
        expectedReturnAt: expectedReturnAt ? new Date(expectedReturnAt).toISOString() : undefined,
        note: note || undefined,
      })
      onCreated(res.id)
    } catch (e: unknown) {
      if (e instanceof TransferCommandError) setError(isAr ? e.messageAr : e.messageEn)
      else setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="max-w-lg space-y-4">
      <h1 className="text-h1 text-primary">{t('transfers.create.title')}</h1>
      {error && <Alert tone="critical" title={error} />}
      <form onSubmit={submit} className="card p-5 space-y-4">
        <div className="space-y-1.5">
          <label className="text-small text-muted">{t('transfers.create.destination')}</label>
          {!loadingDest && destinations.length === 0 ? (
            <Alert tone="warning" title={t('transfers.create.noDestinations')} />
          ) : (
            <Select
              value={destinationId}
              onChange={setDestinationId}
              disabled={loadingDest}
              placeholder={t('transfers.create.destinationPlaceholder')}
              options={destinations.map(d => ({ value: d.id, label: d.name }))}
            />
          )}
        </div>

        <div className="space-y-1.5">
          <label className="text-small text-muted">{t('transfers.create.type')}</label>
          <div className="flex flex-wrap gap-2">
            {TRANSFER_TYPES.map(ty => (
              <button
                key={ty}
                type="button"
                data-testid={`type-${ty}-btn`}
                onClick={() => setTransferType(ty)}
                className={`px-3 py-1.5 rounded-lg text-small font-medium border transition-colors ${
                  transferType === ty
                    ? 'bg-trace-blue text-white border-trace-blue'
                    : 'border-line text-muted hover:border-trace-blue'
                }`}
              >
                {t(`transfers.type.${ty}`)}
              </button>
            ))}
          </div>
        </div>

        <div className="space-y-1.5">
          <label className="text-small text-muted">{t('transfers.create.expectedReturn')}</label>
          <Input type="date" value={expectedReturnAt} onChange={e => setExpectedReturnAt(e.target.value)} />
        </div>

        <div className="space-y-1.5">
          <label className="text-small text-muted">{t('transfers.create.note')}</label>
          <Input value={note} onChange={e => setNote(e.target.value)} placeholder={t('transfers.create.notePlaceholder')} />
        </div>

        <div className="flex gap-3 pt-1">
          <Button type="submit" loading={saving}>
            {t('transfers.create.submit')}
          </Button>
          <Button type="button" variant="secondary" onClick={onCancel}>
            {t('common.cancel')}
          </Button>
        </div>
      </form>
    </div>
  )
}

// ── Relocate Form (FR-22.10, one-way A->B) ──────────────────────────────────
//
// No Type picker, no Expected Return field — a relocate is one-way (rides on
// transfer_type='other' under the hood, invisible to the user) and never comes back on
// its own transfer, so "expected return" has no meaning here. Destination validation
// (must be a real, tenant-owned, non-fulfillment location) is identical to
// CreateTransferForm's — same createTransfer() call, just with transferMode set.

function RelocateTransferForm({ onCreated, onCancel }: {
  onCreated: (id: string) => void; onCancel: () => void
}) {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
  const [destinations, setDestinations] = useState<LocationOption[]>([])
  const [loadingDest, setLoadingDest] = useState(true)
  const [destinationId, setDestinationId] = useState('')
  const [note, setNote] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listTransferDestinations()
      .then(setDestinations)
      .catch(() => setDestinations([]))
      .finally(() => setLoadingDest(false))
  }, [])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!destinationId) {
      setError(t('transfers.create.destinationRequired'))
      return
    }
    setSaving(true); setError(null)
    try {
      const res = await createTransfer({
        transferType: 'other',
        destinationLocationId: destinationId,
        note: note || undefined,
        transferMode: 'relocate_out',
      })
      onCreated(res.id)
    } catch (e: unknown) {
      if (e instanceof TransferCommandError) setError(isAr ? e.messageAr : e.messageEn)
      else setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="max-w-lg space-y-4">
      <h1 className="text-h1 text-primary">{t('transfers.relocate.title')}</h1>
      <p className="text-small text-muted">{t('transfers.relocate.description')}</p>
      {error && <Alert tone="critical" title={error} />}
      <form onSubmit={submit} className="card p-5 space-y-4">
        <div className="space-y-1.5">
          <label className="text-small text-muted">{t('transfers.create.destination')}</label>
          {!loadingDest && destinations.length === 0 ? (
            <Alert tone="warning" title={t('transfers.create.noDestinations')} />
          ) : (
            <Select
              value={destinationId}
              onChange={setDestinationId}
              disabled={loadingDest}
              placeholder={t('transfers.create.destinationPlaceholder')}
              options={destinations.map(d => ({ value: d.id, label: d.name }))}
            />
          )}
        </div>

        <div className="space-y-1.5">
          <label className="text-small text-muted">{t('transfers.create.note')}</label>
          <Input value={note} onChange={e => setNote(e.target.value)} placeholder={t('transfers.create.notePlaceholder')} />
        </div>

        <div className="flex gap-3 pt-1">
          <Button type="submit" loading={saving}>
            {t('transfers.relocate.submit')}
          </Button>
          <Button type="button" variant="secondary" onClick={onCancel}>
            {t('common.cancel')}
          </Button>
        </div>
      </form>
    </div>
  )
}
