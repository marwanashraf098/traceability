import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Badge, BadgeTone, Button, EmptyState, Input, Modal, Select, StatCard, TableSkeleton, Alert, SegmentedControl } from '../components/ui'
import {
  listOpenTransfers, listTransferDestinations, listLocations, createTransfer, listReturnablePieces,
  TransferSummary, TransferType, TRANSFER_TYPES, LocationOption, TransferCommandError,
  TransferListView, ReturnablePiece, TransferStatus,
} from '../api'

// FR-22.10 — Relocate is a distinct top-level create action, not a TRANSFER_TYPES radio
// value: transfer_mode (workflow: round_trip vs relocate_out) is orthogonal to transfer_type
// (category: showroom/dryclean/repair/other — see V64's header comment). Relocate rides on
// transfer_type='other' under the hood; the user never sees a type picker for it.

// FR-22.9 — Consignment list ("out on transfer") + create-transfer form.
// Modeled directly on StockTake.tsx's list+create split (same shell, same
// relativeTime helper, same segmented-button pattern for a small closed enum).

// ── Helpers ───────────────────────────────────────────────────────────────────

// Lifecycle reads grey → amber → blue → green: preparing (still here) → sent (stock is away)
// → reconciling (checking it back in) → closed. Cancelled is grey. Only the DS's five
// semantic tones exist and red is reserved for problems, so preparing shares grey with
// cancelled — the label tells them apart.
export function transferStatusTone(status: TransferStatus): BadgeTone {
  switch (status) {
    case 'sent':        return 'warning'
    case 'reconciling': return 'info'
    case 'closed':      return 'success'
    default:            return 'neutral'   // preparing, cancelled
  }
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

// Relocate / Bring back always carry transfer_type='other' (see FR-22.10 note above), so the
// Type column (and the detail page's subtitle) names the mode for those two instead; round
// trips keep their chosen category.
export function typeLabel(
  tr: Pick<TransferSummary, 'transfer_mode' | 'transfer_type'>,
  t: (k: string) => string,
): string {
  if (tr.transfer_mode === 'relocate_out') return t('transfers.relocate.title')
  if (tr.transfer_mode === 'relocate_return') return t('transfers.return.title')
  return t(`transfers.type.${tr.transfer_type}`)
}

type CreateView = 'create' | 'relocate' | 'return'

// ── List + Create ────────────────────────────────────────────────────────────

export default function Transfers() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [transfers, setTransfers] = useState<TransferSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [view, setView] = useState<'list' | 'create' | 'relocate' | 'return'>('list')
  // Closed-view toggle — default 'open' preserves the pre-toggle behavior exactly.
  const [statusView, setStatusView] = useState<TransferListView>('open')
  // Gates the "Relocate" affordance's visibility: destinations are every non-fulfillment
  // location, so an empty list already means "tenant has <2 locations" — no separate count
  // query needed, this is the exact same fetch CreateTransferForm makes for its own picker.
  const [destinationCount, setDestinationCount] = useState<number | null>(null)
  // Gates "Return": needs a destination AND something to have ever gone out one-way.
  // Reuses the 'all' transfers fetch (already needed for the closed-view toggle) rather
  // than a dedicated existence-check endpoint — an approximation (a relocate_out transfer
  // existing doesn't guarantee any of its pieces are still transferred_out today), but the
  // Return screen's own picker is the real source of truth once a location is chosen
  // (same pattern CreateTransferForm already uses for an empty destination list).
  const [everRelocated, setEverRelocated] = useState<boolean | null>(null)
  const [chooserOpen, setChooserOpen] = useState(false)

  useEffect(() => {
    load('open')
    listTransferDestinations().then(d => setDestinationCount(d.length)).catch(() => setDestinationCount(0))
    listOpenTransfers('all')
      .then(all => setEverRelocated(all.some(tr => tr.transfer_mode === 'relocate_out')))
      .catch(() => setEverRelocated(false))
  }, [])

  async function load(nextView: TransferListView) {
    setLoading(true)
    try { setTransfers(await listOpenTransfers(nextView)) }
    catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setLoading(false) }
  }

  function changeStatusView(next: TransferListView) {
    setStatusView(next)
    load(next)
  }

  // One "+ New transfer" entry point (header + empty state). Gating rules are the ones the
  // three separate header buttons used: round trip always; relocate needs a destination;
  // bring back additionally needs a relocate to have ever existed.
  const checksLoading = destinationCount === null || everRelocated === null
  const hasDestination = destinationCount !== null && destinationCount > 0
  const createOptions: CreateView[] = [
    'create',
    ...(hasDestination ? ['relocate' as const] : []),
    ...(hasDestination && everRelocated ? ['return' as const] : []),
  ]

  function startNewTransfer() {
    if (checksLoading) return
    if (createOptions.length === 1) setView(createOptions[0])
    else setChooserOpen(true)
  }

  function chooseOption(next: CreateView) {
    setChooserOpen(false)
    setView(next)
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

  if (view === 'return') {
    return (
      <ReturnTransferForm
        onCreated={(id) => navigate(`/transfers/${id}/scan-out`)}
        onCancel={() => setView('list')}
      />
    )
  }

  const sentCount = transfers.filter(tr => tr.status === 'sent').length
  const reconcilingCount = transfers.filter(tr => tr.status === 'reconciling').length
  const outstandingTotal = transfers.reduce((sum, tr) => sum + tr.outstanding_count, 0)

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-2">
        <h1 className="text-h1 text-primary">{t('transfers.title')}</h1>
        <Button size="sm" loading={checksLoading} onClick={startNewTransfer}>
          + {t('transfers.new')}
        </Button>
      </div>

      {chooserOpen && (
        <NewTransferChooser
          options={createOptions}
          onChoose={chooseOption}
          onClose={() => setChooserOpen(false)}
        />
      )}

      <SegmentedControl
        value={statusView}
        onChange={(v) => changeStatusView(v as TransferListView)}
        options={[
          { value: 'open', label: t('transfers.view.open') },
          { value: 'closed', label: t('transfers.view.closed') },
          { value: 'all', label: t('transfers.view.all') },
        ]}
      />

      {/* Summary tiles — derived client-side from the already-fetched preparing+sent+reconciling
          set (listOpenTransfers() returns the full set with a per-row outstanding_count,
          not paginated), so no extra fetch is needed. Containers stay neutral (StatCard's
          default); tone lives on the number only. */}
      <div className="grid grid-cols-3 gap-3" data-testid="transfers-summary">
        <StatCard label={t('transfers.summary.sent')} value={sentCount} tone="neutral" />
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
          action={{ label: '+ ' + t('transfers.new'), onClick: startNewTransfer, loading: checksLoading }}
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
                  <td className="tbl-cell text-muted">{typeLabel(tr, t)}</td>
                  <td className="tbl-cell">
                    <Badge tone={transferStatusTone(tr.status)} label={t(`transfers.status.${tr.status}`)} />
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

// ── New transfer chooser ────────────────────────────────────────────────────

const CHOOSER_COPY: Record<CreateView, { title: string; description: string }> = {
  create:   { title: 'transfers.chooser.roundTripTitle', description: 'transfers.chooser.roundTripDescription' },
  relocate: { title: 'transfers.relocate.title',         description: 'transfers.chooser.relocateDescription' },
  return:   { title: 'transfers.return.title',           description: 'transfers.chooser.returnDescription' },
}

function NewTransferChooser({ options, onChoose, onClose }: {
  options: CreateView[]; onChoose: (v: CreateView) => void; onClose: () => void
}) {
  const { t } = useTranslation()
  return (
    <Modal title={t('transfers.chooser.title')} onClose={onClose}>
      <div className="space-y-3" data-testid="new-transfer-chooser">
        {options.map(opt => (
          <button
            key={opt}
            type="button"
            data-testid={`chooser-option-${opt}`}
            onClick={() => onChoose(opt)}
            className="w-full text-start rounded-xl border border-line p-4 transition-colors hover:border-trace-blue focus-visible:border-trace-blue"
          >
            <p className="text-body font-semibold text-primary">{t(CHOOSER_COPY[opt].title)}</p>
            <p className="text-small text-muted mt-1">{t(CHOOSER_COPY[opt].description)}</p>
          </button>
        ))}
      </div>
    </Modal>
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

// ── Return Form (FR-22.11 / B2, B->A) ───────────────────────────────────────
//
// From = any non-fulfillment location (source_location_id); To is locked to the tenant's
// fulfillment warehouse (server-enforced too — createTransfer() rejects any other
// destination for transfer_mode='relocate_return'). The piece list below is informational
// only, not a bulk-claim call — the backend has no such endpoint, by design: a transfer's
// pieces are only ever claimed one at a time by an actual scan (returnScanOut, same as
// every other transfer type's scanOut), matching the "identity break-and-reissue" model
// (transfers-build-spec.md). Selecting here just lets the operator confirm what they expect
// to scan on the next screen; Create only opens the transfer shell (source + destination).
// The list is read-only (no checkboxes): a selection here was never sent anywhere, and
// merchants took ticking pieces for having moved them. The scan is the only thing that
// puts a piece on a transfer.

function ReturnTransferForm({ onCreated, onCancel }: {
  onCreated: (id: string) => void; onCancel: () => void
}) {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
  const [sources, setSources] = useState<LocationOption[]>([])
  const [loadingSources, setLoadingSources] = useState(true)
  const [sourceId, setSourceId] = useState('')
  const [fulfillmentLocation, setFulfillmentLocation] = useState<LocationOption | null>(null)
  const [pieces, setPieces] = useState<ReturnablePiece[]>([])
  const [loadingPieces, setLoadingPieces] = useState(false)
  const [note, setNote] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listTransferDestinations()
      .then(setSources)
      .catch(() => setSources([]))
      .finally(() => setLoadingSources(false))
    // "To" is locked to the tenant's fulfillment warehouse — listTransferDestinations()
    // filters is_fulfillment out (it's the valid-destination set for every OTHER transfer
    // type), so its id/name come from the unfiltered locations list instead.
    listLocations()
      .then(all => setFulfillmentLocation(all.find(l => l.is_fulfillment) ?? null))
      .catch(() => setFulfillmentLocation(null))
  }, [])

  useEffect(() => {
    if (!sourceId) { setPieces([]); return }
    setLoadingPieces(true)
    listReturnablePieces(sourceId)
      .then(setPieces)
      .catch(() => setPieces([]))
      .finally(() => setLoadingPieces(false))
  }, [sourceId])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!sourceId) {
      setError(t('transfers.return.sourceRequired'))
      return
    }
    if (!fulfillmentLocation) {
      setError(t('transfers.return.noFulfillmentLocation'))
      return
    }
    setSaving(true); setError(null)
    try {
      const res = await createTransfer({
        transferType: 'other',
        destinationLocationId: fulfillmentLocation.id,
        note: note || undefined,
        transferMode: 'relocate_return',
        sourceLocationId: sourceId,
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
      <h1 className="text-h1 text-primary">{t('transfers.return.title')}</h1>
      <p className="text-small text-muted">{t('transfers.return.description')}</p>
      {error && <Alert tone="critical" title={error} />}
      <form onSubmit={submit} className="card p-5 space-y-4">
        <div className="space-y-1.5">
          <label className="text-small text-muted">{t('transfers.return.from')}</label>
          {!loadingSources && sources.length === 0 ? (
            <Alert tone="warning" title={t('transfers.create.noDestinations')} />
          ) : (
            <Select
              value={sourceId}
              onChange={setSourceId}
              disabled={loadingSources}
              placeholder={t('transfers.return.fromPlaceholder')}
              options={sources.map(d => ({ value: d.id, label: d.name }))}
            />
          )}
        </div>

        <div className="space-y-1.5">
          <label className="text-small text-muted">{t('transfers.return.to')}</label>
          <p className="text-body text-primary">{fulfillmentLocation?.name ?? t('transfers.return.toLocked')}</p>
        </div>

        {sourceId && (
          <div className="space-y-1.5">
            <label className="text-small text-muted">{t('transfers.return.pieces')}</label>
            {loadingPieces ? (
              <p className="text-small text-muted">{t('common.loading')}</p>
            ) : pieces.length === 0 ? (
              <Alert tone="info" title={t('transfers.return.noPieces')} />
            ) : (
              <div className="card overflow-hidden max-h-64 overflow-y-auto" data-testid="returnable-pieces">
                {pieces.map(p => (
                  <div key={p.id} className="tbl-row flex items-center gap-2 px-3 py-2">
                    <span className="text-small text-primary">
                      {p.product_title} · {p.variant_title}
                      {p.sku && <span className="font-mono text-caption text-muted ms-2">{p.sku}</span>}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        <div className="space-y-1.5">
          <label className="text-small text-muted">{t('transfers.create.note')}</label>
          <Input value={note} onChange={e => setNote(e.target.value)} placeholder={t('transfers.create.notePlaceholder')} />
        </div>

        <div className="flex gap-3 pt-1">
          <Button type="submit" loading={saving}>
            {t('transfers.return.submit')}
          </Button>
          <Button type="button" variant="secondary" onClick={onCancel}>
            {t('common.cancel')}
          </Button>
        </div>
      </form>
    </div>
  )
}
