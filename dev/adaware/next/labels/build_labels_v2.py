#!/usr/bin/env python3
"""Rebuild an auditable v2 commercial-pod label set from frozen AdAware data.

The builder reads dev/adaware/data and writes only into this directory. It
combines old transcript-difference candidates, audited exclusions, local
sibling-audio feature matching, explicit promo wording, and shared-text search.
"""
from __future__ import annotations

import collections
import hashlib
import json
import re
import statistics
from pathlib import Path
from typing import Any

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DATA = ROOT / "data"
OLD_PATH = DATA / "labels" / "ads.json"
SPANS_PATH = DATA / "labels" / "ad_spans.json"
ALIGN_PATH = ROOT / "experiments/audio_labels/audio_labels_results.json"
BYTE_PATH = ROOT / "next/audio_review/byte_gaps_v2.json"
OUT_PATH = HERE / "ads_v2.json"
PROV_PATH = HERE / "provenance.json"
STATS_PATH = HERE / "validation_stats.json"
REPORT_PATH = HERE / "audit_report.md"

DT = 0.5
WINDOW = 8.0
RADIUS = 90.0
DIRECT = re.compile(r"\b(?:sponsored by|supported by|this message comes from|message from|paid message from|paid partnership with|presented by|brought to you by|this episode is sponsored by|this podcast is supported by|advertisement|thanks to our sponsor)\b", re.I)
PROMO = re.compile(r"\b(?:terms apply|learn more at|use code|free trial|shop now|sign up today|download the app|listen to .* podcast)\b", re.I)
BRANDS = ("anthropic", "apple card", "autotrader", "capital one", "chase", "claude", "comcast business", "edward jones", "google workspace", "harvard", "hartford", "ibm", "linkedin", "lowe's", "lowes", "okta", "peloton", "pfizer", "principal", "progressive", "ramp", "ripple", "schwab", "starbucks", "underdog", "vantagescore", "verizon", "zocdoc")
TOKEN = re.compile(r"[a-z0-9$%]+", re.I)

EXCLUSIONS = [
    ("freakonomics-radio", "representative appointments are sought from three motives", "Madison quotation about elections; ordinary episode content"),
    ("freakonomics-radio", "and", "Sparse ASR fragment in ordinary Madison quotation"),
    ("planet-money", "i see everywhere is a yemeni guy", "Interview about Yemeni bodegas; ordinary episode content"),
    ("planet-money", "altogether and", "Sparse ASR fragment in ordinary bodega interview"),
]

