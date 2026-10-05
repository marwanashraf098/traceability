import { useEffect } from 'react'
import { usePhoneStatus } from './PhoneScanProvider'

// Phone as scanner — the authenticated-page signal. RequireAuth renders this beside every
// authenticated page; mounting it tells PhoneScanProvider a signed-in page is showing (load the
// tablet's pairing, open the relay stream), unmounting (login page, station gate) stops that.
// It renders nothing: the visible controls are PhoneScanButton (inside a scan screen's header)
// and PhoneTopbarIcon (Layout's top bar, only while paired) — there is no floating control.

export default function PhoneControl() {
  const setActive = usePhoneStatus()?.setActive
  useEffect(() => {
    if (!setActive) return
    setActive(true)
    return () => setActive(false)
  }, [setActive])
  return null
}
