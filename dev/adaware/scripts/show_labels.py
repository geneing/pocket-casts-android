import json
from pathlib import Path

d = json.loads(Path("data/labels/ads.json").read_text())
for k, ep in d.items():
    slots = [s for v in ep["dynamic_slots"].values() for s in v]
    short = [s for s in slots if s["end"] - s["start"] < 8]
    print(f"{k[:50]:52s} takes {len(ep['takes'])} slots {len(slots):3d} short {len(short)} baked {len(ep['baked_candidates'])}")
    for s in slots:
        print(f"   {s['start']:7.1f} {s['end']:7.1f} {s['end']-s['start']:6.1f}s  {s['text'][:70]}")
