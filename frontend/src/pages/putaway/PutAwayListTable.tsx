import { Fragment } from 'react'
import { useTranslation } from 'react-i18next'
import { Check } from 'lucide-react'
import type { PutAwayListPiece } from '../../api'
import { cn } from '../../components/ui'
import { placePath } from './placePath'
import { NO_SUGGESTION, groupPutAwayRows, putAwayRows } from './putAwayListRows'

// Warehouse places S4 — the put-away list table: the screen AND the print (the page's @media print rules apply, the
// same A6 / A4 sheet styling as the gather list). Zone chips, then per zone its rows in walking order — place (+ path,
// and why it's suggested), item, SKU, quantity (placed / total), piece codes; "No suggestion" last. A row whose pieces
// are all placed is ticked; a placed piece's code is struck through. `compact` (the scan screen): no zone chips, no
// SKU column.

export default function PutAwayListTable({ pieces, compact = false }: { pieces: PutAwayListPiece[]; compact?: boolean }) {
  const { t } = useTranslation()
  const groups = groupPutAwayRows(putAwayRows(pieces))
  const zoneLabel = (zone: string) => (zone === NO_SUGGESTION ? t('putAway.noSuggestion') : t('putAway.list.zone', { zone }))
  const cols = compact ? 4 : 5

  return (
    <>
      <style>{`
        @media print {
          .put-away-zone-row { background: #e8e8e8 !important; }
          .put-away-zone-row td { color: #111111 !important; }
          .put-away-zone-chip { background: #ffffff !important; border-color: #cccccc !important; color: #111111 !important; }
          .put-away-done { color: #666666 !important; }
        }
      `}</style>
      {!compact && (
        <div className="flex flex-wrap gap-2 mb-3" data-testid="put-away-zone-summary">
          {groups.map(g => {
            const qty = g.rows.reduce((n, r) => n + r.qty, 0)
            const done = g.rows.reduce((n, r) => n + r.done, 0)
            return (
              <span key={g.zone} data-testid="put-away-zone-chip"
                className="put-away-zone-chip inline-flex items-center gap-1.5 rounded-full border border-line bg-elevated px-3 py-1 text-small text-primary">
                <bdi className="font-semibold">{zoneLabel(g.zone)}</bdi>
                <span aria-hidden>·</span>
                <span>{t('putAway.list.placedOf', { placed: done, total: qty })}</span>
              </span>
            )
          })}
        </div>
      )}
      <div className="card overflow-hidden p-0">
        <table className="w-full" data-testid="put-away-list-table">
          <thead className="bg-elevated border-b border-line">
            <tr>
              <th className="tbl-header">{t('putAway.list.colPlace')}</th>
              <th className="tbl-header">{t('putAway.list.colItem')}</th>
              {!compact && <th className="tbl-header hidden sm:table-cell print:table-cell">{t('putAway.list.colSku')}</th>}
              <th className="tbl-header text-end">{t('putAway.list.colQty')}</th>
              <th className="tbl-header">{t('putAway.list.colPieces')}</th>
            </tr>
          </thead>
          <tbody>
            {groups.map(g => (
              <Fragment key={g.zone}>
                <tr className="put-away-zone-row bg-elevated/60 border-b border-line" data-testid="put-away-zone-row">
                  <td colSpan={cols} className="tbl-cell py-1.5 text-small font-semibold text-primary">
                    <bdi>{zoneLabel(g.zone)}</bdi>
                  </td>
                </tr>
                {g.rows.map(r => {
                  const allDone = r.done === r.qty
                  return (
                    <tr key={r.key} data-testid="put-away-list-row" data-done={allDone ? 'true' : 'false'}
                      className={cn('tbl-row', allDone && 'put-away-done')}>
                      <td className="tbl-cell align-top">
                        {r.place ? (
                          <>
                            <p className={cn('font-mono font-semibold whitespace-nowrap', allDone ? 'text-muted' : 'text-primary')}>
                              <bdi>{r.place.code}</bdi>
                            </p>
                            <p className="text-caption text-muted"><bdi>{placePath(r.place, t)}</bdi></p>
                            {r.reason && (
                              <p className="text-caption text-muted" data-testid="put-away-row-reason">
                                {t(`putAway.list.reason.${r.reason}`)}
                              </p>
                            )}
                          </>
                        ) : (
                          <p className="text-muted">{t('putAway.noSuggestion')}</p>
                        )}
                      </td>
                      <td className="tbl-cell align-top">
                        <p className={cn('font-medium', allDone ? 'text-muted' : 'text-primary')}>
                          <bdi>{[r.productTitle, r.variantTitle && r.variantTitle !== 'Default Title' ? r.variantTitle : null]
                            .filter(Boolean).join(' · ')}</bdi>
                        </p>
                      </td>
                      {!compact && (
                        <td className="tbl-cell align-top text-muted hidden sm:table-cell print:table-cell">
                          {r.sku ?? t('common.na')}
                        </td>
                      )}
                      <td className="tbl-cell align-top text-end font-mono whitespace-nowrap" data-testid="put-away-row-qty">
                        {allDone
                          ? <span className="inline-flex items-center gap-1 text-success"><Check size={14} strokeWidth={2.5} />{r.qty}</span>
                          : r.done > 0 ? `${r.done} / ${r.qty}` : r.qty}
                      </td>
                      <td className="tbl-cell align-top">
                        <span className="flex flex-wrap gap-x-2 gap-y-0.5 font-mono text-small" dir="ltr">
                          {r.pieces.map(p => (
                            <span key={p.pieceId} data-testid="put-away-piece-code" data-done={p.done ? 'true' : 'false'}
                              className={p.done ? 'line-through text-muted' : 'text-primary'}>{p.code}</span>
                          ))}
                        </span>
                      </td>
                    </tr>
                  )
                })}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}
