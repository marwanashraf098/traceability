import { useTranslation } from 'react-i18next'
import { Barcode, Package, Tag } from 'lucide-react'
import { MiniStat } from './ui'

/** A variant's available pieces split by whether they carry a printed code (flexible labeling). */
export interface PieceSplit { available: number; labeled: number; untagged: number }

/**
 * Available / Labeled / Untagged for one variant, plus the note about untagged pieces when there
 * are any. Shown only for tenants with flexible labeling (the backend sends the split only then).
 */
export function UntaggedSplit({ split }: { split: PieceSplit }) {
  const { t } = useTranslation()
  return (
    <div className="space-y-3" data-testid="untagged-split">
      <div className="grid grid-cols-3 gap-3">
        <MiniStat icon={Package} value={split.available} label={t('untagged.available')} />
        <MiniStat icon={Barcode} value={split.labeled} label={t('untagged.labeled')} />
        <MiniStat icon={Tag} value={split.untagged} label={t('untagged.untagged')}
          tone={split.untagged > 0 ? 'warning' : 'neutral'} />
      </div>
      {split.untagged > 0 && (
        <p className="text-small text-muted" data-testid="untagged-note">
          {t('untagged.variantNote', { count: split.untagged })}
        </p>
      )}
    </div>
  )
}
