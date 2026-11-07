---
id: scanner-wrong-letters
topic: trouble
title: Scanner types wrong letters (Caps Lock or Arabic keyboard)
roles: [owner, manager, worker]
related: [test-scanner, missed-scans, phone-scanner]
alerts: []
screens: []
video:
---
A barcode scanner types like a keyboard, so the computer's keyboard settings change what arrives. Turn off Caps Lock and switch the keyboard language to English, then scan again.

## What you see

- Letters arrive in the wrong case — the label says `P000123` but `p000123` arrives. That's Caps Lock.
- Arabic letters, symbols or Arabic digits appear instead of the code. That's an Arabic keyboard layout.
- Traced says it can't find the piece or waybill, even though the label is fine.

## Fix it

1. Press Caps Lock once so its light is off.
2. Switch the keyboard to English on the computer or tablet the scanner is connected to — for example with the language switcher in the taskbar or menu bar.
3. Click into the scan box and scan the same label again.
4. To be sure, test with a real waybill under **Test a waybill** in **Settings › Couriers** and compare **Scanned** with the printed number — see [Test your barcode scanner](/help/test-scanner).

## Keep it from coming back

- On a shared warehouse computer, remove the Arabic keyboard from its language settings if nobody types Arabic on it, so it can't be switched by accident.
- **Tip:** if the scanner has its own keyboard-language setting, set it to the English (US) keyboard using the setup barcodes in its manual.

A phone paired as a scanner doesn't type through the keyboard, so these settings don't affect it — see [Use your phone as a scanner](/help/phone-scanner).
