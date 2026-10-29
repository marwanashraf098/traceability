import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Box, Minus, Plus, Printer } from 'lucide-react'
import {
  archivePlace, createPlace, createPlacesBulk, getPlace, getPlaces, listLocations, movePlace, openPlaceStickers,
  previewPlacesBulk, renamePlace, setPlacePurpose, LocationRow, PlaceBulkPlan, PlaceDetail, PlaceNode, PlaceTree,
  ServerMessageError,
} from '../../api'
import { Button, Input, Modal, Select, Skeleton, cn, useToast } from '../../components/ui'

// Warehouse places S1 — Settings › Places (owner + manager; shown only when the tenant has places). The visual setup:
// zone cards; the selected zone drawn as racks side by side with their shelves stacked inside, white boxes as chips;
// the zone floor's boxes underneath; Quick add (racks × shelves) and the selected place on the side. No piece is
// placed here — "No place yet" links to Put away (S2).

const MAX_SHELVES = 20
const MAX_RACKS = 99

interface ZoneView {
  zone: PlaceNode
  racks: { rack: PlaceNode; shelves: { shelf: PlaceNode; boxes: PlaceNode[] }[] }[]
  floorBoxes: PlaceNode[]
}

function zoneViews(nodes: PlaceNode[]): ZoneView[] {
  const kids = new Map<string | null, PlaceNode[]>()
  for (const n of nodes) {
    const list = kids.get(n.parentId) ?? []
    list.push(n)
    kids.set(n.parentId, list)
  }
  const of = (id: string, kind: PlaceNode['kind']) => (kids.get(id) ?? []).filter(n => n.kind === kind)
  return (kids.get(null) ?? []).filter(n => n.kind === 'zone').map(zone => ({
    zone,
    racks: of(zone.id, 'rack').map(rack => ({
      rack,
      shelves: of(rack.id, 'shelf').map(shelf => ({ shelf, boxes: of(shelf.id, 'box') })),
    })),
    floorBoxes: of(zone.id, 'box'),
  }))
}

