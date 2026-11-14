import base64
import json
import re

import httpx

feeds = {f["title"]: f for f in json.loads(open("data/feeds.json").read())}
url = feeds["Dan Snow's History Hit"]["episodes"][0]["audio"]
base, q = url.split("?", 1)
tk = re.search(r"tk=([^&]+)", q).group(1)
sig = re.search(r"sig=([^&]+)", q).group(1)
raw = base64.b64decode(tk).decode()
print("tk:", raw)

for label, tk_raw in [
    ("ads_true", raw),
    ("ads_false", raw.replace('"ads":true', '"ads":false')),
]:
    new_tk = base64.b64encode(tk_raw.encode()).decode()
    for sig_mode in ["original_sig", "no_sig"]:
        u = f"{base}?tk={new_tk}" + (f"&sig={sig}" if sig_mode == "original_sig" else "")
        with httpx.Client(timeout=90, follow_redirects=True) as c:
            try:
                r = c.get(u, headers={"User-Agent": "PocketCasts/8.0 (Android)"})
                print(label, sig_mode, r.status_code, len(r.content), str(r.url)[:110])
            except Exception as exc:
                print(label, sig_mode, "ERR", repr(exc)[:120])
