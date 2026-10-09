import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { WatchedCustomer } from '../../analyticsApi'
import { useFmt } from '../../analytics/format'
import { Pill } from '../../components/analytics/ui'

/** Customers who refused 2+ COD orders — the backend's suggestion and a link to the blocklist (Delivery, Customers). */
export function WatchTable({ customers }: { customers: WatchedCustomer[] }) {
  const { t, i18n } = useTranslation()
  const fmt = useFmt()
  return (
    <div className="overflow-x-auto -mx-[18px] px-[18px]">
      <table className="w-full text-[13px] [&_td]:whitespace-nowrap" data-testid="watch-table">
        <thead>
          <tr className="border-b border-line">
            <th className="tbl-header text-start ps-0">{t('analytics.delivery.watchCols.customer')}</th>
            <th className="tbl-header text-start">{t('analytics.delivery.watchCols.governorate')}</th>
            <th className="tbl-header text-end">{t('analytics.delivery.watchCols.orders')}</th>
            <th className="tbl-header text-end">{t('analytics.delivery.watchCols.refused')}</th>
            <th className="tbl-header text-start pe-0">{t('analytics.delivery.watchCols.suggestion')}</th>
          </tr>
        </thead>
        <tbody>
          {customers.map(c => (
            <tr key={c.customerRef} className="border-b border-line last:border-0">
              <td className="py-[9px] ps-0 pe-2.5">{c.displayName ?? t('analytics.delivery.anonymous')}</td>
              <td className="py-[9px] px-2.5">{(i18n.language === 'ar' && c.governorateAr) || c.governorate || '—'}</td>
              <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(c.orders)}</td>
              <td className="py-[9px] px-2.5 text-end">
                <Pill kind={c.refusedCodOrders >= 3 ? 'crit' : 'warn'}>{t('analytics.delivery.refused', { count: c.refusedCodOrders })}</Pill>
              </td>
              <td className="py-[9px] ps-2.5 pe-0">
                {c.blocked
                  ? <span className="text-muted">{t('analytics.delivery.blocked')}</span>
                  : (
                    <span className="inline-flex items-center gap-2.5">
                      {t('analytics.delivery.suggestPrepay')}
                      <Link to={c.blocklistLink ?? '/blocklist'} className="text-trace-blue font-semibold hover:underline whitespace-nowrap">{t('analytics.delivery.toBlocklist')}</Link>
                    </span>
                  )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
