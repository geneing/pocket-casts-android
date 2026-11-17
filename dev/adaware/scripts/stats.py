import json
from pathlib import Path

d = json.loads(Path("data/downloads.json").read_text())
tot = sum(t["duration"] for e in d.values() for t in e["takes"]) / 3600
print(f"{len(d)} episodes, {sum(len(e['takes']) for e in d.values())} takes, {tot:.1f} h audio")
labels = json.loads(Path("data/labels/ads.json").read_text())
slots = [s for e in labels.values() for v in e["dynamic_slots"].values() for s in v]
print(f"{len(labels)} labelled episodes, {len(slots)} slots, {sum(s['end']-s['start'] for s in slots)/60:.1f} min ads")
print("all slots >8s:", all(s["end"] - s["start"] >= 8 for s in slots))