# Targeted corrections transcribed in experiments/astra/label_audit.md. The
# take-0 boundaries are explicitly reported there; sibling-take extents use
# the same audited commercial sequence and are marked lower-confidence.
AUDITED_PODS = {
    "hard-fork__jensen-huang-is-out-at-nvidia": {
        0: [(0.0,31.34,"audited take-0 pre-roll"),(153.32,247.55,"audited complete Ramp/Ripple/IBM pod"),
            (1236.95,1331.47,"audited complete commercial pod"),(2502.88,2595.09,"audited complete commercial pod"),
            (2677.34,2708.64,"audited closing Ramp ad absent from v1")],
        1: [(152.0,250.31,"sibling-take extent inferred from audited take-0 pod and take-1 transcript"),
            (1225.90,1331.51,"take-1 transcript-audit end correction"),(2490.50,2597.30,"take-1 transcript-audit end correction"),
            (2677.40,2708.64,"sibling transcript match to take-0 closing Ramp spot; onset uncertain")],
        2: [(151.49,248.80,"sibling-take extent inferred from audited take-0 pod and take-2 transcript"),
            (1225.90,1332.00,"take-2 transcript span corroborates full pod"),(2501.60,2598.10,"sibling audio-alignment interval corroborates full pod"),
            (2675.97,2707.35,"sibling transcript match to take-0 closing Ramp spot; onset uncertain")],
    },
    "dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with": {
        0: [(1527.16,1654.73,"audited full TikTok/Verizon/Progressive/Lowe's midroll pod"),
            (3056.13,3165.41,"audited closing Lowe's ad plus own-show History Hit subscription promo")],
        1: [(2340.59,2439.31,"audited Verizon/Grow Therapy later pod; editorial resumes at 2446.69"),
            (3156.68,3265.91,"audited closing Lowe's ad plus own-show History Hit subscription promo")],
        2: [(1556.56,1725.04,"audited full TikTok/Progressive/Grow Therapy/Lowe's pod; shared TikTok prefix precedes byte-distinct tail"),
            (3176.21,3256.39,"audited Apple Card ad plus own-show History Hit subscription promo; preceding outro gap excluded")],
    },
    "the-daily__what-jensen-huang-s-resignation-will-mea": {
        0: [(0.0,29.56,"audited Capital One Venture X pre-roll through terms and details"),
            (714.91,812.01,"audited Capital One/UNHCR/eTrade pod; sibling byte alignment supports extent")],
        1: [(0.0,24.50,"audited Capital One Saver pre-roll through terms and details"),
            (712.04,793.51,"audited continuous Capital One/Starbucks commercial pod, including copy between transcript fragments")],
        2: [(0.0,24.50,"audited Capital One Saver pre-roll through terms and details")],
    },
    "planet-money__how-bodegas-led-to-the-yemeni-coffeehous": {
        0: [(0.11,15.81,"audited HubSpot pre-roll sponsor message"),(1620.60,1656.36,"audited sponsor lead-in, shared Hartford ad, and Capella post-roll")],
        1: [(0.0,19.72,"audited Edward Jones pre-roll through member SIPC; editorial begins at Planet Money introduction"),
            (274.67,337.00,"audited Schwab/eTrade/Odoo complete midroll pod"),(1099.13,1161.99,"audited Schwab/eTrade/Odoo complete midroll pod"),
            (1598.78,1649.49,"audited sponsor lead-in, Hartford ad, and Capella post-roll")],
        2: [(0.0,14.84,"audited US Bank pre-roll through member FDIC"),
            (1598.54,1644.94,"audited sponsor lead-in, Google Workspace and Boll and Branch post-rolls")],
    },
    "revisionist-history__from-the-big-dig-mitt-ted": {
        0: [(0.0,91.80,"audited shared ChatGPT/Progressive prefix plus Health versus Hype commercial pod")],
        1: [(0.0,91.80,"audited shared ChatGPT/Progressive prefix plus Health versus Hype commercial pod")],
        2: [(0.0,140.66,"audited full ChatGPT/Progressive/Apple Card pre-roll pod; byte span misses shared prefix")],
    },
    "odd-lots__what-nvidia-s-megaspeed-scandal-is-reall": {
        0: [(1019.26,1117.00,"audited Underdog creative plus adjoining terms/creative copy"),
            (2422.00,2603.25,"uncertain lead-in; continuous Underdog, ChatGPT, and following commercial copy includes audited 2440.17-2506.27 creative")],
        1: [(956.78,1145.32,"audited Bloomberg/Dubai promo, Underdog, ChatGPT, and Indeed midroll pod"),
            (2415.18,2611.55,"transcript audit: continuous Underdog-to-ChatGPT commercial pod")],
        2: [(1006.97,1143.00,"transcript audit: continuous Hills, Underdog, and ChatGPT commercial pod"),
            (2411.00,2648.60,"inferred continuous Underdog/Comcast commercial pod from transcript copy"),
            (3703.46,3850.00,"uncertain closing ad onset: sparse transcript begins at 3703.46 while local audio proposal begins at 3734.5")],
    },
    "freakonomics-radio__694-does-democracy-really-need-elections": {
        0: [(3017.70,3120.00,"audited complete US Bank/Sylvania/Hotels.com midroll"),
            (4224.50,4353.84,"audited complete Osempic/Adyen/Mazda/Schwab closing pod")],
        1: [(3000.60,3119.70,"sibling transcript extent: Peloton/Sylvania/Hotels.com commercial pod"),
            (4224.50,4354.90,"sibling transcript extent: Osempic/Mazda/Adyen/Schwab closing pod")],
        2: [(3017.70,3120.00,"take-0 transcript audit corroborated by take-2 transcript"),
            (4224.50,4353.84,"take-0 transcript audit corroborated by take-2 transcript")],
    },
}


def toks(text: str) -> list[str]:
    return TOKEN.findall(text.lower().replace("’", "'"))


def evidence(text: str) -> dict[str, Any]:
    t = text.lower()
    brands = [b for b in BRANDS if b in t]
    return {"direct_cue": bool(DIRECT.search(t)), "promo_cue": bool(PROMO.search(t)), "brands": brands}


def exclusion(ep: str, text: str) -> str | None:
    t = " ".join(toks(text))
    for name, phrase, reason in EXCLUSIONS:
        matched = t == phrase if len(phrase.split()) <= 2 else phrase in t
        if name in ep and matched:
            return reason
    return None


def words_for(transcript: dict[str, Any]) -> list[dict[str, Any]]:
    out = []
    for seg in transcript.get("segments", []):
        for w in seg.get("words", []):
            normalized = toks(w.get("w", ""))
            if normalized:
                out.append({"w": normalized[0], "s": float(w["s"]), "e": float(w["e"])})
    return out


