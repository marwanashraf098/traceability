import { useCallback, useEffect, useRef, useState } from 'react'

// A tiny cache for analytics reads (no react-query): answers are kept per key for 60 s. A fresh
// cached answer is shown with no request; a stale one is shown while a refresh runs in the
// background; a window focus refreshes a stale answer. Changing the key (the period, a filter)
// aborts the request in flight. Errors are per call — one card failing never blanks another.

export const CACHE_TTL_MS = 60_000

interface Entry { at: number; data: unknown }
const cache = new Map<string, Entry>()

/** Tests only. */
export function clearAnalyticsCache() {
  cache.clear()
}

export interface QueryState<T> {
  data: T | undefined
  error: Error | null
  /** No answer yet (first load for this key). */
  loading: boolean
  /** A cached answer is shown while a newer one loads. */
  refreshing: boolean
  retry: () => void
}

function isAbort(e: unknown) {
  return e instanceof DOMException ? e.name === 'AbortError' : (e as { name?: string })?.name === 'AbortError'
}

/** `key` null = don't fetch (a dependency isn't known yet). */
export function useAnalyticsQuery<T>(key: string | null, fetcher: (signal: AbortSignal) => Promise<T>): QueryState<T> {
  const initial = key ? (cache.get(key)?.data as T | undefined) : undefined
  const [data, setData] = useState<T | undefined>(initial)
  const [error, setError] = useState<Error | null>(null)
  const [loading, setLoading] = useState<boolean>(key != null && initial === undefined)
  const [refreshing, setRefreshing] = useState(false)
  const [nonce, setNonce] = useState(0)
  const fetcherRef = useRef(fetcher)
  fetcherRef.current = fetcher

  useEffect(() => {
    if (key == null) { setData(undefined); setLoading(false); setError(null); return }
    const hit = cache.get(key)
    const fresh = hit && Date.now() - hit.at < CACHE_TTL_MS
    setData(hit?.data as T | undefined)
    setError(null)
    // retry deletes the entry and a focus refresh only fires when stale, so a fresh hit is final.
    if (fresh) { setLoading(false); setRefreshing(false); return }
    const ctl = new AbortController()
    setLoading(!hit)
    setRefreshing(!!hit)
    fetcherRef.current(ctl.signal).then(d => {
      if (ctl.signal.aborted) return
      cache.set(key, { at: Date.now(), data: d })
      setData(d)
      setLoading(false)
      setRefreshing(false)
    }, e => {
      if (ctl.signal.aborted || isAbort(e)) return
      setError(e instanceof Error ? e : new Error(String(e)))
      setLoading(false)
      setRefreshing(false)
    })
    return () => ctl.abort()
  }, [key, nonce])

  // A window focus refreshes a stale answer.
  useEffect(() => {
    if (key == null) return
    const onFocus = () => {
      const hit = cache.get(key)
      if (!hit || Date.now() - hit.at >= CACHE_TTL_MS) setNonce(n => n + 1)
    }
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
  }, [key])

  const retry = useCallback(() => {
    if (key) cache.delete(key)
    setNonce(n => n + 1)
  }, [key])

  return { data, error, loading, refreshing, retry }
}
