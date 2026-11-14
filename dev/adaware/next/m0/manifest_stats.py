import json
from collections import Counter
from pathlib import Path

m = json.loads(Path("corpus/manifest.json").read_text())["episodes"]
counts = Counter(v.get("status") for v in m.values())
print(counts)
print("ad minutes:", round(sum(v.get("ad_minutes", 0) for v in m.values() if v.get("status") == "ok"), 1))
