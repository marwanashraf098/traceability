import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Printer } from 'lucide-react'
import {
  getPlaceStickerPlan, openPlaceStickers, type PlaceKind, type PlaceStickerChoice, type PlaceStickerPlan,
  type PlaceStickerSize,
} from '../../api'
import { Button, Checkbox, Modal, SegmentedControl, Skeleton } from '../../components/ui'

const KINDS: PlaceKind[] = ['zone', 'rack', 'shelf', 'box']
const SIZES: PlaceStickerSize[] = ['SMALL', 'A6', 'A4']

/**
 * Place sticker sizes — "Print stickers": one row per kind in / under the chosen places (zones and racks included),
 * each with its size (50×25 mm / A6 / A4; A6 one per page or four per A4 sheet), offered as the tenant's last choice
 * for that kind (else shelf 50×25, box and rack A6, zone A4). Print → one PDF, every kind in its size; the server
 * remembers each printed kind's choice.
 *
 * Ticks: a bulk print (Print all stickers, Print zone stickers) starts with zones and racks unticked, shelves and
 * boxes ticked, unless the store remembered another tick for that kind; a direct print (one place, Create and print)
 * starts with every kind ticked. On a bulk print the kinds whose tick was changed are remembered.
 */
const BULK_DEFAULT: Record<PlaceKind, boolean> = { zone: false, rack: false, shelf: true, box: true }

export function PrintStickersDialog({ placeIds, bulk = false, onClose, fail }: {
  placeIds: string[]
  bulk?: boolean
  onClose: () => void
  fail: (e: unknown) => void
}) {
  const { t, i18n } = useTranslation()
  const [plan, setPlan] = useState<PlaceStickerPlan | null>(null)
  const [choices, setChoices] = useState<Partial<Record<PlaceKind, PlaceStickerChoice>>>({})
  const [included, setIncluded] = useState<Partial<Record<PlaceKind, boolean>>>({})
  const [initial, setInitial] = useState<Partial<Record<PlaceKind, boolean>>>({})
  const [printing, setPrinting] = useState(false)

  useEffect(() => {
    let cancelled = false
    getPlaceStickerPlan(placeIds)
      .then(p => {
        if (cancelled) return
        // Read everything before keeping it: a plan without counts / choices is an error, never a broken render.
        const included = Object.fromEntries(KINDS.map(k => [k,
          p.counts[k] > 0 && !!p.choices[k] && (bulk ? (p.bulkTicks?.[k] ?? BULK_DEFAULT[k]) : true)]))
        setPlan(p)
        setChoices(p.choices)
        setIncluded(included)
        setInitial(included)
      })
      .catch(e => { if (!cancelled) { fail(e); onClose() } })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [placeIds.join(',')])

  const rows = plan ? KINDS.filter(k => plan.counts[k] > 0 && !!plan.choices[k]) : []
  const chosen = rows.filter(k => included[k])

  function setSize(kind: PlaceKind, size: PlaceStickerSize) {
    setChoices(c => ({ ...c, [kind]: { size, a6Mode: size === 'A6' ? (c[kind]?.a6Mode ?? 'single') : null } }))
  }

  async function print() {
    if (printing || chosen.length === 0) return
    setPrinting(true)
    try {
      const kinds = Object.fromEntries(chosen.map(k => [k, choices[k]!]))
      const changed = bulk
        ? Object.fromEntries(rows.filter(k => !!included[k] !== !!initial[k]).map(k => [k, !!included[k]]))
        : undefined
      await openPlaceStickers(placeIds, kinds, i18n.language, changed)
      onClose()
    } catch (e) {
      fail(e)
    } finally {
      setPrinting(false)
    }
  }

  return (
    <Modal title={t('places.print.title')} onClose={() => !printing && onClose()}>
      <div className="space-y-4" data-testid="places-print-dialog">
        {!plan ? (
          <Skeleton className="h-32 rounded-lg" />
        ) : (
          <>
            <ul className="space-y-3">
              {rows.map(kind => {
                const c = choices[kind]!
                return (
                  <li key={kind} className="space-y-2 rounded-lg bg-bg p-3" data-testid={`places-print-row-${kind}`}>
                    <Checkbox
                      checked={!!included[kind]}
                      onChange={v => setIncluded(i => ({ ...i, [kind]: v }))}
                      label={t('places.print.kindCount', { kind: t(`places.print.kinds.${kind}`), count: plan.counts[kind] })}
                    />
                    {included[kind] && (
                      <div className="flex flex-wrap items-center gap-2 ps-7">
                        <SegmentedControl
                          options={SIZES.map(s => ({ value: s, label: t(`places.print.size.${s}`) }))}
                          value={c.size}
                          onChange={v => setSize(kind, v as PlaceStickerSize)}
                        />
                        {c.size === 'A6' && (
                          <SegmentedControl
                            options={[
                              { value: 'single', label: t('places.print.mode.single') },
                              { value: 'sheet', label: t('places.print.mode.sheet') },
                            ]}
                            value={c.a6Mode ?? 'single'}
                            onChange={v => setChoices(cs => ({ ...cs, [kind]: { size: 'A6', a6Mode: v as 'single' | 'sheet' } }))}
                          />
                        )}
                        <span className="text-caption text-muted">
                          {t(c.size === 'SMALL' ? 'places.print.labelPrinter' : 'places.print.paperPrinter')}
                        </span>
                      </div>
                    )}
                  </li>
                )
              })}
            </ul>
            <p className="text-small text-muted">{t('places.print.hint')}</p>
          </>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose} disabled={printing}>{t('places.cancel')}</Button>
          <Button iconStart={Printer} onClick={print} loading={printing} disabled={!plan || chosen.length === 0}>
            {t('places.print.print')}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
