import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Printer, Tag } from 'lucide-react'
import {
  openPackLabelsPdf, PackLabelLine, PackLabelRequest, PackLabelScope, PackLabelSummary, PackOrderLabels,
  previewPackLabels, printPackLabels, printPackReplacement, PrintSort, ServerMessageError,
} from '../../api'
import { Alert, Badge, Button, Modal, Radio, SegmentedControl, cn } from '../../components/ui'

// Flexible labeling slice 4 — packing labels in Pick & Pack. Everything here renders only for a switched-on tenant:
// the pack screens show it when the order detail carries the label fields (the server adds them only then), the
// queue when /me says flexibleLabeling. Nothing here touches a scan input, useScanner or AwbLinkDialog: the line
// highlight and the replacement button are plain UI around the screens' own (unchanged) scan handling.

/**
 * A refused pack scan's text: the server's own words when it sent them in both languages (the label refusals —
 * "This label is for …", voided, no untagged piece left), else the screen's translation of the code as before.
 */
export function scanRejectionText(
  t: (key: string, opts?: Record<string, unknown>) => string, lang: string,
  r: { code: string | null; message: string | null; messageAr?: string },
  prefixKey?: string,
): string {
  if (r.messageAr) return lang.startsWith('ar') ? r.messageAr : (r.message ?? r.messageAr)
  const code = r.code ?? 'ERROR'
  const plain = t(`fulfill.rejection.${code}`, { defaultValue: r.message ?? code })
  return prefixKey ? t(`${prefixKey}.${code}`, { defaultValue: plain }) : plain
}

const isOpen = (l: PackLabelLine) => l.quantity > 0 && l.allocated < l.quantity

/**
 * The highlighted line: the first unfilled one, or the one the packer tapped while it still has room. When the
 * highlighted line fills, it moves on to the next unfilled line after it (else the first unfilled one).
 */
export function usePackLineHighlight(lines: PackLabelLine[]) {
  const [chosen, setChosen] = useState<string | null>(null)
  const open = lines.filter(isOpen)
  let highlighted: string | null
  if (chosen && open.some(l => l.id === chosen)) {
    highlighted = chosen
  } else if (chosen) {
    const at = lines.findIndex(l => l.id === chosen)
    highlighted = (lines.slice(at + 1).find(isOpen) ?? open[0])?.id ?? null
  } else {
    highlighted = open[0]?.id ?? null
  }
  return { highlighted, select: (id: string) => { if (open.some(l => l.id === id)) setChosen(id) } }
}

/** True when the order detail came with label fields (a switched-on tenant). */
export function hasLabelFields(labels: PackOrderLabels | undefined): labels is PackOrderLabels {
  return labels != null
}

/** "Label printed" / "Needs label" on an unfilled line whose product has untagged stock. */
export function LineLabelStatus({ line }: { line: PackLabelLine }) {
  const { t } = useTranslation()
  if (line.labelsUnused === undefined || !isOpen(line)) return null
  if (line.labelsUnused > 0) {
    return (
      <span className="mt-1.5 flex" data-testid={`line-label-${line.id}`}>
        <Badge tone="info" label={t('fulfill.labels.printed', { count: line.labelsUnused })} />
      </span>
    )
  }
  if ((line.untaggedStock ?? 0) === 0) return null
  return (
    <span className="mt-1.5 flex flex-col items-start gap-1" data-testid={`line-label-${line.id}`}>
      <span><Badge tone="warning" label={t('fulfill.labels.needs')} /></span>
      <span className="text-caption text-muted">{t('fulfill.labels.needsHint')}</span>
    </span>
  )
}

