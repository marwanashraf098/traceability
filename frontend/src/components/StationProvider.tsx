import { createContext, useCallback, useContext, useState, ReactNode } from 'react'
import type { Me } from '../api'

const STATION_MODE_KEY = 'stationMode'

interface StationContextValue {
  /** Device-level flag — persisted in localStorage, same pattern as the 'lang' key
   *  (main.tsx:14-17). Survives reboot/reload. */
  stationMode: boolean
  /** The currently PIN-switched-in worker. In-memory only, like the access token —
   *  reset on every reload, which is exactly what makes "gate on every fresh open"
   *  work: even when /auth/refresh silently restores a worker's access token from a
   *  rotated cookie, currentWorker is null again until they re-enter their PIN. */
  currentWorker: Me | null
  enterStationMode: () => void
  exitStationMode: () => void
  signInWorker: (me: Me) => void
  signOutWorker: () => void
}

const StationContext = createContext<StationContextValue | undefined>(undefined)

export function useStation(): StationContextValue {
  const ctx = useContext(StationContext)
  if (ctx === undefined) throw new Error('useStation must be used within StationProvider')
  return ctx
}

/**
 * Root-level provider — MUST sit above the router so currentWorker survives route
 * navigation between worker screens (a fresh context per route would kick the worker
 * back to the gate on every navigation, which is not the intended behavior — only a
 * true reload should do that).
 */
export function StationProvider({ children }: { children: ReactNode }) {
  const [stationMode, setStationMode] = useState<boolean>(
    () => localStorage.getItem(STATION_MODE_KEY) === 'true'
  )
  const [currentWorker, setCurrentWorker] = useState<Me | null>(null)

  const enterStationMode = useCallback(() => {
    localStorage.setItem(STATION_MODE_KEY, 'true')
    setStationMode(true)
  }, [])

  const exitStationMode = useCallback(() => {
    localStorage.removeItem(STATION_MODE_KEY)
    setStationMode(false)
    setCurrentWorker(null)
  }, [])

  const signInWorker = useCallback((me: Me) => {
    setCurrentWorker(me)
  }, [])

  const signOutWorker = useCallback(() => {
    setCurrentWorker(null)
  }, [])

  return (
    <StationContext.Provider
      value={{ stationMode, currentWorker, enterStationMode, exitStationMode, signInWorker, signOutWorker }}
    >
      {children}
    </StationContext.Provider>
  )
}