export default function PlacesTab() {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const [locations, setLocations] = useState<LocationRow[]>([])
  const [locationId, setLocationId] = useState<string | undefined>(undefined)
  const [tree, setTree] = useState<PlaceTree | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [zoneId, setZoneId] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const fail = useCallback((e: unknown) => {
    const message = e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('places.errors.generic')
    toast({ tone: 'error', message })
  }, [i18n.language, t, toast])

  useEffect(() => { listLocations().then(setLocations).catch(() => {}) }, [])

  const reload = useCallback(async (loc?: string) => {
    try {
      const next = await getPlaces(loc)
      setTree(next)
      setLoadError(false)
      return next
    } catch {
      setLoadError(true)
      return null
    }
  }, [])

  useEffect(() => { setTree(null); setZoneId(null); setSelectedId(null); reload(locationId) }, [locationId, reload])

  const zones = useMemo(() => (tree ? zoneViews(tree.nodes) : []), [tree])
  const zone = zones.find(z => z.zone.id === zoneId) ?? zones[0] ?? null

  async function run<T>(action: () => Promise<T>, after?: (result: T) => void) {
    if (busy) return
    setBusy(true)
    try {
      const result = await action()
      await reload(tree?.locationId)
      after?.(result)
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }

  const addZone = () => run(() => createPlace({ locationId: tree?.locationId, kind: 'zone' }), z => {
    setZoneId(z.id)
    setSelectedId(null)
  })
  const printAll = () => {
    if (zones.length === 0) return
    openPlaceStickers(zones.map(z => z.zone.id)).catch(fail)
  }

  if (loadError) {
    return <p className="text-body text-critical-text" data-testid="places-error">{t('places.errors.load')}</p>
  }
  if (!tree) return <Skeleton className="h-64 rounded-2xl" />

  return (
    <div className="space-y-4" data-testid="places-settings">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <h2 className="text-h2 text-primary">{t('places.title')}</h2>
          {locations.length > 1 ? (
            <div className="w-56" data-testid="places-location">
              <Select
                value={tree.locationId}
                onChange={setLocationId}
                options={locations.map(l => ({ value: l.id, label: l.name }))}
              />
            </div>
          ) : (
            <span className="text-h3 text-primary">{tree.locationName}</span>
          )}
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" iconStart={Printer} onClick={printAll} disabled={zones.length === 0}>
            {t('places.printAll')}
          </Button>
          <Button onClick={addZone} loading={busy} iconStart={Plus}>
            {t('places.addZone')}
          </Button>
        </div>
      </div>

      <div role="tablist" aria-label={t('places.zones')} className="flex flex-wrap gap-2.5">
        {zones.map(z => {
          const active = z.zone.id === zone?.zone.id
          const shelves = z.racks.reduce((n, r) => n + r.shelves.length, 0)
          const boxes = z.floorBoxes.length + z.racks.reduce((n, r) => n + r.shelves.reduce((m, s) => m + s.boxes.length, 0), 0)
          return (
            <button
              key={z.zone.id}
              type="button"
              role="tab"
              aria-selected={active}
              data-testid={`places-zone-${z.zone.code}`}
              onClick={() => { setZoneId(z.zone.id); setSelectedId(null) }}
              className={cn('min-w-[150px] text-start rounded-xl px-4 py-3 bg-surface border transition-colors',
                active ? 'border-2 border-trace-blue' : 'border-line hover:bg-elevated',
                z.zone.purpose === 'damaged' && !active && 'bg-critical/[0.04] border-critical/30')}
            >
              <span className="flex items-center gap-2">
                <span className="text-h3 text-primary font-bold">{z.zone.code}</span>
                {z.zone.purpose === 'damaged' && <DamagedBadge />}
              </span>
              {z.zone.name && <span className="block text-small text-primary truncate">{z.zone.name}</span>}
              <span className="block text-small text-muted">
                {z.racks.length > 0
                  ? t('places.zoneSummary', { racks: z.racks.length, shelves, pieces: z.zone.totalPieces })
                  : t('places.zoneSummaryBoxes', { count: boxes, pieces: z.zone.totalPieces })}
              </span>
            </button>
          )
        })}
        <button
          type="button"
          onClick={addZone}
          disabled={busy}
          data-testid="places-next-zone"
          className="min-w-[120px] rounded-xl px-4 py-3 border border-dashed border-grey-300 text-body font-medium text-primary hover:bg-elevated"
        >
          {t('places.nextZone', { code: tree.nextZoneCode })}
        </button>
      </div>

      {zone === null ? (
        <EmptySetup tree={tree} onCreated={async (r) => { await reload(tree.locationId); setZoneId(r.zoneId) }} fail={fail} />
      ) : (
        <div className="flex flex-wrap items-start gap-5">
          <ZoneLayout
            view={zone}
            selectedId={selectedId}
            onSelect={setSelectedId}
            busy={busy}
            onAddShelf={rackId => run(() => createPlace({ kind: 'shelf', parentId: rackId }), s => setSelectedId(s.id))}
            onAddRack={shelves => run(() => createPlacesBulk({ zoneId: zone.zone.id, racks: 1, shelvesPerRack: shelves, floorBoxes: 0 }))}
            onAddBox={parentId => run(() => createPlace({ kind: 'box', parentId }), b => setSelectedId(b.id))}
            onZoneAction={action => {
              const z = zone.zone
              if (action === 'print') openPlaceStickers([z.id]).catch(fail)
              if (action === 'damaged') run(() => setPlacePurpose(z.id, z.purpose === 'damaged' ? null : 'damaged'))
              if (action === 'archive') run(() => archivePlace(z.id), () => setZoneId(null))
              if (action === 'rename') setSelectedId(z.id)
            }}
          />
          <aside className="flex-1 basis-[300px] min-w-0 space-y-4">
            <QuickAdd
              key={zone.zone.id}
              zone={zone.zone}
              onCreated={async (r) => {
                await reload(tree.locationId)
                openPlaceStickers(r.createdIds).catch(fail)
              }}
              fail={fail}
            />
            {selectedId && tree.nodes.some(n => n.id === selectedId) && (
              <SelectedPlace
                key={selectedId}
                id={selectedId}
                nodes={tree.nodes}
                locationName={tree.locationName}
                busy={busy}
                onRun={run}
                onClose={() => setSelectedId(null)}
                fail={fail}
              />
            )}
            <div
              className="flex items-center justify-between gap-3 rounded-xl border border-warning/40 bg-warning/[0.08] px-4 py-3.5"
              data-testid="places-no-place"
            >
              <span className="text-body text-warning-text">
                {t('places.noPlaceYet', { count: tree.noPlaceYet })}
              </span>
              {tree.noPlaceYet > 0 && (
                <Link to="/put-away" data-testid="places-put-away-link"
                  className="text-body font-semibold text-trace-blue hover:text-trace-blue-hover whitespace-nowrap">
                  {t('places.putAwayLink')} <span aria-hidden className="inline-block rtl:rotate-180">›</span>
                </Link>
              )}
            </div>
          </aside>
        </div>
      )}
    </div>
  )
}

function DamagedBadge() {
  const { t } = useTranslation()
  return (
    <span className="text-caption font-semibold text-critical-text bg-critical/10 border border-critical/30 rounded-full px-2 py-0.5">
      {t('places.damaged')}
    </span>
  )
}

function BoxChip({ box, selected, onSelect }: { box: PlaceNode; selected: boolean; onSelect: (id: string) => void }) {
  const { t } = useTranslation()
  return (
    <button
      type="button"
      onClick={e => { e.stopPropagation(); onSelect(box.id) }}
      data-testid={`places-box-${box.code}`}
      className={cn('inline-flex items-center gap-1 rounded px-1.5 py-0.5 font-mono text-caption font-semibold',
        'bg-warning/10 text-warning-text border',
        selected ? 'border-trace-blue ring-1 ring-trace-blue' : 'border-warning/40')}
      aria-label={t('places.boxAria', { code: box.code, count: box.totalPieces })}
    >
      {box.code}
      {box.purpose === 'damaged' && <span aria-hidden="true">·!</span>}
    </button>
  )
}

function ZoneLayout({
  view, selectedId, onSelect, busy, onAddShelf, onAddRack, onAddBox, onZoneAction,
}: {
  view: ZoneView
  selectedId: string | null
  onSelect: (id: string) => void
  busy: boolean
  onAddShelf: (rackId: string) => void
  /** One more rack with as many shelves as the last one (4 when the zone has none). */
  onAddRack: (shelves: number) => void
  onAddBox: (parentId: string) => void
  onZoneAction: (action: 'print' | 'damaged' | 'archive' | 'rename') => void
}) {
  const { t } = useTranslation()
  const z = view.zone
  const used = new Set(view.racks.map(r => r.rack.label.toUpperCase()))
  let next = 1
  while (used.has(`R${String(next).padStart(2, '0')}`)) next++
  const nextRack = `R${String(next).padStart(2, '0')}`
  const lastShelves = view.racks.length > 0 ? Math.max(1, view.racks[view.racks.length - 1].shelves.length) : 4
  return (
    <section
      aria-label={t('places.zoneLayout', { code: z.code })}
      className="flex-[999_1_600px] min-w-0 bg-surface border border-line rounded-2xl p-5 space-y-4"
      data-testid="places-zone-layout"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-h3 text-primary">
          {t('places.zoneTitle', { code: z.code })}
          {z.name && <span className="text-body text-muted font-normal"> · {z.name}</span>}
          <span className="text-body text-muted font-normal"> · {t('places.tapShelf')}</span>
        </h3>
        <div className="flex flex-wrap gap-1.5">
          <Button size="sm" variant="ghost" onClick={() => onZoneAction('rename')}>{t('places.rename')}</Button>
          <Button size="sm" variant="ghost" onClick={() => onZoneAction('print')} iconStart={Printer}>{t('places.printZone')}</Button>
          <Button size="sm" variant="ghost" onClick={() => onZoneAction('damaged')} disabled={busy}>
            {z.purpose === 'damaged' ? t('places.unmarkDamaged') : t('places.markDamaged')}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => onZoneAction('archive')} disabled={busy} className="text-critical-text">
            {t('places.archiveZone')}
          </Button>
        </div>
      </div>

      <div className="overflow-x-auto pb-1" data-testid="places-racks">
        <div className="flex gap-3.5 items-stretch min-w-max">
          {view.racks.map(({ rack, shelves }) => (
            <div key={rack.id} className="w-[140px] flex flex-col gap-1.5" data-testid={`places-rack-${rack.code}`}>
              <button
                type="button"
                onClick={() => onSelect(rack.id)}
                className={cn('flex items-baseline justify-between px-0.5 rounded text-start',
                  selectedId === rack.id && 'text-trace-blue')}
              >
                <span className="text-body font-bold">{rack.label}</span>
                <span className="text-small text-muted">{t('places.pcs', { count: rack.totalPieces })}</span>
              </button>
              <div className="bg-elevated border-2 border-grey-300 rounded-lg p-1.5 flex flex-col gap-1.5 flex-1">
                {shelves.map(({ shelf, boxes }) => {
                  const selected = selectedId === shelf.id
                  const empty = shelf.totalPieces === 0
                  return (
                    <div
                      key={shelf.id}
                      role="button"
                      tabIndex={0}
                      onClick={() => onSelect(shelf.id)}
                      onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(shelf.id) } }}
                      data-testid={`places-shelf-${shelf.code}`}
                      aria-pressed={selected}
                      className={cn('min-h-[62px] w-full text-start rounded-md px-2.5 py-2 flex flex-col gap-1 cursor-pointer',
                        selected ? 'border-2 border-trace-blue bg-trace-blue/[0.08]'
                          : empty ? 'border border-dashed border-grey-300 bg-surface' : 'border border-line bg-bg')}
                    >
                      <span className="flex items-baseline justify-between">
                        <span className="text-small font-bold text-primary">{shelf.label}</span>
                        <span className={cn('text-small', empty ? 'text-muted' : 'text-primary')}>
                          {empty ? t('places.empty') : t('places.pcs', { count: shelf.totalPieces })}
                        </span>
                      </span>
                      {boxes.length > 0 && (
                        <span className="flex flex-wrap gap-1">
                          {boxes.map(b => <BoxChip key={b.id} box={b} selected={selectedId === b.id} onSelect={onSelect} />)}
                        </span>
                      )}
                    </div>
                  )
                })}
              </div>
              <button
                type="button"
                onClick={() => onAddShelf(rack.id)}
                disabled={busy}
                className="h-8 rounded-md border border-dashed border-grey-300 bg-surface text-small font-medium text-primary hover:bg-elevated"
              >
                {t('places.addShelf')}
              </button>
            </div>
          ))}
          <button
            type="button"
            onClick={() => onAddRack(lastShelves)}
            disabled={busy}
            data-testid="places-add-rack"
            className="w-[110px] mt-6 rounded-lg border-2 border-dashed border-grey-300 bg-surface text-body font-semibold text-primary flex flex-col items-center justify-center gap-1 hover:bg-elevated"
          >
            <Plus size={22} aria-hidden="true" />
            {t('places.addRack')}
            <span className="text-small font-normal text-muted" dir="ltr">{nextRack}</span>
          </button>
        </div>
      </div>

      <div className="border-t border-line pt-3.5 space-y-2.5">
        <p className="text-body font-semibold text-primary">
          {t('places.floor')} <span className="font-normal text-muted">· {t('places.floorHint')}</span>
        </p>
        <div className="flex flex-wrap gap-2.5">
          {view.floorBoxes.map(b => (
            <button
              key={b.id}
              type="button"
              onClick={() => onSelect(b.id)}
              data-testid={`places-floorbox-${b.code}`}
              className={cn('min-w-[120px] text-start rounded-lg px-3 py-2.5 bg-warning/10 border',
                selectedId === b.id ? 'border-2 border-trace-blue' : 'border-warning/40')}
            >
              <span className="block font-mono text-small font-semibold text-warning-text">
                {b.code} {b.purpose === 'damaged' && <DamagedBadge />}
              </span>
              <span className="block text-small text-warning-text">{t('places.pcs', { count: b.totalPieces })}</span>
            </button>
          ))}
          <button
            type="button"
            onClick={() => onAddBox(z.id)}
            disabled={busy}
            className="min-w-[120px] min-h-[52px] rounded-lg border border-dashed border-grey-300 bg-surface text-small font-medium text-primary hover:bg-elevated"
          >
            {t('places.addBox')}
          </button>
        </div>
      </div>
    </section>
  )
}