/** The order's label counter and the ONE printing action inside a pack screen: "Print 1 replacement label". */
export function PackLabelsBar({ orderId, labels, line, lineName, onPrinted }: {
  orderId: string
  labels: PackOrderLabels
  /** The highlighted line (the replacement is for its product), or null when every line is filled. */
  line: PackLabelLine | null
  lineName: string
  onPrinted: () => void
}) {
  const { t, i18n } = useTranslation()
  const [printing, setPrinting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const canPrint = !!line && (line.untaggedStock ?? 0) > 0

  async function replacement() {
    if (!line || printing) return
    setPrinting(true); setError(null)
    try {
      await printPackReplacement(orderId, line.id, crypto.randomUUID())
      onPrinted()
    } catch (e: unknown) {
      setError(e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('fulfill.labels.error'))
    } finally {
      setPrinting(false)
    }
  }

  return (
    <div className="space-y-2" data-testid="pack-labels-bar">
      <div className="flex flex-wrap items-center gap-3 rounded-xl border border-line bg-panel px-4 py-2.5">
        <Tag size={16} strokeWidth={2} className="text-trace-blue flex-shrink-0" />
        <span className="text-small text-primary flex-1 min-w-0" data-testid="pack-labels-counter">
          {t('fulfill.labels.counter', { used: labels.used, printed: labels.printed })}
        </span>
        {line && (
          <span className="text-caption text-muted min-w-0 truncate">
            {t('fulfill.labels.forLine', { name: lineName })}
          </span>
        )}
        <Button variant="secondary" size="sm" iconStart={Printer} loading={printing} disabled={!canPrint}
          onClick={replacement}>
          {t('fulfill.labels.replacement')}
        </Button>
      </div>
      {error && <Alert tone="critical" title={error} dismissible onDismiss={() => setError(null)} />}
    </div>
  )
}

/** Ring + tap target for the highlighted line (plain styling; the screen's scan input keeps focus). */
export function lineHighlightClass(highlighted: boolean) {
  return highlighted ? 'border-trace-blue ring-2 ring-trace-blue/30' : ''
}

/** "Product · Variant" as the pack screens write it. */
export function lineDisplayName(l: { product_title: string; variant_title: string }) {
  return [l.product_title, l.variant_title && l.variant_title !== 'Default Title' ? l.variant_title : null]
    .filter(Boolean).join(' · ')
}

// ── "Print labels" (queue) ─────────────────────────────────────────────────────

/**
 * The queue's "Print labels": "New only" = the waybill print's own orders (Bosta waybills not printed yet),
 * "Everything ready to pack" = every order in the Pick & Pack queue (any courier, self-pickup), "Same orders as
 * the last waybill print" = that print's orders — or one order (`orderId`). Shows "N labels for M orders" before printing, then opens the PDF part
 * by part (each ≤ 2,000 labels, never splitting an order).
 */
export function PrintLabelsDialog({ orderId, orderNumber, onClose }: {
  orderId?: string
  orderNumber?: string | null
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const [scope, setScope] = useState<PackLabelScope>('new')
  const [sort, setSort] = useState<PrintSort>('oldest')
  const [summary, setSummary] = useState<PackLabelSummary | null>(null)
  const [printed, setPrinted] = useState<PackLabelSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [opening, setOpening] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  const request: PackLabelRequest = orderId ? { orderId } : { scope, sort }
  const readable = (e: unknown) => e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('fulfill.labels.error')

  useEffect(() => {
    let cancelled = false
    setLoading(true); setError(null)
    previewPackLabels(request)
      .then(s => { if (!cancelled) setSummary(s) })
      .catch(e => { if (!cancelled) setError(readable(e)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderId, scope, sort])

  async function openPart(index: number, s: PackLabelSummary) {
    setOpening(index); setError(null)
    try { await openPackLabelsPdf(s.parts[index].orderIds) }
    catch (e: unknown) { setError(readable(e)) }
    finally { setOpening(null) }
  }

  async function print() {
    if (busy || !summary || summary.labels === 0) return
    setBusy(true); setError(null)
    try {
      const s = await printPackLabels(request)
      setPrinted(s)
      if (s.parts.length === 1) await openPart(0, s)
    } catch (e: unknown) {
      setError(readable(e))
    } finally {
      setBusy(false)
    }
  }

  const scopeOption = (value: PackLabelScope, label: string, hint: string) => (
    <label className={cn('flex items-center gap-3 rounded-xl border p-3 cursor-pointer transition-colors',
      scope === value ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line hover:bg-elevated')}>
      <Radio value={value} checked={scope === value} onChange={v => setScope(v as PackLabelScope)} />
      <span className="flex-1 min-w-0">
        <span className="block text-body font-semibold text-primary">{label}</span>
        <span className="block text-small text-muted">{hint}</span>
      </span>
    </label>
  )

  const title = orderId
    ? t('fulfill.labels.dialog.titleOrder', { number: orderNumber ?? '' })
    : t('fulfill.labels.dialog.title')

  return (
    <Modal title={title} onClose={onClose}>
      <div className="space-y-4" data-testid="print-labels-dialog">
        {printed ? (
          <>
            <Alert tone="success" title={t('fulfill.labels.dialog.done', { count: printed.labels, orders: printed.orders })}>
              {printed.parts.length > 1 ? t('fulfill.labels.dialog.partsHint', { count: printed.parts.length }) : undefined}
            </Alert>
            <div className="flex flex-col gap-2">
              {printed.parts.map((p, i) => (
                <Button key={p.part} variant="secondary" iconStart={Printer} loading={opening === i}
                  onClick={() => openPart(i, printed)}>
                  {printed.parts.length > 1
                    ? t('fulfill.labels.dialog.openPart', { part: p.part, of: p.of, count: p.labels })
                    : t('fulfill.labels.dialog.openAgain')}
                </Button>
              ))}
            </div>
            {error && <Alert tone="critical" title={error} />}
            <Button className="w-full" onClick={onClose}>{t('fulfill.printBatch.done')}</Button>
          </>
        ) : (
          <>
            <p className="text-small text-muted">
              {orderId ? t('fulfill.labels.dialog.introOrder') : t('fulfill.labels.dialog.intro')}
            </p>
            {!orderId && (
              <>
                <div className="space-y-2">
                  <p className="text-small font-semibold text-primary">{t('fulfill.printBatch.which')}</p>
                  {scopeOption('new', t('fulfill.printBatch.newOnly'), t('fulfill.labels.dialog.newHint'))}
                  {scopeOption('all', t('fulfill.printBatch.everything'), t('fulfill.labels.dialog.allHint'))}
                  {scopeOption('last', t('fulfill.labels.dialog.last'), t('fulfill.labels.dialog.lastHint'))}
                </div>
                {scope !== 'last' && (
                  <div className="space-y-1.5">
                    <p className="text-small font-semibold text-primary">{t('fulfill.printBatch.order')}</p>
                    <SegmentedControl value={sort} onChange={v => setSort(v as PrintSort)} options={[
                      { value: 'oldest', label: t('fulfill.printBatch.oldest') },
                      { value: 'newest', label: t('fulfill.printBatch.newest') },
                    ]} />
                  </div>
                )}
              </>
            )}

            <div className="rounded-xl border border-line p-3 space-y-1" data-testid="print-labels-summary" aria-busy={loading}>
              {summary && !loading ? (
                <>
                  <p className="text-body font-semibold text-primary">
                    {t('fulfill.labels.dialog.summary', { count: summary.labels, orders: summary.orders })}
                  </p>
                  {summary.unitsWithoutLabel > 0 && (
                    <p className="text-small text-muted">
                      {t('fulfill.labels.dialog.fromLabeled', { count: summary.unitsWithoutLabel })}
                    </p>
                  )}
                  {summary.reused > 0 && (
                    <p className="text-small text-muted">{t('fulfill.labels.dialog.reused', { count: summary.reused })}</p>
                  )}
                  {summary.parts.length > 1 && (
                    <p className="text-small text-muted">{t('fulfill.labels.dialog.parts', { count: summary.parts.length })}</p>
                  )}
                  {summary.orders === 0 && summary.queueNeedsLabels && (
                    <p className="text-small text-warning font-medium" data-testid="print-labels-queue-hint">
                      {t('fulfill.labels.dialog.queueHint')}
                    </p>
                  )}
                </>
              ) : (
                <p className="text-small text-muted">{t('fulfill.labels.dialog.counting')}</p>
              )}
            </div>

            {error && <Alert tone="critical" title={error} />}

            <div className="flex gap-2 pt-1">
              <Button variant="secondary" className="flex-1" onClick={onClose} disabled={busy}>{t('common.cancel')}</Button>
              <Button className="flex-1" iconStart={Printer} loading={busy}
                disabled={loading || !summary || summary.labels === 0} onClick={print}>
                {t('fulfill.labels.dialog.print', { count: summary?.labels ?? 0 })}
              </Button>
            </div>
          </>
        )}
      </div>
    </Modal>
  )
}
