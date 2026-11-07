import { useEffect, useState } from 'react'
import type { HelpLibrary } from './articles'

/** Loads the help content chunk once (dynamic import). undefined while loading, null if it failed. */
let cached: Promise<HelpLibrary | null> | null = null

export function loadHelp(): Promise<HelpLibrary | null> {
  if (!cached) cached = import('./content').then(m => m.LIBRARY).catch(() => { cached = null; return null })
  return cached
}

export function useHelpLibrary(): HelpLibrary | null | undefined {
  const [lib, setLib] = useState<HelpLibrary | null | undefined>(undefined)
  useEffect(() => {
    let live = true
    loadHelp().then(l => { if (live) setLib(l) })
    return () => { live = false }
  }, [])
  return lib
}
