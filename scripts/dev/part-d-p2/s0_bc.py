"""Part D P2, question 3 — Move S0's b and c again, with TRACED'S OWN app (S0 used the Shopify CLI's app; key handling
may be per app), on one item at MAIN only. Dev store only (traced_dev refuses anything else).

    python3 -I scripts/dev/part-d-p2/s0_bc.py MAIN_LOCATION_GID ITEM_GID

b   +1 WITH compare-and-set, then the identical request again — the Part D root cause: S0 saw
    CHANGE_FROM_QUANTITY_STALE (the check runs before the key lookup), not a replay.
b2  +1 WITHOUT compare-and-set, then the identical request again — S0: a true replay (same group).
b3  the b2 key again with a different delta — S0: IDEMPOTENCY_KEY_PARAMETER_MISMATCH.
c   a stale attempt, then the SAME key with a corrected baseline — S0: applied (a userError stores no key).
ITEM must be ACTIVE at MAIN (any quantity). The quantity is put back at the end with compare-and-set.
"""
import os
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from traced_dev import level, adjust

MAIN, ITEM = sys.argv[1], sys.argv[2]
results = {}
start = level(ITEM, MAIN)
print("start", start)
if not start or not start["active"]:
    raise SystemExit("Refusing: ITEM must be ACTIVE at MAIN")

# b — with compare-and-set
v0 = level(ITEM, MAIN)["available"]
kb = str(uuid.uuid4())
g1, e1 = adjust("b_first_cas", ITEM, MAIN, +1, v0, kb, ref="traced://part-d-p2/b")
v1 = level(ITEM, MAIN)["available"]
g2, e2 = adjust("b_identical_resend_cas", ITEM, MAIN, +1, v0, kb, ref="traced://part-d-p2/b")
v2 = level(ITEM, MAIN)["available"]
results["b (CAS) identical re-send"] = (f"first={'applied' if g1 else e1} resend={'replayed same group' if g2 and g1 and g2['id'] == g1['id'] else ('applied AGAIN' if g2 else e2)}"
                                        f" available {v0}->{v1}->{v2}")

# b2 — without compare-and-set
kb2 = str(uuid.uuid4())
g3, e3 = adjust("b2_first_null", ITEM, MAIN, +1, None, kb2, ref="traced://part-d-p2/b2")
v3 = level(ITEM, MAIN)["available"]
g4, e4 = adjust("b2_identical_resend_null", ITEM, MAIN, +1, None, kb2, ref="traced://part-d-p2/b2")
v4 = level(ITEM, MAIN)["available"]
results["b2 (no CAS) identical re-send"] = (f"first={'applied' if g3 else e3} resend={'replayed same group' if g4 and g3 and g4['id'] == g3['id'] else ('applied AGAIN' if g4 else e4)}"
                                            f" available {v2}->{v3}->{v4}")

# b3 — same key, different parameters
g5, e5 = adjust("b3_same_key_other_delta", ITEM, MAIN, +2, None, kb2, ref="traced://part-d-p2/b2")
v5 = level(ITEM, MAIN)["available"]
results["b3 same key, other delta"] = f"{'applied' if g5 else e5} available {v4}->{v5}"

# c — a stale attempt, then the same key corrected, then the same corrected input again
kc = str(uuid.uuid4())
g6, e6 = adjust("c_stale", ITEM, MAIN, +1, v5 + 7, kc, ref="traced://part-d-p2/c")
g7, e7 = adjust("c_same_key_corrected", ITEM, MAIN, +1, v5, kc, ref="traced://part-d-p2/c")
v7 = level(ITEM, MAIN)["available"]
g8, e8 = adjust("c_same_key_corrected_again", ITEM, MAIN, +1, v5, kc, ref="traced://part-d-p2/c")
v8 = level(ITEM, MAIN)["available"]
results["c stale then same key corrected"] = (f"stale={e6 or 'applied?!'} corrected={'applied' if g7 else e7} "
                                              f"again={'replayed' if g8 and g7 and g8['id'] == g7['id'] else ('applied AGAIN' if g8 else e8)}"
                                              f" available {v5}->{v7}->{v8}")

end = level(ITEM, MAIN)
if end["available"] != start["available"]:
    adjust("cleanup_restore_start", ITEM, MAIN, start["available"] - end["available"], end["available"], str(uuid.uuid4()))
print("end", level(ITEM, MAIN), "(start", start, ")")

print("\n==== P2 question 3")
for k, v in results.items():
    print(f"{k}: {v}")
