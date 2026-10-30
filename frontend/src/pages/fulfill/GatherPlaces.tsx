import { Fragment } from 'react'
import { useTranslation } from 'react-i18next'
import type { GatherRow, VariantPickPlaces } from '../../api'
import { NO_PLACE, SplitPart, groupByZone, placeNote, sortByPlace, splitAcrossPlaces } from './placeSplit'

// Warehouse places S3 — the gather list with places (screen and print: the same table, GatherList's @media print
// rules apply). Each variant is split into one row per place (most pieces first, until the needed quantity is
// covered; the rest "No place yet"), rows grouped by zone in walking order, "No place yet" last. The GatherRow
// payload is unchanged — the split is done here from the batch places; the shortage note and highlight are today's,
// on every row of a short variant.

export interface GatherPlaceRow extends SplitPart { row: GatherRow }

export function gatherPlaceRows(rows: GatherRow[], places: Map<string, VariantPickPlaces>): GatherPlaceRow[] {
  return sortByPlace(rows.flatMap(row =>
    splitAcrossPlaces(row.needed, places.get(row.variantId)?.places ?? []).map(part => ({ ...part, row }))))
}

export default function GatherPlacesTable({ rows, places }: { rows: GatherRow[]; places: Map<string, VariantPickPlaces> }) {
  const { t } = useTranslation()
  const groups = groupByZone(gatherPlaceRows(rows, places))
  const zoneLabel = (zone: string) => (zone === NO_PLACE ? t('fulfill.places.noPlace') : t('fulfill.places.zone', { zone }))

  return (
    <>
      {/* Print additions only (GatherList's own @media print rules do the rest): zone headers and chips light. */}
      <style>{`
        @media print {
          .gather-zone-row { background: #e8e8e8 !important; }
          .gather-zone-row td { color: #111111 !important; }
          .gather-zone-chip { background: #ffffff !important; border-color: #cccccc !important; color: #111111 !important; }
        }
      `}</style>
      <div className="flex flex-wrap gap-2 mb-3" data-testid="gather-zone-summary">
        {groups.map(g => (
          <span key={g.zone} data-testid="gather-zone-chip"
            className="gather-zone-chip inline-flex items-center gap-1.5 rounded-full border border-line bg-elevated px-3 py-1 text-small text-primary">
            <bdi className="font-semibold">{zoneLabel(g.zone)}</bdi>
            <span aria-hidden>·</span>
            <span>{t('fulfill.places.pieces', { count: g.rows.reduce((n, r) => n + r.qty, 0) })}</span>
          </span>
        ))}
      </div>
      <div className="card overflow-hidden p-0">
        <table className="w-full" data-testid="gather-places-table">
          <thead className="bg-elevated border-b border-line">
            <tr>
              <th className="tbl-header">{t('fulfill.places.colPlace')}</th>
              <th className="tbl-header">{t('fulfill.gather.colItem')}</th>
              <th className="tbl-header hidden sm:table-cell print:table-cell">{t('fulfill.gather.colSku')}</th>
              <th className="tbl-header text-end">{t('fulfill.places.colTake')}</th>
              <th className="tbl-header text-end">{t('fulfill.gather.colAvailable')}</th>
              <th className="tbl-header hidden md:table-cell print:table-cell">{t('fulfill.gather.colOrders')}</th>
            </tr>
          </thead>
          <tbody>
            {groups.map(g => (
              <Fragment key={g.zone}>
                <tr className="gather-zone-row bg-elevated/60 border-b border-line" data-testid="gather-zone-row">
                  <td colSpan={6} className="tbl-cell py-1.5 text-small font-semibold text-primary">
                    <bdi>{zoneLabel(g.zone)}</bdi>
                  </td>
                </tr>
                {g.rows.map(r => {
                  const row = r.row
                  const note = r.place ? placeNote(r.place, t) : null
                  return (
                    <tr key={`${row.variantId}:${r.place?.binId ?? 'none'}`} data-testid="gather-place-row"
                      className={`tbl-row ${row.shortage ? 'bg-critical/[0.14]' : ''}`}>
                      <td className="tbl-cell">
                        {r.place
                          ? <p className="font-mono font-semibold text-primary whitespace-nowrap"><bdi>{r.place.code}</bdi></p>
                          : <p className="text-muted">{t('fulfill.places.noPlace')}</p>}
                        {note && <p className="text-caption text-muted">{note}</p>}
                        {r.of > 1 && (
                          <p className="text-caption text-muted">{t('fulfill.places.partOf', { index: r.index, of: r.of })}</p>
                        )}
                      </td>
                      <td className="tbl-cell">
                        <p className="font-medium text-primary">{row.displayName}</p>
                        {row.shortage && (
                          <p className="text-caption text-critical-text">
                            {t('fulfill.gather.shortageNote', {
                              shortfall: row.needed - row.availableCount,
                              needed: row.needed,
                              available: row.availableCount,
                            })}
                          </p>
                        )}
                      </td>
                      <td className="tbl-cell text-muted hidden sm:table-cell print:table-cell">
                        {row.sku ?? t('common.na')}
                      </td>
                      <td className="tbl-cell text-end font-mono">{r.qty}</td>
                      <td className={`tbl-cell text-end font-mono ${row.shortage ? 'text-critical-text font-semibold' : ''}`}>
                        {row.availableCount}
                      </td>
                      <td className="tbl-cell text-muted hidden md:table-cell print:table-cell">
                        {row.orderNumbers.join(', ')}
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
