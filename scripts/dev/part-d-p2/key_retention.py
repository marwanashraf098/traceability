"""Part D P2, question 2 — how long does Shopify keep an idempotency key (with Traced's own app)?

    python3 -I scripts/dev/part-d-p2/key_retention.py plant MAIN_LOCATION_GID ITEM_GID
    python3 -I scripts/dev/part-d-p2/key_retention.py check            # run again at / after each horizon

plant: one +1 per horizon (1, 6, 20, 24, 48, 72 h), each with its own key and NO compare-and-set (S0: without
compare-and-set an identical re-send is a true replay), then ONE −6 to put the quantity back. Keys + the exact inputs go
to p2-keys.json next to this script (dev store data only — no token).
check: for every key whose horizon has passed and that isn't checked yet, the IDENTICAL request again. A replay (same
adjustment group, quantity unchanged) = Shopify still held the key at that age. A new adjustment (quantity +1) = the
key had expired; the +1 is put straight back with compare-and-set. Each key is checked once.

ITEM must be ACTIVE at MAIN (any quantity). Dev store only (traced_dev refuses anything else).
"""
import json
import os
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from traced_dev import level, adjust

STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "p2-keys.json")
HORIZONS_H = [1, 6, 20, 24, 48, 72]


def load():
    if not os.path.exists(STATE):
        return []
    with open(STATE, encoding="utf-8") as f:
        return json.load(f)


def save(rows):
    with open(STATE, "w", encoding="utf-8") as f:
        json.dump(rows, f, indent=1)


mode = sys.argv[1] if len(sys.argv) > 1 else ""
if mode == "plant":
    MAIN, ITEM = sys.argv[2], sys.argv[3]
    if load():
        raise SystemExit(f"Refusing: {STATE} already holds planted keys — finish the checks (or move the file) first")
    start = level(ITEM, MAIN)
    if not start or not start["active"]:
        raise SystemExit("Refusing: ITEM must be ACTIVE at MAIN")
    rows = []
    for h in HORIZONS_H:
        key, ref = str(uuid.uuid4()), f"traced://part-d-p2/key-retention/{h}h"
        grp, codes = adjust(f"plant_{h}h", ITEM, MAIN, +1, None, key, ref=ref)
        if grp is None:
            save(rows)
            raise SystemExit(f"plant_{h}h did not apply ({codes}) — stopping; {len(rows)} key(s) planted")
        rows.append({"horizon_h": h, "key": key, "ref": ref, "item": ITEM, "location": MAIN,
                     "planted_at": time.time(), "group_id": grp["id"], "checked": None})
        save(rows)
    now = level(ITEM, MAIN)
    grp, codes = adjust("plant_restore", ITEM, MAIN, -len(rows), now["available"], str(uuid.uuid4()))
    print("start", start, "end", level(ITEM, MAIN), "restore", "applied" if grp else codes)
    print(f"planted {len(rows)} keys; run `check` after each horizon: {HORIZONS_H} h")
elif mode == "check":
    rows = load()
    if not rows:
        raise SystemExit("nothing planted")
    for r in rows:
        age_h = (time.time() - r["planted_at"]) / 3600
        if r["checked"] or age_h < r["horizon_h"]:
            continue
        before = level(r["item"], r["location"])
        grp, codes = adjust(f"check_{r['horizon_h']}h", r["item"], r["location"], +1, None, r["key"], ref=r["ref"])
        after = level(r["item"], r["location"])
        replayed = grp is not None and grp["id"] == r["group_id"] and after["available"] == before["available"]
        r["checked"] = {"age_h": round(age_h, 2), "replayed": replayed, "errors": codes,
                        "group_id": grp and grp["id"], "before": before, "after": after}
        save(rows)
        if not replayed and grp is not None:
            adjust(f"check_{r['horizon_h']}h_restore", r["item"], r["location"], -1, after["available"], str(uuid.uuid4()))
        print(f"{r['horizon_h']} h key at {age_h:.1f} h: {'HELD (replayed)' if replayed else 'EXPIRED (applied again — put back)' if grp else 'error ' + str(codes)}")
    print("\n==== P2 question 2")
    for r in rows:
        print(f"{r['horizon_h']:>3} h: {r['checked'] and ('held' if r['checked']['replayed'] else 'expired/other') or 'not yet'}"
              f"{'' if not r['checked'] else ' at ' + str(r['checked']['age_h']) + ' h'}")
else:
    raise SystemExit(__doc__)