function Stepper({ label, value, onChange, min, max, testId }: {
  label: string; value: number; onChange: (n: number) => void; min: number; max: number; testId: string
}) {
  const { t } = useTranslation()
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="text-body text-primary">{label}</span>
      <div className="flex items-center gap-1.5">
        <button type="button" aria-label={t('places.less', { what: label })} onClick={() => onChange(Math.max(min, value - 1))}
          className="w-10 h-10 rounded-lg border border-line bg-surface flex items-center justify-center hover:bg-elevated">
          <Minus size={16} />
        </button>
        <span className="w-9 text-center text-h3 text-primary" data-testid={testId}>{value}</span>
        <button type="button" aria-label={t('places.more', { what: label })} onClick={() => onChange(Math.min(max, value + 1))}
          className="w-10 h-10 rounded-lg border border-line bg-surface flex items-center justify-center hover:bg-elevated">
          <Plus size={16} />
        </button>
      </div>
    </div>
  )
}

function rangeText(plan: PlaceBulkPlan | null): string {
  if (!plan?.firstShelf) return ''
  return plan.firstShelf === plan.lastShelf ? plan.firstShelf : `${plan.firstShelf} … ${plan.lastShelf}`
}

/** Quick add: N racks × M shelves to the selected zone, racks numbered from its next free R. */
function QuickAdd({ zone, onCreated, fail }: {
  zone: PlaceNode
  onCreated: (r: { createdIds: string[] }) => Promise<void>
  fail: (e: unknown) => void
}) {
  const { t } = useTranslation()
  const [racks, setRacks] = useState(2)
  const [shelves, setShelves] = useState(4)
  const [plan, setPlan] = useState<PlaceBulkPlan | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let cancelled = false
    previewPlacesBulk({ zoneId: zone.id, racks, shelvesPerRack: shelves, floorBoxes: 0 })
      .then(p => { if (!cancelled) setPlan(p) })
      .catch(() => { if (!cancelled) setPlan(null) })
    return () => { cancelled = true }
  }, [zone.id, racks, shelves])

  async function create() {
    if (saving) return
    setSaving(true)
    try {
      const r = await createPlacesBulk({ zoneId: zone.id, racks, shelvesPerRack: shelves, floorBoxes: 0 })
      await onCreated(r)
    } catch (e) {
      fail(e)
    } finally {
      setSaving(false)
    }
  }

  return (
    <section aria-label={t('places.quickAdd', { code: zone.code })} className="bg-surface border border-line rounded-2xl p-4 space-y-3.5"
      data-testid="places-quick-add">
      <h3 className="text-h3 text-primary">{t('places.quickAdd', { code: zone.code })}</h3>
      <Stepper label={t('places.racks')} value={racks} onChange={setRacks} min={1} max={MAX_RACKS} testId="places-qa-racks" />
      <Stepper label={t('places.shelvesEach')} value={shelves} onChange={setShelves} min={1} max={MAX_SHELVES} testId="places-qa-shelves" />
      <div className="bg-bg rounded-lg p-3 space-y-1">
        <p className="text-small text-primary" data-testid="places-qa-total">{t('places.createsShelves', { count: racks * shelves })}</p>
        <p className="font-mono text-caption text-muted" dir="ltr" data-testid="places-qa-range">{rangeText(plan)}</p>
      </div>
      <Button className="w-full" size="lg" onClick={create} loading={saving} iconStart={Printer}>
        {t('places.createAndPrint')}
      </Button>
    </section>
  )
}

