import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Badge, Button, cn } from '../../components/ui'
import { patchOnboardingPrefs, updateTenantSettings, type PickPackMode } from '../../api'

/**
 * Onboarding O5 — "How do you want to pack?" (design/onboarding/Traced_onboarding_PickPack-Mode).
 *
 * First time (FulfillRoute decides when): Use <mode> → the existing PUT /tenant/settings {pickPackMode} (owner only),
 * then PATCH /onboarding/prefs {pickPackChosen: true}; "Skip for now (uses Order queue)" → the PATCH only — the
 * mode is never written. Replay (the page's "?"): the same two cards; the owner can switch, anyone else reads it
 * with "Only the store owner can change this". "Recommended for Bosta" only with Bosta connected.
 */
export default function PickPackChooser({ current, bostaConnected, canChange, replay, onDone }: {
  current: PickPackMode
  bostaConnected: boolean
  canChange: boolean
  replay: boolean
  onDone: (mode: PickPackMode) => void
}) {
  const { t } = useTranslation()
  const [sel, setSel] = useState<PickPackMode>(replay ? current : bostaConnected ? 'waybill_scan' : 'order_queue')
  const [busy, setBusy] = useState<'use' | 'skip' | null>(null)
  const [error, setError] = useState(false)

  async function use() {
    setBusy('use'); setError(false)
    try {
      await updateTenantSettings({ pickPackMode: sel })
    } catch {
      setError(true); setBusy(null); return
    }
    await patchOnboardingPrefs({ pickPackChosen: true }).catch(() => { /* the mode is saved; the chooser may show once more */ })
    onDone(sel)
  }

  async function skip() {
    setBusy('skip')
    await patchOnboardingPrefs({ pickPackChosen: true }).catch(() => { /* nothing else is written either way */ })
    onDone(current)
  }

  const cards: { mode: PickPackMode; key: 'waybill' | 'queue' }[] = [
    { mode: 'waybill_scan', key: 'waybill' },
    { mode: 'order_queue', key: 'queue' },
  ]
  const chosenTitle = t(`pickPackChooser.${sel === 'waybill_scan' ? 'waybill' : 'queue'}.title`)

  return (
    <div className="max-w-[920px] mx-auto flex flex-col gap-5 py-2" data-testid="pick-pack-chooser" data-replay={replay}>
      <div className="flex flex-col gap-1.5">
        <span className="text-caption font-bold uppercase tracking-wide text-trace-blue">{t('pickPackChooser.eyebrow')}</span>
        <h1 className="text-h1 text-primary">{t('pickPackChooser.title')}</h1>
        <p className="text-body text-muted">{t('pickPackChooser.lead')}</p>
      </div>

      <div className="grid gap-3.5 md:grid-cols-2" role="group" aria-label={t('pickPackChooser.title')}>
        {cards.map(c => {
          const on = sel === c.mode
          const Tag = canChange ? 'button' : 'div'
          return (
            <Tag key={c.mode} {...(canChange ? { type: 'button' as const, onClick: () => setSel(c.mode), 'aria-pressed': on } : {})}
              data-testid={`pick-pack-mode-${c.mode}`}
              className={cn('card text-start p-5 flex flex-col gap-3 transition-colors',
                on ? 'border-trace-blue bg-trace-blue/[0.06]' : canChange ? 'hover:border-trace-blue' : '')}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-h3 text-primary">{t(`pickPackChooser.${c.key}.title`)}</span>
                <span className="flex gap-1.5">
                  {c.mode === 'waybill_scan' && bostaConnected && (
                    <span data-testid="pick-pack-recommended"><Badge tone="info" label={t('pickPackChooser.recommendedBosta')} /></span>
                  )}
                  {replay && c.mode === current && <Badge tone="neutral" label={t('pickPackChooser.current')} />}
                </span>
              </div>
              <p className="text-small text-primary/80">{t(`pickPackChooser.${c.key}.desc`)}</p>
              <ol className="flex flex-col gap-1.5">
                {(['s1', 's2', 's3'] as const).map((s, i) => (
                  <li key={s} className="flex items-center gap-2.5 text-small text-primary">
                    <span className="w-5 h-5 flex-shrink-0 rounded-full bg-elevated text-caption font-semibold flex items-center justify-center" aria-hidden>{i + 1}</span>
                    {t(`pickPackChooser.${c.key}.${s}`)}
                  </li>
                ))}
              </ol>
              <p className="text-caption text-muted border-t border-line pt-3">{t(`pickPackChooser.${c.key}.best`)}</p>
            </Tag>
          )
        })}
      </div>
      <p className="text-small text-muted">{t('pickPackChooser.selfPickup')}</p>
      {!canChange && <p className="text-small text-muted" data-testid="pick-pack-owner-only">{t('pickPackChooser.ownerOnly')}</p>}
      {error && <p role="alert" className="text-small text-critical-text">{t('pickPackChooser.error')}</p>}

      <div className="flex flex-wrap items-center justify-end gap-3">
        {replay ? (
          <Button variant="secondary" onClick={() => onDone(current)} disabled={busy !== null}>{t('pickPackChooser.close')}</Button>
        ) : (
          <Button variant="secondary" onClick={() => { void skip() }} loading={busy === 'skip'} disabled={busy !== null}>
            {t('pickPackChooser.skip')}
          </Button>
        )}
        {canChange && (
          <Button onClick={() => { void use() }} loading={busy === 'use'} disabled={busy !== null}>
            {t('pickPackChooser.use', { mode: chosenTitle })}
          </Button>
        )}
      </div>
    </div>
  )
}
