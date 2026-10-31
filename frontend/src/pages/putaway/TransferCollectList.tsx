import { useTranslation } from 'react-i18next'
import type { TransferLine, VariantPickPlaces } from '../../api'
import { placeNote } from '../fulfill/placeSplit'

// Warehouse places S4 — the send-out screen's collect list, display only. Each transfer line (variant · sent so far)
// with where that variant's AVAILABLE pieces still are at the main warehouse — S3's batch places: per place, most
// pieces first, then "No place yet" — lines in walking order by their first place, lines with no place last. A
// transfer has no planned lines (a line is created by its first scan), so the list grows as scanning goes on.

export interface CollectLine { line: TransferLine; places: VariantPickPlaces | null }

export function collectLines(lines: TransferLine[], places: Map<string, VariantPickPlaces>): CollectLine[] {
  const keyOf = (c: CollectLine) => c.places?.places[0]?.sortKey ?? null
  // A line whose variant isn't in the reply yet (scanned after the last places read) waits for the next read.
  return lines
    .filter(line => places.has(line.variant_id))
    .map((line, i) => ({ c: { line, places: places.get(line.variant_id) ?? null }, i }))
    .sort((a, b) => {
      const ka = keyOf(a.c), kb = keyOf(b.c)
      if (ka == null || kb == null) return (ka != null ? -1 : kb != null ? 1 : 0) || a.i - b.i
      return (ka < kb ? -1 : ka > kb ? 1 : 0) || a.i - b.i
    })
    .map(x => x.c)
}

export default function TransferCollectList({ lines, places }: { lines: TransferLine[]; places: Map<string, VariantPickPlaces> }) {
  const { t } = useTranslation()
  const rows = collectLines(lines, places)
  if (rows.length === 0) return null

  return (
    <div className="space-y-2" data-testid="transfer-collect-list">
      <h2 className="text-caption text-muted uppercase tracking-widest">{t('putAway.list.collectTitle')}</h2>
      <div className="card overflow-hidden">
        <table className="min-w-full">
          <tbody>
            {rows.map(({ line, places: p }) => {
              const none = (p?.places.length ?? 0) === 0 && (p?.noPlaceCount ?? 0) === 0
              return (
                <tr key={line.id} className="tbl-row" data-testid="transfer-collect-line">
                  <td className="tbl-cell align-top">
                    <p className="text-primary"><bdi>{line.product_title} · {line.variant_title}</bdi></p>
                    {line.sku && <p className="font-mono text-caption text-muted"><bdi>{line.sku}</bdi></p>}
                    <p className="text-caption text-muted">{t('putAway.list.collectSent', { count: line.qty_out })}</p>
                  </td>
                  <td className="tbl-cell align-top">
                    {none ? (
                      <p className="text-small text-muted">{t('putAway.list.collectNoneLeft')}</p>
                    ) : (
                      <ul className="space-y-1">
                        {p!.places.map(place => {
                          const note = placeNote(place, t)
                          return (
                            <li key={place.binId} data-testid="transfer-collect-place" className="flex items-baseline justify-between gap-3">
                              <span>
                                <span className="font-mono font-semibold text-primary whitespace-nowrap"><bdi>{place.code}</bdi></span>
                                {note && <span className="block text-caption text-muted">{note}</span>}
                              </span>
                              <span className="text-small text-muted whitespace-nowrap">{t('fulfill.places.pieces', { count: place.count })}</span>
                            </li>
                          )
                        })}
                        {p!.noPlaceCount > 0 && (
                          <li data-testid="transfer-collect-place" className="flex items-baseline justify-between gap-3">
                            <span className="text-muted">{t('fulfill.places.noPlace')}</span>
                            <span className="text-small text-muted whitespace-nowrap">{t('fulfill.places.pieces', { count: p!.noPlaceCount })}</span>
                          </li>
                        )}
                      </ul>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