/** No zone yet in this location: one form makes the first zone with its racks, shelves and floor boxes. */
function EmptySetup({ tree, onCreated, fail }: {
  tree: PlaceTree
  onCreated: (r: { zoneId: string; createdIds: string[] }) => Promise<void>
  fail: (e: unknown) => void
}) {
  const { t } = useTranslation()
  const [racks, setRacks] = useState(4)
  const [shelves, setShelves] = useState(4)
  const [boxes, setBoxes] = useState(0)
  const [plan, setPlan] = useState<PlaceBulkPlan | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let cancelled = false
    previewPlacesBulk({ locationId: tree.locationId, racks, shelvesPerRack: shelves, floorBoxes: boxes })
      .then(p => { if (!cancelled) setPlan(p) })
      .catch(() => { if (!cancelled) setPlan(null) })
    return () => { cancelled = true }
  }, [tree.locationId, racks, shelves, boxes])

  async function create() {
    if (saving) return
    setSaving(true)
    try {
      const r = await createPlacesBulk({ locationId: tree.locationId, racks, shelvesPerRack: shelves, floorBoxes: boxes })
      await onCreated(r)
      openPlaceStickers(r.createdIds).catch(fail)
    } catch (e) {
      fail(e)
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="bg-surface border border-line rounded-2xl p-5 max-w-lg space-y-4" data-testid="places-empty">
      <div>
        <h3 className="text-h3 text-primary">{t('places.emptyTitle', { location: tree.locationName })}</h3>
        <p className="text-small text-muted mt-1">{t('places.emptyBody')}</p>
      </div>
      <Stepper label={t('places.racks')} value={racks} onChange={setRacks} min={1} max={MAX_RACKS} testId="places-setup-racks" />
      <Stepper label={t('places.shelvesEach')} value={shelves} onChange={setShelves} min={1} max={MAX_SHELVES} testId="places-setup-shelves" />
      <Stepper label={t('places.floorBoxes')} value={boxes} onChange={setBoxes} min={0} max={200} testId="places-setup-boxes" />
      <div className="bg-bg rounded-lg p-3 space-y-1">
        <p className="text-small text-primary" data-testid="places-setup-summary">
          {t('places.setupSummary', { zone: plan?.zoneCode ?? tree.nextZoneCode, racks, shelves: racks * shelves, boxes })}
        </p>
        <p className="font-mono text-caption text-muted" dir="ltr">{rangeText(plan)}</p>
      </div>
      <Button className="w-full" size="lg" onClick={create} loading={saving} iconStart={Printer}>
        {t('places.createAndPrint')}
      </Button>
    </section>
  )
}

