"""Part D P2, question 1 — does inventoryActivate re-activate an INACTIVE level, and does a RE-USED activation key
(what Traced sends today: ShopifyCatalogActivationService.activationKey, the catalog activation's key) replay as a
no-op? Also re-checks, with Traced's app, that an adjust silently lands on an inactive level and whether it shows
once the level is active again. Dev store only (traced_dev refuses anything else).

    python3 -I scripts/dev/part-d-p2/activation.py MAIN_LOCATION_GID ITEM_GID

ITEM must be ACTIVE at MAIN with available = on_hand = committed = reserved = 0 (the probe refuses otherwise — it
never moves real stock). It ends where it started: active at MAIN, 0.
"""
import os
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from traced_dev import level, adjust, activate, deactivate

MAIN, ITEM = sys.argv[1], sys.argv[2]
results = {}

start = level(ITEM, MAIN)
print("start", start)
if not start or not start["active"] or any(start.get(k, 0) != 0 for k in ("available", "on_hand", "committed", "reserved")):
    raise SystemExit("Refusing: ITEM must be ACTIVE at MAIN with every quantity 0")


def must_be_inactive(step):
    lv = level(ITEM, MAIN)
    if lv is None or lv["active"]:
        raise SystemExit(f"{step}: could not deactivate the level ({lv}) — stopping")
    return lv


deactivate("q1_deactivate_1", start["id"])
must_be_inactive("q1_deactivate_1")

# 1a. a FRESH key on an inactive level
k1 = str(uuid.uuid4())
lvl, errs = activate("q1a_activate_fresh_key", ITEM, MAIN, k1)
a = level(ITEM, MAIN)
results["1a fresh key re-activates an inactive level"] = f"{'YES' if a and a['active'] else 'NO'} errors={errs} level={a}"

# 1b. the SAME key again after the level went inactive again (Traced re-uses the catalog activation key)
deactivate("q1b_deactivate_2", a["id"])
must_be_inactive("q1b_deactivate_2")
lvl, errs = activate("q1b_activate_reused_key", ITEM, MAIN, k1)
b = level(ITEM, MAIN)
results["1b re-used key re-activates"] = (f"{'YES' if b and b['active'] else 'NO — replayed as a no-op, level still inactive'}"
                                         f" errors={errs} level={b}")

# 1c. an adjust on the inactive level (Traced's app): does it land silently? (S0 saw it with the CLI's app)
if b and b["active"]:
    deactivate("q1c_deactivate_3", b["id"])
inactive = must_be_inactive("q1c_deactivate_3")
grp, codes = adjust("q1c_adjust_inactive_plus1", ITEM, MAIN, +1, None, str(uuid.uuid4()))
c = level(ITEM, MAIN)
results["1c +1 on an inactive level"] = f"applied={grp is not None} errors={codes} {inactive} -> {c}"

# 1d. re-activate with a fresh key: does the hidden +1 show?
lvl, errs = activate("q1d_activate_after_hidden_adjust", ITEM, MAIN, str(uuid.uuid4()))
d = level(ITEM, MAIN)
results["1d after re-activation"] = f"active={d and d['active']} level={d} errors={errs}"

# Put it back: active at MAIN, available 0.
if d is None or not d["active"]:
    activate("cleanup_activate", ITEM, MAIN, str(uuid.uuid4()))
    d = level(ITEM, MAIN)
if d and d.get("available", 0) != 0:
    adjust("cleanup_zero", ITEM, MAIN, -d["available"], d["available"], str(uuid.uuid4()))
end = level(ITEM, MAIN)
print("end", end, "(start", start, ")")
if not end or not end["active"] or end.get("available") != 0 or end.get("on_hand") != 0:
    print("WARNING: the level did not return to its start state — fix it by hand in the dev store")

print("\n==== P2 question 1")
for k, v in results.items():
    print(f"{k}: {v}")
