import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { useScanner, ScanOutcome } from '../hooks/useScanner'
import { ScanShell } from '../components/ScanShell'
import { Button } from '../components/ui'

// S2 — scanner probe (owner / manager, /scan-probe, not in the sidebar, no backend calls).
// A real-device check of layout- and Caps-Lock-independent capture: the input is a normal
// useScanner + ScanShell input, so what it captures is exactly what every scan screen
// captures. Each keydown is logged with what the browser reported and whether the scan
// listener handled it (it runs first, on the document, in the capture phase — this page's
// own listener on the input sees `defaultPrevented`). On Enter the captured scan is shown
// next to the text the layout would have typed without the listener.

interface KeyRow {
  n: number
  code: string
  key: string
  shiftKey: boolean
  capsLock: boolean
  keyCode: number
  isComposing: boolean
  handled: boolean
}

interface ScanRow {
  n: number
  captured: string
  raw: string
}

/** Code points of a string, for glyphs that are hard to read (U+061B …). */
function codePoints(s: string): string {
  return [...s].map(c => (c.charCodeAt(0) < 0x80 && c !== ' ' ? c : 'U+' + c.codePointAt(0)!.toString(16).toUpperCase().padStart(4, '0'))).join(' ')
}

/** What the layout itself would have inserted for this keydown (named keys insert nothing). */
function layoutText(e: KeyboardEvent): string {
  if (e.ctrlKey || e.metaKey || e.altKey) return ''
  if (e.key === 'Unidentified' || e.key === 'Dead') return ''
  if (e.key.length > 1 && /^[A-Z][A-Za-z0-9]*$/.test(e.key)) return ''
  return e.key
}

export default function ScanProbe() {
  const { t } = useTranslation()
  const [keys, setKeys] = useState<KeyRow[]>([])
  const [scans, setScans] = useState<ScanRow[]>([])
  const rawBuffer = useRef('')
  const rawPerScan = useRef<string[]>([])
  const counter = useRef(0)

  const onScan = useCallback(async (code: string): Promise<ScanOutcome> => {
    const raw = rawPerScan.current.shift() ?? ''
    setScans(prev => [{ n: ++counter.current, captured: code, raw: raw.trim() }, ...prev].slice(0, 50))
    return { success: true }
  }, [])
  const scanner = useScanner({ onScan })

  useEffect(() => {
    const input = scanner.inputRef.current
    if (!input) return
    const onKeyDown = (e: KeyboardEvent) => {
      setKeys(prev => [{
        n: prev.length ? prev[0].n + 1 : 1,
        code: e.code, key: e.key, shiftKey: e.shiftKey,
        capsLock: e.getModifierState?.('CapsLock') ?? false,
        keyCode: e.keyCode, isComposing: e.isComposing,
        handled: e.defaultPrevented,
      }, ...prev].slice(0, 200))
      if (e.key === 'Enter') {
        rawPerScan.current.push(rawBuffer.current)
        rawBuffer.current = ''
      } else {
        rawBuffer.current += layoutText(e)
      }
    }
    input.addEventListener('keydown', onKeyDown)
    return () => input.removeEventListener('keydown', onKeyDown)
  }, [scanner.inputRef])

  function clear() {
    setKeys([])
    setScans([])
    rawBuffer.current = ''
    rawPerScan.current = []
    counter.current = 0
    scanner.clearRecentScans()
  }

  return (
    <div className="min-h-screen bg-base p-6">
      <div className="max-w-5xl mx-auto space-y-5">
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <Link to="/overview" className="text-muted hover:text-primary" aria-label={t('scanProbe.back')}>
              <ArrowLeft size={20} className="rtl:rotate-180" />
            </Link>
            <div>
              <h1 className="text-h3 font-semibold">{t('scanProbe.title')}</h1>
              <p className="text-small text-muted">{t('scanProbe.subtitle')}</p>
            </div>
          </div>
          <Button variant="secondary" size="sm" onClick={clear}>{t('scanProbe.clear')}</Button>
        </div>

        <ScanShell scanner={scanner} placeholder={t('scanProbe.placeholder')} />

        <section className="card p-4" dir="ltr">
          <h2 className="text-body font-semibold mb-2">{t('scanProbe.scansTitle')}</h2>
          {scans.length === 0 ? (
            <p className="text-small text-muted">{t('scanProbe.noScans')}</p>
          ) : (
            <table className="w-full text-small" data-testid="probe-scans">
              <thead>
                <tr className="text-start text-muted">
                  <th className="text-start py-1 w-10">#</th>
                  <th className="text-start py-1">{t('scanProbe.captured')}</th>
                  <th className="text-start py-1">{t('scanProbe.raw')}</th>
                </tr>
              </thead>
              <tbody>
                {scans.map(s => (
                  <tr key={s.n} className="border-t border-line align-top">
                    <td className="py-1.5 text-muted">{s.n}</td>
                    <td className="py-1.5 font-mono" data-testid="probe-captured">
                      <div>{s.captured}</div>
                      <div className="text-muted text-caption">{codePoints(s.captured)}</div>
                    </td>
                    <td className="py-1.5 font-mono" data-testid="probe-raw">
                      <div>{s.raw || '—'}</div>
                      <div className="text-muted text-caption">{codePoints(s.raw)}</div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <section className="card p-4 overflow-x-auto" dir="ltr">
          <h2 className="text-body font-semibold mb-2">{t('scanProbe.keysTitle')}</h2>
          <table className="w-full text-small font-mono" data-testid="probe-keys">
            <thead>
              <tr className="text-muted">
                <th className="text-start py-1">#</th>
                <th className="text-start py-1">code</th>
                <th className="text-start py-1">key</th>
                <th className="text-start py-1">shiftKey</th>
                <th className="text-start py-1">CapsLock</th>
                <th className="text-start py-1">keyCode</th>
                <th className="text-start py-1">isComposing</th>
                <th className="text-start py-1">{t('scanProbe.listener')}</th>
              </tr>
            </thead>
            <tbody>
              {keys.map(k => (
                <tr key={k.n} className="border-t border-line">
                  <td className="py-1 text-muted">{k.n}</td>
                  <td className="py-1">{k.code || '""'}</td>
                  <td className="py-1">{JSON.stringify(k.key)} <span className="text-muted">{codePoints(k.key)}</span></td>
                  <td className="py-1">{String(k.shiftKey)}</td>
                  <td className="py-1">{String(k.capsLock)}</td>
                  <td className="py-1">{k.keyCode}</td>
                  <td className="py-1">{String(k.isComposing)}</td>
                  <td className={`py-1 font-semibold ${k.handled ? 'text-success-text' : 'text-muted'}`}>
                    {k.handled ? t('scanProbe.handled') : t('scanProbe.fellThrough')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>
    </div>
  )
}