def shared_match(query: list[str], target: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Find a mostly exact creative-like span using exact 4-word shingles."""
    if len(query) < 24 or len(target) < 24:
        return None
    grams: dict[tuple[str, ...], list[int]] = collections.defaultdict(list)
    for i in range(len(query) - 3):
        grams[tuple(query[i:i + 4])].append(i)
    tgt = [x["w"] for x in target]
    offsets = collections.Counter()
    for j in range(len(tgt) - 3):
        for i in grams.get(tuple(tgt[j:j + 4]), ()):
            offsets[j - i] += 1
    best = None
    for off, _ in offsets.most_common(8):
        matched_q, matched_t = set(), set()
        for qi in range(max(0, -off), min(len(query) - 3, len(tgt) - off - 3)):
            tj = qi + off
            if query[qi:qi + 4] == tgt[tj:tj + 4]:
                matched_q.update(range(qi, qi + 4))
                matched_t.update(range(tj, tj + 4))
        coverage = len(matched_q) / len(query)
        if coverage < 0.62 or len(matched_t) < 18:
            continue
        ids = sorted(matched_t)
        start, end = target[ids[0]]["s"], target[ids[-1]]["e"]
        candidate = {"start": round(start, 2), "end": round(end, 2), "coverage": round(coverage, 3),
                     "text": " ".join(tgt[i] for i in ids)}
        if best is None or candidate["coverage"] > best["coverage"]:
            best = candidate
    return best


def load_features(ep: str, take: int) -> np.ndarray | None:
    path = DATA / "features" / ep / f"take{take}.npz"
    if not path.exists():
        return None
    with np.load(path) as z:
        names = [str(x) for x in z["names"].tolist()]
        columns = [names.index(f"mfcc_{i}_mean") for i in range(1, 13)]
        x = z["feats"][:, columns].astype(np.float32)
    n = len(x) // 2
    return x[:n * 2].reshape(n, 2, -1).mean(axis=1)


def local_gaps(a: np.ndarray, b: np.ndarray, start: float, end: float) -> list[dict[str, float]]:
    """Find source-only regions using ordered MFCC matches in a local window."""
    a0 = max(0, int(max(0, start - RADIUS) / DT))
    a1 = min(len(a), int(np.ceil((end + RADIUS) / DT)))
    b0 = max(0, int(max(0, start - RADIUS) / DT))
    b1 = min(len(b), int(np.ceil((end + RADIUS) / DT)))
    aa, bb = a[a0:a1], b[b0:b1]
    width = max(3, round(WINDOW / DT))
    if min(len(aa), len(bb)) < width * 2:
        return []
    pool = np.concatenate((aa, bb))
    med = np.median(pool, axis=0)
    q25, q75 = np.percentile(pool, (25, 75), axis=0)
    scale = np.maximum((q75 - q25) / 1.349, 1e-3)
    def norm(x):
        x = np.clip((x - med) / scale, -5, 5)
        return x / np.maximum(np.linalg.norm(x, axis=1, keepdims=True), 1e-6)
    aa, bb = norm(aa), norm(bb)
    sim = aa @ bb.T
    rows, cols = sim.shape[0] - width + 1, sim.shape[1] - width + 1
    score = np.zeros((rows, cols), dtype=np.float32)
    for k in range(width):
        score += sim[k:k + rows, k:k + cols]
    score /= width
    stride = max(1, round(1 / DT))
    starts = np.arange(0, rows, stride)
    best_j = score[starts].argmax(axis=1)
    best_v = score[starts, best_j]
    ok = []
    for row, col, value in zip(starts, best_j, best_v):
        radius = round(20 / DT)
        runner = np.r_[score[row, :max(0, col-radius)], score[row, min(cols, col+radius+1):]]
        second = float(runner.max()) if runner.size else -1
        ok.append(value >= 0.72 and (value - second >= 0.035 or second < 0))
    ai, ao, last = [], [], -1
    for i, j, valid in zip(starts, best_j, ok):
        if valid and j > last:
            ai.append(int(i)); ao.append(float(j-i)); last = int(j)
    if len(ai) < 2:
        return []
    time = np.arange(len(aa))
    offset = np.interp(time, ai, ao)
    sm = max(3, round(10 / DT) | 1); pad = sm // 2
    offset = np.median(np.lib.stride_tricks.sliding_window_view(np.pad(offset, (pad,pad), mode="edge"), sm), axis=1)
    target = np.rint(time + offset).astype(int)
    inside = (target >= 0) & (target < len(bb))
    frame = np.full(len(time), -1., np.float32)
    frame[inside] = np.sum(aa[inside] * bb[target[inside]], axis=1)
    sm = max(3, round(4 / DT) | 1); pad = sm // 2
    frame = np.mean(np.lib.stride_tricks.sliding_window_view(np.pad(frame, (pad,pad), mode="edge"), sm), axis=1)
    ids = np.flatnonzero(frame < 0.48)
    if not len(ids):
        return []
    breaks = np.flatnonzero(np.diff(ids) > round(2 / DT))
    groups = zip(np.r_[ids[0], ids[breaks+1]], np.r_[ids[breaks], ids[-1]+1])
    base = a0 * DT
    out = []
    for s, e in groups:
        gs, ge = base+s*DT, base+e*DT
        if ge-gs >= 8 and ge >= start-45 and gs <= end+45:
            out.append({"start": round(gs,2), "end": round(ge,2)})
    return out


def overlap(a: dict[str, Any], b: dict[str, Any]) -> float:
    lo, hi = max(a["start"], b["start"]), min(a["end"], b["end"])
    union = max(a["end"], b["end"]) - min(a["start"], b["start"])
    return max(0., hi-lo) / union if union > 0 else 0.


def refine(seed: dict[str, Any], features: dict[int, np.ndarray], takes: list[int]) -> dict[str, Any]:
    proposals = []
    dur = seed["end"] - seed["start"]
    for other in takes:
        if other == seed["take"] or other not in features:
            continue
        for gap in local_gaps(features[seed["take"]], features[other], seed["start"], seed["end"]):
            gd = gap["end"] - gap["start"]
            center_delta = abs((gap["start"] + gap["end"] - seed["start"] - seed["end"]) / 2)
            if gd > max(dur*4.5, dur+120):
                continue
            if overlap(gap, seed) >= 0.18 or center_delta <= max(45, dur*1.2):
                proposals.append({**gap, "target_take": other, "iou": overlap(gap, seed), "center_delta": center_delta})
    if not proposals:
        return {"start": seed["start"], "end": seed["end"], "basis": "transcript_word_times", "uncertainty_seconds": 12., "support": []}
    proposals.sort(key=lambda p: (-p["iou"], p["center_delta"]))
    clusters = []
    for p in proposals:
        c = next((c for c in clusters if abs(p["start"]-statistics.median(x["start"] for x in c)) <= 12 and abs(p["end"]-statistics.median(x["end"] for x in c)) <= 12), None)
        if c is None:
            clusters.append([p])
        else:
            c.append(p)
    if not clusters:
        clusters = [[p] for p in proposals]
    chosen = max(clusters, key=lambda c: (len(c), sum(p["iou"] for p in c), -statistics.mean(p["center_delta"] for p in c)))
    if len(chosen) < 2 and len(takes) > 2:
        return {"start": seed["start"], "end": seed["end"], "basis": "transcript_word_times", "uncertainty_seconds": 12., "support": chosen}
    ss = [p["start"] for p in chosen]; ee = [p["end"] for p in chosen]
    s, e = statistics.median(ss), statistics.median(ee)
    if abs(s-seed["start"]) > 90 or abs(e-seed["end"]) > 90:
        return {"start": seed["start"], "end": seed["end"], "basis": "transcript_word_times", "uncertainty_seconds": 12., "support": chosen}
    unc = max(2., max(abs(x-statistics.median(ss)) for x in ss), max(abs(x-statistics.median(ee)) for x in ee))
    return {"start": round(s,2), "end": round(e,2), "basis": "localized_audio_match_consensus", "uncertainty_seconds": round(unc,2), "support": chosen}


def explicit_candidates(transcript: dict[str, Any], existing: list[dict[str, Any]]) -> list[dict[str, Any]]:
    segs = transcript.get("segments", []); found = []
    for i, seg in enumerate(segs):
        if not DIRECT.search(seg.get("text", "")):
            continue
        start, end, texts = float(seg["start"]), float(seg["end"]), [seg.get("text", "")]
        for nxt in segs[i+1:i+10]:
            if float(nxt["start"])-end > 2.8:
                break
            ev = evidence(nxt.get("text", ""))
            if not (PROMO.search(nxt.get("text", "")) or ev["brands"] or len(toks(nxt.get("text", ""))) > 7 and re.search(r"\b(?:terms|visit|save|plan|today|app|free|buy)\b", nxt.get("text", ""), re.I)):
                break
            end = float(nxt["end"]); texts.append(nxt.get("text", ""))
        if end-start < 8: continue
        item = {"start": round(start,2), "end": round(end,2), "text": " ".join(texts), "evidence": evidence(" ".join(texts))}
        if not any(overlap(item,x) >= .25 or abs(item["start"]-x["start"]) <= 8 for x in existing):
            found.append(item)
    return found


def stable_id(ep: str, t: int, s: float, text: str) -> str:
    return hashlib.sha1(f"{ep}|{t}|{s:.1f}|{' '.join(toks(text)[:18])}".encode()).hexdigest()[:12]


def merge_pods(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out = []
    for x in sorted(items, key=lambda z: (z["start"], z["end"])):
        if out and x["start"] <= out[-1]["end"]+3:
            p=out[-1]; p["start"]=min(p["start"],x["start"]); p["end"]=max(p["end"],x["end"]); p["sub_creatives"].append({"start":x["start"],"end":x["end"],"text":x["text"],"label_id":x["label_id"],"classification":x["classification"],"provenance":x["provenance"],"boundary_basis":x["boundary_basis"],"confidence":x["confidence"],"uncertainty_seconds":x["uncertainty_seconds"],"byte_alignment_status":x.get("byte_alignment_status"),"byte_alignment_support":x.get("byte_alignment_support",[]),"notes":x.get("notes",[])})
            p["uncertainty_seconds"]=max(p["uncertainty_seconds"],x["uncertainty_seconds"])
            p["confidence"]="low" if "low" in (p["confidence"],x["confidence"]) else "medium" if "medium" in (p["confidence"],x["confidence"]) else "high"
            if x["classification"] == "audited_whole_pod":
                for key in ("classification", "provenance", "boundary_basis", "notes"):
                    p[key] = x[key]
            if x["classification"] == "audited_whole_pod":
                for key in ("classification", "provenance", "boundary_basis", "notes"):
                    p[key] = x[key]
        else:
            out.append({**x,"sub_creatives":[{"start":x["start"],"end":x["end"],"text":x["text"],"label_id":x["label_id"],"classification":x["classification"],"provenance":x["provenance"],"boundary_basis":x["boundary_basis"],"confidence":x["confidence"],"uncertainty_seconds":x["uncertainty_seconds"],"byte_alignment_status":x.get("byte_alignment_status"),"byte_alignment_support":x.get("byte_alignment_support",[]),"notes":x.get("notes",[])}]})
    for x in out:
        x["text"]=" ".join(y["text"] for y in x["sub_creatives"])
        by_start=min(x["sub_creatives"],key=lambda y:y["start"])
        by_end=max(x["sub_creatives"],key=lambda y:y["end"])
        x["boundary_sources"]={"start":{"basis":by_start["boundary_basis"],"provenance":by_start["provenance"],"uncertainty_seconds":by_start["uncertainty_seconds"]},
                               "end":{"basis":by_end["boundary_basis"],"provenance":by_end["provenance"],"uncertainty_seconds":by_end["uncertainty_seconds"]}}
        x["byte_alignment_support"]=by_start.get("byte_alignment_support",[])+by_end.get("byte_alignment_support",[])
        endpoint_status={by_start.get("byte_alignment_status"),by_end.get("byte_alignment_status")}
        x["byte_alignment_status"]="full_pod_corroborated" if endpoint_status=={"full_span_corroborated"} else "partial_or_discordant" if endpoint_status-{"no_byte_support"} else "no_byte_support"
    return out


def attach_byte_support(item: dict[str, Any], records: list[dict[str, Any]]) -> None:
    """Attach exact-byte changed spans without treating them as full-pod labels.

    A byte gap can omit a shared creative within the same commercial pod, so it
    corroborates an edge only when both anchors and both boundaries agree.
    """
    candidates=[]
    for record in records:
        seed={"start":float(record["seed_start"]),"end":float(record["seed_end"])}
        if overlap(item,seed) >= 0.08 or (abs(item["start"]-seed["start"])<=20 and abs(item["end"]-seed["end"])<=90):
            for support in record.get("support",[]):
                candidates.append({"seed_start":seed["start"],"seed_end":seed["end"],"other_take":support.get("other_take"),
                                   "start":float(support["start"]),"end":float(support["end"]),
                                   "left_anchor":support.get("left_anchor"),"right_anchor":support.get("right_anchor"),
                                   "start_delta_from_words":support.get("start_delta_from_words"),
                                   "end_delta_from_words":support.get("end_delta_from_words")})
    item["byte_alignment_support"]=candidates
    if not candidates:
        item["byte_alignment_status"]="no_byte_support"
        return
    full=[x for x in candidates if x["left_anchor"] is not None and x["right_anchor"] is not None
          and abs(x["start"]-item["start"])<=10 and abs(x["end"]-item["end"])<=10]
    if full:
        item["byte_alignment_status"]="full_span_corroborated"
        delta=max(abs(item["start"]-statistics.median(x["start"] for x in full)),
                  abs(item["end"]-statistics.median(x["end"] for x in full)))
        item["uncertainty_seconds"]=min(item["uncertainty_seconds"],max(2.0,round(delta,2)))
    elif any(x["start"] >= item["start"]+10 or x["end"] <= item["end"]-10 for x in candidates):
        item["byte_alignment_status"]="partial_changed_span_shared_pod_possible"
    else:
        item["byte_alignment_status"]="boundary_disagreement"


def main() -> None:
    old=json.loads(OLD_PATH.read_text(encoding="utf-8")); spans=json.loads(SPANS_PATH.read_text(encoding="utf-8"))
    byte_data=json.loads(BYTE_PATH.read_text(encoding="utf-8")) if BYTE_PATH.exists() else {}
    span_by={(x["episode"],int(x["take"]),round(float(x["start"]),2)):x for x in spans}
    out={}; label_prov=[]; excluded=[]; holdout=[]
    for ep, data in old.items():
        audio_paths=sorted((DATA/"audio"/ep).glob("take*.mp3"), key=lambda p:int(p.stem.removeprefix("take")))
        takes=[int(p.stem.removeprefix("take")) for p in audio_paths]
        take_paths=[str(p.relative_to(DATA)).replace("\\","/") for p in audio_paths]
        trans={t:json.loads((DATA/"transcripts"/ep/f"take{t}.json").read_text(encoding="utf-8")) for t in takes}
        words_by={t:words_for(trans[t]) for t in takes}
        feats={t:x for t in takes if (x:=load_features(ep,t)) is not None}
        candidates={t:[] for t in takes}
        for t in takes:
            for raw in data["dynamic_slots"].get(str(t),[]):
                reason=exclusion(ep,raw.get("text",""))
                if reason:
                    excluded.append({"episode":ep,"take":t,"start":raw["start"],"end":raw["end"],"text":raw.get("text",""),"reason":reason}); continue
                ev=evidence(raw.get("text",""))
                if len(toks(raw.get("text","")))<5 and not (ev["direct_cue"] or ev["brands"]):
                    excluded.append({"episode":ep,"take":t,"start":raw["start"],"end":raw["end"],"text":raw.get("text",""),"reason":"Sparse ASR text below evidence threshold"}); continue
                seed={"take":t,"start":float(raw["start"]),"end":float(raw["end"]),"text":raw.get("text",""),"text_evidence":ev}
                refined=refine(seed,feats,takes)
                confidence="high" if (ev["direct_cue"] or ev["brands"]) and refined["basis"]=="localized_audio_match_consensus" else "medium" if (ev["direct_cue"] or ev["brands"]) else "low"
                item={**seed,"start":refined["start"],"end":refined["end"],"label_id":stable_id(ep,t,seed["start"],seed["text"]),
                      "provenance":"transcript_semantics","boundary_basis":refined["basis"],"uncertainty_seconds":refined["uncertainty_seconds"],
                      "confidence":confidence,"classification":"dynamic_candidate","audio_support":refined["support"],"notes":[]}
                candidates[t].append(item); label_prov.append({"episode":ep,**item})
        for t in takes:
            for c in explicit_candidates(trans[t],candidates[t]):
                reason=exclusion(ep,c["text"])
                if reason: continue
                item={**c,"text_evidence":c["evidence"],"take":t,"label_id":stable_id(ep,t,c["start"],c["text"]),"provenance":"transcript_semantics","boundary_basis":"explicit_transcript_cue",
                      "uncertainty_seconds":5.,"confidence":"medium","classification":"semantic_only_candidate","audio_support":[],"notes":["Found by explicit sponsor wording"]}
                candidates[t].append(item); label_prov.append({"episode":ep,**item})

        # Replace known partial spans with targeted whole-pod corrections from
        # the transcript audit. Sibling-only estimates remain low confidence.
        for t, pod_rows in AUDITED_PODS.get(ep, {}).items():
            if t not in candidates:
                continue
            for start, end, note in pod_rows:
                target={"start":start,"end":end}
                keep=[]; removed=[]
                for item in candidates[t]:
                    close=(overlap(item,target)>=0.12 or (abs(item["start"]-start)<=35 and abs(item["end"]-end)<=45))
                    (removed if close else keep).append(item)
                candidates[t]=keep
                if removed:
                    removed_ids={x["label_id"] for x in removed}
                    label_prov[:]=[x for x in label_prov if x.get("label_id") not in removed_ids]
                tw=words_by[t]
                text=" ".join(x["w"] for x in tw if x["e"]>=start and x["s"]<=end)
                inferred=("sibling-take extent" in note or "inferred from audited" in note)
                exact_audit=("audited" in note and not inferred)
                uncertainty=35.0 if "closing ad onset" in note else 20.0 if "uncertain" in note else 10.0 if exact_audit and end-start<15.5 else 15.0 if exact_audit else 20.0
                item={"take":t,"start":start,"end":end,"text":text,"label_id":stable_id(ep,t,start,text),
                      "provenance":"transcript_audit" if exact_audit else "cross_take_inference",
                      "boundary_basis":"audited_transcript_extent" if exact_audit else "sibling_transcript_inference",
                      "uncertainty_seconds":uncertainty,
                      "confidence":"medium" if exact_audit else "low","classification":"audited_whole_pod",
                      "audio_support":[],"text_evidence":evidence(text),
                      "notes":[note,"Transcript-derived; not listening-verified"]}
                candidates[t].append(item); label_prov.append({"episode":ep,**item})

        baked=[]
        # Any ad-like seed that has a >=62% 4-gram match in every sibling is
        # emitted as a shared candidate. This is transcript-only evidence.
        for source in [x for t in takes for x in candidates[t]]:
            ev=source["text_evidence"]
            query=toks(source["text"])
            if len(query)<24 or not (ev["direct_cue"] or ev["brands"]): continue
            matches={source["take"]:{"start":source["start"],"end":source["end"],"text":source["text"],"coverage":1.0}}
            for t in takes:
                if t==source["take"]: continue
                m=shared_match(query,words_by[t])
                if m and abs((m["end"]-m["start"])-(source["end"]-source["start"]))<=90: matches[t]=m
            if len(matches)!=len(takes): continue
            gid=stable_id(ep,source["take"],source["start"],source["text"]+"shared")
            group={"label_id":gid,"shared_basis":"transcript_4gram_match_in_all_takes","confidence":"medium",
                   "take_intervals":{str(t):matches[t] for t in takes}}
            if any(all(overlap(group["take_intervals"][str(t)],g["take_intervals"][str(t)])>=.5 for t in takes) for g in baked): continue
            baked.append(group)
            for t in takes:
                m=matches[t]
                item={"take":t,"start":round(m["start"],2),"end":round(m["end"],2),"text":m["text"],"label_id":gid,
                      "provenance":"cross_take_transcript_match","boundary_basis":"matched_word_timestamps","uncertainty_seconds":8.,
                      "confidence":"medium","classification":"shared_baked_candidate","audio_support":[],
                      "text_evidence":ev,"notes":["Creative-like text matched across every sibling take; no audio confirmation"]}
                if not any(x["label_id"]==gid for x in candidates[t]):
                    candidates[t].append(item); label_prov.append({"episode":ep,**item})

        slots={}
        for t in takes:
            byte_records=byte_data.get(ep,{}).get(str(t),[])
            for item in candidates[t]:
                attach_byte_support(item,byte_records)
            slots[str(t)]=merge_pods([x for x in candidates[t] if x["end"]>x["start"]])
        out[ep]={"duration":data["duration"],"takes":take_paths,"dynamic_slots":slots,"baked_candidates":baked}
    OUT_PATH.write_text(json.dumps(out,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")

    totals=collections.Counter(); per_ep={}; conf=collections.Counter(); bases=collections.Counter(); uncertainty=collections.Counter(); byte_status=collections.Counter()
    for ep,d in out.items():
        per_ep[ep]={"takes":len(d["takes"]),"pods":sum(len(v) for v in d["dynamic_slots"].values()),"baked_candidate_groups":len(d["baked_candidates"]),
                    "per_take_pods":{t:len(v) for t,v in d["dynamic_slots"].items()}}
        for items in d["dynamic_slots"].values():
            for x in items:
                conf[x["confidence"]]+=1; bases[x["boundary_basis"]]+=1
                uncertainty["over_10s" if x["uncertainty_seconds"]>10 else "up_to_5s" if x["uncertainty_seconds"]<=5 else "5_to_10s"]+=1
                byte_status[x["byte_alignment_status"]]+=1
    # Descriptive reference overlap, not independent label accuracy.
    ref_rows=[]
    for ep,d in out.items():
        for t,pred in d["dynamic_slots"].items():
            gold=old[ep]["dynamic_slots"].get(t,[])
            used=set(); tp=0
            for p in pred:
                ix=next((i for i,g in enumerate(gold) if i not in used and abs(p["start"]-g["start"])<=10 and abs(p["end"]-g["end"])<=10),None)
                if ix is not None: used.add(ix); tp+=1
            fp,fn=len(pred)-tp,len(gold)-tp
            ref_rows.append({"episode":ep,"take":t,"v2_pods":len(pred),"v1_intervals":len(gold),"tp":tp,"fp":fp,"fn":fn})
            totals.update(tp=tp,fp=fp,fn=fn)
    tp,fp,fn=totals["tp"],totals["fp"],totals["fn"]
    stats={"episodes":len(out),"takes":sum(len(d["takes"]) for d in out.values()),"v2_pods":sum(v["pods"] for v in per_ep.values()),
           "excluded_v1_records":len(excluded),"confidence_counts":dict(conf),"boundary_basis_counts":dict(bases),"uncertainty_counts":dict(uncertainty),"byte_alignment_status_counts":dict(byte_status),"episodes_detail":per_ep,
           "v1_reference_overlap":{"tolerance_seconds":10,"tp":tp,"fp":fp,"fn":fn,"precision":tp/(tp+fp) if tp+fp else 0.,
                                    "recall":tp/(tp+fn) if tp+fn else 0.,"takes":ref_rows,
                                    "caveat":"Descriptive overlap with transcript-difference labels, not independent accuracy."}}
    STATS_PATH.write_text(json.dumps(stats,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    for x in label_prov:
        if x["take"]==0 and int(float(x["start"])//300)%4==0:
            holdout.append({"episode":x["episode"],"take":0,"label_id":x["label_id"],"start":x["start"],"end":x["end"],"status":"reserved_for_future_audio_review"})
    provenance={"version":2,"inputs":[str(OLD_PATH.relative_to(ROOT)),str(SPANS_PATH.relative_to(ROOT)),str(ALIGN_PATH.relative_to(ROOT)),str(BYTE_PATH.relative_to(ROOT))],
                "definition":"Maximal contiguous paid commercial/promotional pod; cross-podcast promos count; editorial speech excluded.",
                "method":"Transcript-difference candidates plus explicit sponsor wording. Each candidate is compared to sibling MFCC features only in a +/-90 second local window; ordered, unique anchors produce optional boundary consensus. Exact 4-word transcript shingle matching across every take records possible shared creatives separately. Exact MP3 byte-gap matches are attached as boundary corroboration only; they can omit shared ads within a commercial pod and never override the semantic pod extent by themselves.",
                "audio_listening_possible":False,"manual_audio_verification":False,"holdout_for_future_audio_review":holdout,
                "exclusions":excluded,"labels":label_prov,
                "parameters":{"feature_grid_seconds":DT,"match_window_seconds":WINDOW,"local_radius_seconds":RADIUS,"boundary_tolerance_eval_seconds":10}}
    PROV_PATH.write_text(json.dumps(provenance,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")

    lines=["# AdAware v2 label audit","","## Annotation definition","",
           "One label is one maximal contiguous paid-commercial or promotional pod. Cross-podcast and clear own-show subscription promos count; ordinary editorial speech and show outros are excluded. Three seconds is an automatic merge heuristic only; targeted transcript-supported ads within one break include longer spoken or silent transitions. Source spans remain under `sub_creatives`.","",
           "## Method and verification","",
           "The build starts from the frozen transcript-difference spans, excludes the documented Madison quotation and Yemeni bodega interview false positives plus sparse fragments, and adds explicit sponsor wording when not already covered. MFCC boundary proposals use only a candidate-centered +/-90 second region and require ordered local anchors; consensus across sibling takes is used when available. Exact MP3 byte-gap results are attached as corroboration only; they can omit shared creatives, so they never replace the maximal semantic pod. Shared candidates are found by exact 4-word shingle overlap in all takes and are marked separately.","",
           "Audio was not listened to. No labels are marked manually verified. Audio boundary proposals are algorithmic; shared-creative matches are transcript-only and not independent evidence for a transcript-based detector. A deterministic take-0 sample is reserved for future audio review.","",
           "## Validation","",
           f"- Episodes / takes: {stats['episodes']} / {stats['takes']} (includes the ad-free control).",
           f"- V2 pods: {stats['v2_pods']}; excluded v1 records: {stats['excluded_v1_records']}.",
           f"- Boundary uncertainty: {uncertainty['over_10s']} pods exceed 10 s; {uncertainty['5_to_10s']} are in the 5–10 s band; {uncertainty['up_to_5s']} are estimated at 5 s or less. Wider estimates are flagged and should not be treated as meeting the 10 s target.",
           f"- Exact MP3 byte-gap corroboration: `{json.dumps(dict(byte_status),sort_keys=True)}`. Partial changed spans do not replace a complete semantic pod, because shared creatives can sit outside byte-different regions.",
           f"- Descriptive v1 overlap at +/-10 s: TP {tp}, FP {fp}, FN {fn}, precision {stats['v1_reference_overlap']['precision']:.3f}, recall {stats['v1_reference_overlap']['recall']:.3f}. This is not independent accuracy because v1 has known boundary and semantic defects.","",
           "## Per-episode coverage","","| Episode | Takes | Pods | Shared groups | Per-take pods |","|---|---:|---:|---:|---|"]
    for ep,x in per_ep.items(): lines.append(f"| `{ep}` | {x['takes']} | {x['pods']} | {x['baked_candidate_groups']} | {', '.join('t'+t+'='+str(n) for t,n in x['per_take_pods'].items())} |")
    lines += ["","## Confidence and boundary basis","",f"Confidence counts: `{json.dumps(dict(conf),sort_keys=True)}`.","",f"Boundary basis counts: `{json.dumps(dict(bases),sort_keys=True)}`.","",
              "## Files","","- `ads_v2.json`: compatible episode/take interval layout, with per-interval provenance, confidence, and uncertainty.","- `provenance.json`: sources, exclusions, method details, label-level evidence, and a future audio-review holdout.","- `validation_stats.json`: all-episode coverage and descriptive v1 comparison.","- `build_labels_v2.py`: reproducible builder; original `data/labels` files are not modified.",""]
    REPORT_PATH.write_text("\n".join(lines),encoding="utf-8")
    print(f"episodes={stats['episodes']} takes={stats['takes']} pods={stats['v2_pods']} excluded={len(excluded)}")
    print(f"v1 overlap TP={tp} FP={fp} FN={fn} P={stats['v1_reference_overlap']['precision']:.3f} R={stats['v1_reference_overlap']['recall']:.3f}")
    print(f"Wrote {OUT_PATH}, {PROV_PATH}, {STATS_PATH}, {REPORT_PATH}")

if __name__ == "__main__": main()