function SelectedPlace({ id, nodes, locationName, busy, onRun, onClose, fail }: {
  id: string
  nodes: PlaceNode[]
  locationName: string
  busy: boolean
  onRun: <T>(action: () => Promise<T>, after?: (r: T) => void) => Promise<void>
  onClose: () => void
  fail: (e: unknown) => void
}) {
  const { t } = useTranslation()
  const node = nodes.find(n => n.id === id)!
  const [detail, setDetail] = useState<PlaceDetail | null>(null)
  const [renaming, setRenaming] = useState(false)

  useEffect(() => {
    let cancelled = false
    getPlace(id).then(d => { if (!cancelled) setDetail(d) }).catch(() => {})
    return () => { cancelled = true }
  }, [id, node.totalPieces, node.code, node.name, node.parentId, node.purpose])

  const moveTargets = node.kind === 'box'
    ? nodes.filter(n => (n.kind === 'zone' || n.kind === 'shelf') && n.id !== node.parentId)
      .map(n => ({ value: n.id, label: n.kind === 'zone' ? t('places.floorOf', { code: n.code }) : n.code }))
    : []

  return (
    <section aria-label={t('places.selected')} className="bg-surface border-2 border-trace-blue rounded-2xl p-4 space-y-3"
      data-testid="places-selected">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-caption uppercase tracking-wide font-semibold text-muted">{t(`places.kind.${node.kind}`)}</p>
          <p className="font-mono text-h2 text-primary break-all" data-testid="places-selected-code">{node.code}</p>
          <p className="text-small text-muted">
            {[locationName, ...node.path].join(' › ')}{node.name ? ` · ${node.name}` : ''}
          </p>
        </div>
        <div className="text-end">
          <span className="text-h2 text-primary">{node.totalPieces}</span>
          <span className="text-small text-muted"> {t('places.pcsUnit')}</span>
        </div>
      </div>

      {(node.kind === 'shelf' || node.kind === 'box') && (
        <div className="flex items-center gap-3 rounded-lg bg-bg p-3" aria-label={t('places.stickerPreview')}>
          <div className="w-[150px] rounded border border-line bg-white p-2 flex flex-col items-center gap-1">
            <div aria-hidden="true" className="w-full h-7 bg-[repeating-linear-gradient(90deg,#111_0_2px,#fff_2px_4px,#111_4px_5px,#fff_5px_8px)]" />
            <span className="font-mono text-caption font-semibold text-black whitespace-nowrap" dir="ltr">{node.code}</span>
            <span className="text-caption text-grey-600 text-center" dir="ltr">{node.scanCode}</span>
          </div>
          <p className="text-small text-muted">{t('places.stickerHint')}</p>
        </div>
      )}

      {detail && (detail.contents.length > 0 || detail.boxes.length > 0) ? (
        <ul className="text-body divide-y divide-line border-t border-line" data-testid="places-contents">
          {detail.contents.map(c => (
            <li key={c.variantId} className="flex justify-between gap-3 py-1.5">
              <span className="truncate">{c.productTitle}{c.variantTitle && c.variantTitle !== 'Default Title' ? ` · ${c.variantTitle}` : ''}</span>
              <b>{c.count}</b>
            </li>
          ))}
          {detail.boxes.map(b => (
            <li key={b.id} className="flex justify-between gap-3 py-1.5">
              <span><span className="font-mono text-small text-warning-text">{b.code}</span> ({t('places.kind.box')})</span>
              <b>{b.totalPieces}</b>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-small text-muted">{t('places.nothingHere')}</p>
      )}

      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant="secondary" iconStart={Printer} onClick={() => openPlaceStickers([node.id]).catch(fail)}>
          {node.kind === 'shelf' || node.kind === 'box' ? t('places.printSticker') : t('places.printStickers')}
        </Button>
        {node.kind === 'shelf' && (
          <Button size="sm" variant="secondary" iconStart={Box} disabled={busy}
            onClick={() => onRun(() => createPlace({ kind: 'box', parentId: node.id }))}>
            {t('places.boxHere')}
          </Button>
        )}
        {node.kind === 'rack' && (
          <Button size="sm" variant="secondary" disabled={busy}
            onClick={() => onRun(() => createPlace({ kind: 'shelf', parentId: node.id }))}>
            {t('places.addShelf')}
          </Button>
        )}
        <Button size="sm" variant="secondary" onClick={() => setRenaming(true)}>{t('places.rename')}</Button>
        {node.kind === 'box' && (
          <Button size="sm" variant="secondary" disabled={busy}
            onClick={() => onRun(() => setPlacePurpose(node.id, node.purpose === 'damaged' ? null : 'damaged'))}>
            {node.purpose === 'damaged' ? t('places.unmarkDamaged') : t('places.markDamaged')}
          </Button>
        )}
        {node.kind !== 'zone' && (
          <Button size="sm" variant="ghost" className="text-critical-text" disabled={busy}
            onClick={() => onRun(() => archivePlace(node.id), () => onClose())}>
            {t('places.archive')}
          </Button>
        )}
      </div>

      {moveTargets.length > 0 && (
        <div className="space-y-1">
          <p className="text-small text-muted">{t('places.moveBox')}</p>
          <Select value="" placeholder={t('places.moveTo')} options={moveTargets}
            onChange={target => onRun(() => movePlace(node.id, target))} />
        </div>
      )}

      {renaming && (
        <RenameModal node={node} onClose={() => setRenaming(false)}
          onSave={(code, name) => onRun(() => renamePlace(node.id, { code, name }), () => setRenaming(false))} />
      )}
    </section>
  )
}

function RenameModal({ node, onClose, onSave }: {
  node: PlaceNode; onClose: () => void; onSave: (code: string, name: string) => void
}) {
  const { t } = useTranslation()
  const [code, setCode] = useState(node.code)
  const [name, setName] = useState(node.name ?? '')
  return (
    <Modal title={t('places.renameTitle', { code: node.code })} onClose={onClose}>
      <form className="space-y-4" onSubmit={e => { e.preventDefault(); onSave(code.trim(), name.trim()) }}>
        <label className="block space-y-1">
          <span className="text-small font-semibold text-primary">{t('places.code')}</span>
          <Input value={code} onChange={e => setCode(e.target.value)} dir="ltr" maxLength={40} data-testid="places-rename-code" />
          <span className="block text-caption text-muted">{t('places.codeHint')}</span>
        </label>
        <label className="block space-y-1">
          <span className="text-small font-semibold text-primary">{t('places.name')}</span>
          <Input value={name} onChange={e => setName(e.target.value)} maxLength={60} data-testid="places-rename-name" />
        </label>
        {node.kind === 'rack' && (
          <p className="text-caption text-muted" data-testid="places-rename-rack-note">{t('places.renameRackNote')}</p>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>{t('places.cancel')}</Button>
          <Button type="submit">{t('places.save')}</Button>
        </div>
      </form>
    </Modal>
  )
}
