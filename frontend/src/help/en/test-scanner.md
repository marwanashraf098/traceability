---
id: test-scanner
topic: start
title: Test your barcode scanner
roles: [owner, manager]
related: [scanner-wrong-letters, missed-scans, phone-scanner]
alerts: []
screens: [/settings?tab=couriers, /lookup]
video:
---
A USB or Bluetooth barcode scanner works like a keyboard: it types the code and presses Enter. To test it, scan a real waybill under **Test a waybill** in **Settings › Couriers** and check that what Traced shows matches the number printed on it.

## Set it up

1. Plug in a USB scanner, or pair a Bluetooth scanner with the computer or tablet the way you'd pair a keyboard.
2. Make sure the scanner ends each scan with Enter. Most do out of the box; if yours doesn't, set its "suffix" to Enter using the setup barcodes in its manual.

## Test it

1. Open **Settings › Couriers** and find **Test a waybill**.
2. Click the box and scan a waybill. With Enter at the end, the test runs by itself.
3. Compare **Scanned** with the number printed on the waybill. If Traced knows the waybill, it also shows its order. Nothing is saved.

You can also scan a piece label or a waybill into the search box at the top of Traced (**Search barcode or AWB…**). Traced opens **Lookup** with that piece or order.

## If the result looks wrong

- **Odd letters or Arabic characters** — Caps Lock is on or the keyboard isn't set to English. See [Scanner types wrong letters](/help/scanner-wrong-letters).
- **Nothing happens until you press Enter** — the scanner isn't sending Enter. Change its suffix as above.
- **Some scans don't show up** — see [Some scans are missed](/help/missed-scans).

No scanner? Your phone's camera can do the job — see [Use your phone as a scanner](/help/phone-scanner). It reads Code 128 and QR barcodes only.
