#!/usr/bin/env python3
"""Score the jev classifier against the M0 labelled ad corpus.

Gold comes from ``dynamic_slots`` in ``next/m0/corpus/labels/ads.json``: the ad pods a take
inserted, derived by diffing sibling takes of the same episode. Every whisper cue is
labelled ``advertisement`` when its **midpoint** falls inside a dynamic slot, else
``content``. Cues are classified in non-overlapping windows of ``K`` (default 20) so one
request never carries a whole episode, the per-cue predictions are cached as JSONL, and
every threshold is applied at report time.

Two levels are reported. The cue level (precision/recall/F1 on cues) says how well the
model tags sentences. The interval level is the one the project cares about: merge
consecutive ad-scored cues into pods, and a pod is a hit only when both of its boundaries
are within 5 s of a gold slot's boundaries.

From ``dev/adaware``::

    OPENCODE_API_KEY=$(tr -d '\\r\\n' < ../../secrets/opencode.key) \\
        uv run python next/jev/eval_corpus.py --limit 1 --dry-run
    OPENCODE_API_KEY=$(tr -d '\\r\\n' < ../../secrets/opencode.key) \\
        uv run python next/jev/eval_corpus.py --take 0

The cache lives in ``next/jev/results/<episode>__take<N>.jsonl``. It stores predictions
only, never gold or thresholded labels, so changing a threshold never needs a re-run.
Resume is on by default; a window with a missing answer is not treated as cached, so the
next run asks for it again.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Sequence

from jev_client import DEFAULT_MODEL, MissingApiKey, SystemOneError, classify_batch

HERE = Path(__file__).resolve().parent
CORPUS = HERE.parent / "m0" / "corpus"
DEFAULT_OUT = HERE / "results"
DEFAULT_THRESHOLDS = (0.2, 0.3, 0.4, 0.5, 0.6, 0.7)

#: Cues between two ad cues that may be bridged when merging a pod.
MAX_GAP_CUES = 1
#: Predicted pods shorter than this are dropped before matching.
MIN_POD_SECONDS = 8.0
#: A hit needs both predicted boundaries within this many seconds of the gold ones.
MATCH_TOLERANCE = 5.0

#: Baked-ad candidates are absent from the corpus labels (every entry has ``[]``); these
#: legacy label files still carry them per take and are used as a read-only fallback.
LEGACY_LABEL_FILES = (HERE.parent / "labels" / "ads_v3.json", HERE.parent / "labels" / "ads_v2.json")

#: The three thresholds the report always tabulates, whatever ``--thresholds`` says.
REPORT_THRESHOLDS = (0.3, 0.5, 0.7)


# --- Corpus ----------------------------------------------------------------------------


@dataclass(frozen=True)
class Cue:
    idx: int
    start: float
    end: float
    text: str


@dataclass
class Episode:
    episode: str
    take: int
    show: str
    duration: float
    cues: list[Cue]
    slots: list[tuple[float, float]]
    gold: list[bool]
    gold_overlap: list[float]
    baked: list[tuple[float, float, str]]
    rows: list[dict] = field(default_factory=list)

    @property
    def missing(self) -> int:
        return sum(1 for row in self.rows if row.get("missing"))


def load_labels(corpus: Path) -> dict[str, dict]:
    return json.loads((corpus / "labels" / "ads.json").read_text(encoding="utf-8"))


def load_cues(corpus: Path, episode: str, take: int) -> list[Cue]:
    """Read one take's whisper segments as cues; segments without text are dropped."""
    path = corpus / "transcripts" / episode / f"take{take}.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    cues: list[Cue] = []
    for segment in payload.get("segments", []):
        text = " ".join(str(segment.get("text") or "").split())
        if not text:
            continue
        cues.append(Cue(len(cues), float(segment["start"]), float(segment["end"]), text))
    return cues


def gold_slots(entry: dict, take: int) -> list[tuple[float, float]]:
    """The take's dynamic ad slots as ``(start, end)`` pairs, sorted, non-degenerate."""
    slots = [
        (float(item["start"]), float(item["end"]))
        for item in entry.get("dynamic_slots", {}).get(str(take), [])
        if float(item["end"]) > float(item["start"])
    ]
    return sorted(slots)


def _union_length(spans: Sequence[tuple[float, float]]) -> float:
    total = 0.0
    current_start: float | None = None
    current_end = 0.0
    for start, end in sorted(spans):
        if current_start is None:
            current_start, current_end = start, end
        elif start <= current_end:
            current_end = max(current_end, end)
        else:
            total += current_end - current_start
            current_start, current_end = start, end
    if current_start is not None:
        total += current_end - current_start
    return total


def overlap_fraction(start: float, end: float, slots: Sequence[tuple[float, float]]) -> float:
    """Fraction of ``[start, end)`` covered by the union of ``slots``."""
    if end <= start:
        return 0.0
    clipped = [(max(start, a), min(end, b)) for a, b in slots if b > start and a < end]
    clipped = [(a, b) for a, b in clipped if b > a]
    return _union_length(clipped) / (end - start)


def gold_for_cues(
    cues: Sequence[Cue], slots: Sequence[tuple[float, float]]
) -> tuple[list[bool], list[float]]:
    """Label each cue by its midpoint; also return the cue's overlap fraction with the slots.

    Midpoint is the primary rule because whisper cues straddle pod edges: a cue that starts
    inside a pod but ends after it is mostly ad, one that starts before a pod is mostly
    content, and the midpoint is the cheap tiebreaker at cue resolution. The overlap
    fraction is kept so the ``>= 0.5`` rule can be compared at report time.
    """
    gold: list[bool] = []
    fractions: list[float] = []
    for cue in cues:
        midpoint = (cue.start + cue.end) / 2.0
        gold.append(any(start <= midpoint <= end for start, end in slots))
        fractions.append(overlap_fraction(cue.start, cue.end, slots))
    return gold, fractions


def baked_intervals(
    entry: dict, episode: str, take: int, use_legacy: bool = True
) -> list[tuple[float, float, str]]:
    """Baked-ad candidates for one take as ``(start, end, source)``.

    The corpus labels always carry ``baked_candidates: []`` (``merge_legacy.py`` writes an
    empty list, and the diff-based builder emits only dynamic slots), so the legacy label
    files are consulted for the ten episodes they overlap. They are read, never modified.
    """
    found: list[tuple[float, float, str]] = []
    for item in entry.get("baked_candidates") or []:
        if "take_intervals" in item:
            span = (item["take_intervals"] or {}).get(str(take))
            if span:
                found.append((float(span["start"]), float(span["end"]), "corpus"))
        elif "start" in item and "end" in item:
            found.append((float(item["start"]), float(item["end"]), "corpus"))
    if found or not use_legacy:
        return sorted(found)
    for path in LEGACY_LABEL_FILES:
        if not path.is_file():
            continue
        legacy = json.loads(path.read_text(encoding="utf-8"))
        for item in (legacy.get(episode) or {}).get("baked_candidates") or []:
            span = (item.get("take_intervals") or {}).get(str(take))
            if span:
                found.append((float(span["start"]), float(span["end"]), path.name))
    return sorted(found)


def load_episode(
    corpus: Path, episode: str, entry: dict, take: int, use_legacy_baked: bool = True
) -> Episode | None:
    """Build one episode to score, or ``None`` when the take has no cues or no gold slots."""
    if not (corpus / "transcripts" / episode / f"take{take}.json").is_file():
        return None
    slots = gold_slots(entry, take)
    if not slots:
        return None
    cues = load_cues(corpus, episode, take)
    if not cues:
        return None
    gold, fractions = gold_for_cues(cues, slots)
    return Episode(
        episode=episode,
        take=take,
        show=str(entry.get("show") or episode.split("__")[0]),
        duration=float(entry.get("duration") or 0.0),
        cues=cues,
        slots=slots,
        gold=gold,
        gold_overlap=fractions,
        baked=baked_intervals(entry, episode, take, use_legacy_baked),
    )


# --- Windows and cache -----------------------------------------------------------------


def window_bounds(n_cues: int, k: int) -> list[tuple[int, int]]:
    """Non-overlapping ``[start, stop)`` windows of at most ``k`` cues; never empty."""
    if k < 1:
        raise ValueError("k must be at least 1")
    return [(start, min(start + k, n_cues)) for start in range(0, n_cues, k)]


def window_sentences(cues: Sequence[Cue], start: int, stop: int) -> list[dict]:
    """A window as request sentences, with ids local to the window (1..n)."""
    return [{"id": position + 1, "text": cue.text} for position, cue in enumerate(cues[start:stop])]


def cache_path(out_dir: Path, episode: str, take: int) -> Path:
    return out_dir / f"{episode}__take{take}.jsonl"


def read_cache(path: Path) -> dict[int, dict]:
    """Cached predictions keyed by cue index; unreadable lines are ignored."""
    rows: dict[int, dict] = {}
    if not path.is_file():
        return rows
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
            rows[int(row["idx"])] = row
        except (json.JSONDecodeError, KeyError, TypeError, ValueError):
            continue
    return rows


def write_cache(path: Path, rows: dict[int, dict]) -> None:
    """Rewrite the file in cue order. Small (one line per cue), so cheap and crash-safe enough."""
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [json.dumps(rows[idx], ensure_ascii=False) for idx in sorted(rows)]
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def cached_window(rows: dict[int, dict], cues: Sequence[Cue], start: int, stop: int) -> bool:
    """True when every cue of the window is cached, non-missing, and matches the transcript."""
    for position in range(start, stop):
        row = rows.get(position)
        if row is None or row.get("missing"):
            return False
        if row.get("text") != cues[position].text:
            return False
    return True


def rows_from_batch(batch: Any, cues: Sequence[Cue], start: int, stop: int) -> list[dict]:
    """Map a window's ``BatchResult`` back onto cue indices."""
    rows: list[dict] = []
    per_sentence = list(getattr(batch, "per_sentence", []))
    for offset, cue in enumerate(cues[start:stop]):
        result = per_sentence[offset] if offset < len(per_sentence) else None
        probabilities = dict(getattr(result, "probabilities", {}) or {})
        rows.append(
            {
                "idx": cue.idx,
                "start": cue.start,
                "end": cue.end,
                "text": cue.text,
                "label": getattr(result, "label", None),
                "p_ad": probabilities.get("advertisement"),
                "p_content": probabilities.get("content"),
                "p_transition": probabilities.get("transition"),
                "missing": bool(getattr(result, "missing", result is None)),
            }
        )
    return rows


def is_quota_error(exc: SystemOneError) -> bool:
    """True when the account's free usage is gone, so retrying the run cannot help.

    Measured body: HTTP 429 ``{"error":{"type":"FreeUsageLimitError","message":"Free usage
    limit reached..."}}``.
    """
    if exc.status == 402:
        return True
    if exc.status != 429:
        return False
    body = (exc.body or "").lower()
    return "freeusagelimit" in body or "usage limit" in body


def classify_window(
    podcast: str,
    cues: Sequence[Cue],
    start: int,
    stop: int,
    model: str,
    timeout: float,
    retries: int = 3,
    backoff: Sequence[float] = (1.0, 2.0, 4.0),
    sleep: Any = time.sleep,
) -> tuple[list[dict], dict]:
    """Classify one window, retrying transport/HTTP failures with the given backoff.

    A quota error is raised at once: it is account-wide, so retrying only burns time.
    """
    sentences = window_sentences(cues, start, stop)
    attempts = 0
    last_error: Exception | None = None
    for attempt in range(retries + 1):
        attempts += 1
        try:
            batch = classify_batch(podcast, sentences, model=model, timeout=timeout)
        except MissingApiKey:
            raise
        except SystemOneError as exc:
            if is_quota_error(exc):
                raise
            last_error = exc
            if attempt < retries:
                sleep(backoff[min(attempt, len(backoff) - 1)])
            continue
        return rows_from_batch(batch, cues, start, stop), {
            "attempts": attempts,
            "usage": dict(getattr(batch, "usage", {}) or {}),
        }
    raise last_error if last_error else SystemOneError(None, "", "classify_batch failed")


# --- Metrics ---------------------------------------------------------------------------


@dataclass
class Pod:
    start: float
    end: float
    first: int
    last: int

    @property
    def duration(self) -> float:
        return self.end - self.start


def ad_flags(rows: Sequence[dict], threshold: float) -> list[bool]:
    """Cue-level ad decision; a missing answer counts as not-ad (conservative)."""
    flags: list[bool] = []
    for row in rows:
        score = row.get("p_ad")
        flags.append(bool(score is not None and float(score) >= threshold))
    return flags


def merge_pods(
    cues: Sequence[Cue],
    flags: Sequence[bool],
    max_gap: int = MAX_GAP_CUES,
    min_seconds: float = MIN_POD_SECONDS,
) -> list[Pod]:
    """Merge consecutive ad cues into pods, bridging gaps of up to ``max_gap`` cues."""
    if len(cues) != len(flags):
        raise ValueError("cues and flags must be the same length")
    if max_gap < 0:
        raise ValueError("max_gap must be non-negative")
    pods: list[Pod] = []
    first: int | None = None
    last = 0
    gap = 0
    for index, flag in enumerate(flags):
        if flag:
            if first is None:
                first = index
                gap = 0
            last = index
            continue
        if first is None:
            continue
        gap += 1
        if gap > max_gap:
            pods.append(Pod(cues[first].start, cues[last].end, first, last))
            first, gap = None, 0
    if first is not None:
        pods.append(Pod(cues[first].start, cues[last].end, first, last))
    return [pod for pod in pods if pod.end - pod.start >= min_seconds]


def match_pods(
    predicted: Sequence[Pod], gold: Sequence[tuple[float, float]], tolerance: float = MATCH_TOLERANCE
) -> tuple[list[tuple[int, int]], list[int], list[int]]:
    """Greedy one-to-one match; a hit needs both boundaries within ``tolerance`` seconds.

    Returns ``(matches, unmatched_predicted, unmatched_gold)`` where a match is
    ``(predicted_index, gold_index)``. Greedy by predicted start is deterministic and,
    because pods are disjoint and the tolerance is far below the shortest pod, it cannot
    strand an obviously better pairing.
    """
    used = [False] * len(gold)
    matches: list[tuple[int, int]] = []
    unmatched_predicted: list[int] = []
    for p_index, pod in enumerate(predicted):
        hit = None
        for g_index, (start, end) in enumerate(gold):
            if used[g_index]:
                continue
            if abs(pod.start - start) <= tolerance and abs(pod.end - end) <= tolerance:
                hit = g_index
                break
        if hit is None:
            unmatched_predicted.append(p_index)
        else:
            used[hit] = True
            matches.append((p_index, hit))
    unmatched_gold = [index for index, seen in enumerate(used) if not seen]
    return matches, unmatched_predicted, unmatched_gold


def _ratio(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


def _f1(precision: float | None, recall: float | None) -> float | None:
    if not precision or not recall:
        return None
    return 2 * precision * recall / (precision + recall)


def cue_confusion(
    rows: Sequence[dict], gold: Sequence[bool], threshold: float, include_transition: bool = False
) -> dict[str, int]:
    """Cue-level confusion counts for one threshold; ``transition`` optionally counts as ad."""
    tp = fp = fn = 0
    for row, is_gold_ad in zip(rows, gold):
        score = row.get("p_ad")
        predicted_ad = bool(score is not None and float(score) >= threshold)
        if include_transition and not predicted_ad:
            transition = row.get("p_transition")
            predicted_ad = bool(transition is not None and float(transition) >= threshold)
        if predicted_ad and is_gold_ad:
            tp += 1
        elif predicted_ad:
            fp += 1
        elif is_gold_ad:
            fn += 1
    return {"tp": tp, "fp": fp, "fn": fn}


def cue_metrics(
    episodes: Sequence[Episode],
    threshold: float,
    include_transition: bool = False,
    golds: dict[str, Sequence[bool]] | None = None,
) -> dict:
    """Aggregate cue-level metrics; ``golds`` overrides each episode's gold (overlap-rule check)."""
    tp = fp = fn = 0
    for episode in episodes:
        gold = golds[episode.episode] if golds is not None else episode.gold
        counts = cue_confusion(episode.rows, gold, threshold, include_transition=include_transition)
        tp += counts["tp"]
        fp += counts["fp"]
        fn += counts["fn"]
    precision = _ratio(tp, tp + fp)
    recall = _ratio(tp, tp + fn)
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "predicted": tp + fp,
        "gold": tp + fn,
        "precision": precision,
        "recall": recall,
        "f1": _f1(precision, recall),
    }


def episode_interval_counts(
    episode: Episode,
    threshold: float,
    max_gap: int = MAX_GAP_CUES,
    min_seconds: float = MIN_POD_SECONDS,
) -> dict:
    """Pods, hits and gold slot count for one episode at one threshold."""
    pods = merge_pods(episode.cues, ad_flags(episode.rows, threshold), max_gap=max_gap, min_seconds=min_seconds)
    matches, unmatched_predicted, unmatched_gold = match_pods(pods, episode.slots)
    return {
        "episode": episode.episode,
        "pods": len(pods),
        "hits": len(matches),
        "gold": len(episode.slots),
        "unmatched_predicted": unmatched_predicted,
        "unmatched_gold": unmatched_gold,
        "matches": matches,
    }


def interval_metrics(
    episodes: Sequence[Episode],
    threshold: float,
    max_gap: int = MAX_GAP_CUES,
    min_seconds: float = MIN_POD_SECONDS,
) -> dict:
    per_episode = [
        episode_interval_counts(episode, threshold, max_gap=max_gap, min_seconds=min_seconds)
        for episode in episodes
    ]
    pods = sum(item["pods"] for item in per_episode)
    hits = sum(item["hits"] for item in per_episode)
    gold = sum(item["gold"] for item in per_episode)
    precision = _ratio(hits, pods)
    recall = _ratio(hits, gold)
    return {
        "per_episode": per_episode,
        "pods": pods,
        "hits": hits,
        "gold": gold,
        "precision": precision,
        "recall": recall,
        "f1": _f1(precision, recall),
    }


def slot_coverage(episode: Episode, threshold: float) -> list[dict]:
    """Per gold slot: how much of it any predicted pod covers, and the union pod's edge error.

    Diagnostic only. The headline interval metric stays the 5 s both-boundary rule; this
    separates "the ad was not found" from "the ad was found but the edges are loose".
    """
    pods = merge_pods(episode.cues, ad_flags(episode.rows, threshold))
    diagnostics: list[dict] = []
    for gold_start, gold_end in episode.slots:
        overlapping = [pod for pod in pods if min(pod.end, gold_end) > max(pod.start, gold_start)]
        if not overlapping:
            diagnostics.append({"coverage": 0.0, "start_delta": None, "end_delta": None})
            continue
        covered = sum(
            max(0.0, min(pod.end, gold_end) - max(pod.start, gold_start)) for pod in overlapping
        )
        span_start = min(pod.start for pod in overlapping)
        span_end = max(pod.end for pod in overlapping)
        diagnostics.append(
            {
                "coverage": covered / (gold_end - gold_start),
                "start_delta": span_start - gold_start,
                "end_delta": span_end - gold_end,
                "pods": len(overlapping),
            }
        )
    return diagnostics


def coverage_summary(episodes: Sequence[Episode], threshold: float) -> dict:
    """Aggregate the per-slot coverage diagnostic, including a split-pod count."""
    slots = [item for episode in episodes for item in slot_coverage(episode, threshold)]
    found = [item for item in slots if item["coverage"] >= 0.8 and item["start_delta"] is not None]
    start_errors = sorted(abs(item["start_delta"]) for item in found)
    end_errors = sorted(abs(item["end_delta"]) for item in found)
    within_5s = sum(
        1
        for item in found
        if abs(item["start_delta"]) <= MATCH_TOLERANCE and abs(item["end_delta"]) <= MATCH_TOLERANCE
    )
    return {
        "slots": len(slots),
        "found_80": len(found),
        "split": sum(1 for item in found if item.get("pods", 1) > 1),
        "median_start_error": _median(start_errors),
        "median_end_error": _median(end_errors),
        "both_edges_within_5s": within_5s,
    }


def _median(values: Sequence[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2


#: Cheap, mechanical promo vocabulary. Used only to compare cue groups, never to relabel.
PROMO_MARKERS = re.compile(
    r"(dot\s?com|\.com\b|use code|promo code|sponsor|terms apply|% off|percent off|"
    r"\$\d|free trial|subscribe|sign up|coupon|shop now|learn more at|visit \w+|"
    r"call \d|limited time|offer\b)",
    re.IGNORECASE,
)


def cue_groups(episodes: Sequence[Episode], threshold: float) -> dict[str, dict[str, int]]:
    """Cues split into TP/FP/FN/TN with a promo-marker count per group.

    A false positive that reads like an ad (URL, discount, sponsor) is much more likely to
    be an unlabelled ad than a model error, so this rate is the evidence for the confound.
    """
    groups = {name: {"cues": 0, "markers": 0} for name in ("tp", "fp", "fn", "tn")}
    for episode in episodes:
        for row, is_gold_ad in zip(episode.rows, episode.gold):
            score = row.get("p_ad")
            predicted_ad = bool(score is not None and float(score) >= threshold)
            if predicted_ad and is_gold_ad:
                key = "tp"
            elif predicted_ad:
                key = "fp"
            elif is_gold_ad:
                key = "fn"
            else:
                key = "tn"
            groups[key]["cues"] += 1
            if PROMO_MARKERS.search(str(row.get("text") or "")):
                groups[key]["markers"] += 1
    return groups


def content_pods(episodes: Sequence[Episode], threshold: float) -> list[dict]:
    """Predicted pods that overlap no gold slot: candidate baked ads, not necessarily errors."""
    found: list[dict] = []
    for episode in episodes:
        for pod in merge_pods(episode.cues, ad_flags(episode.rows, threshold)):
            if any(min(pod.end, end) > max(pod.start, start) for start, end in episode.slots):
                continue
            scores = [
                float(row["p_ad"])
                for row in episode.rows[pod.first : pod.last + 1]
                if row.get("p_ad") is not None
            ]
            found.append(
                {
                    "episode": episode.episode,
                    "start": pod.start,
                    "end": pod.end,
                    "duration": pod.duration,
                    "cues": pod.last - pod.first + 1,
                    "mean_pad": sum(scores) / len(scores) if scores else 0.0,
                    "text": episode.cues[pod.first].text,
                }
            )
    return sorted(found, key=lambda item: -item["duration"])


def slot_table(episodes: Sequence[Episode], threshold: float) -> list[dict]:
    """One row per gold slot: coverage, predicted union span, edge errors, official hit."""
    rows: list[dict] = []
    for episode in episodes:
        diagnostics = slot_coverage(episode, threshold)
        pods = merge_pods(episode.cues, ad_flags(episode.rows, threshold))
        matches, _unmatched_pods, _unmatched_gold = match_pods(pods, episode.slots)
        hit_indices = {gold_index for _pod_index, gold_index in matches}
        for index, ((start, end), item) in enumerate(zip(episode.slots, diagnostics)):
            rows.append(
                {
                    "episode": episode.episode,
                    "gold_start": start,
                    "gold_end": end,
                    "coverage": item["coverage"],
                    "start_delta": item["start_delta"],
                    "end_delta": item["end_delta"],
                    "pods": item.get("pods", 0),
                    "hit": index in hit_indices,
                }
            )
    return rows


def baked_credit(episode: Episode, threshold: float) -> dict[str, int | float]:
    """Among predicted-in-content cues, how many sit on a baked-ad candidate."""
    if not episode.baked:
        return {"false_positives": 0, "on_baked": 0}
    spans = [(start, end) for start, end, _source in episode.baked]
    false_positives = 0
    on_baked = 0
    for row, is_gold_ad in zip(episode.rows, episode.gold):
        score = row.get("p_ad")
        if is_gold_ad or score is None or float(score) < threshold:
            continue
        false_positives += 1
        if overlap_fraction(float(row["start"]), float(row["end"]), spans) > 0.0:
            on_baked += 1
    return {"false_positives": false_positives, "on_baked": on_baked}


# --- Reporting -------------------------------------------------------------------------


def _pct(value: float | None, digits: int = 2) -> str:
    return "n/a" if value is None else f"{value:.{digits}f}"


def _seconds(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.1f}s"


def _signed(value: float | None) -> str:
    return "n/a" if value is None else f"{value:+.1f}s"


def _truncate(text: str, limit: int = 130) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def _timestamp(seconds: float) -> str:
    minutes, rest = divmod(int(round(seconds)), 60)
    hours, minutes = divmod(minutes, 60)
    return f"{hours}:{minutes:02d}:{rest:02d}" if hours else f"{minutes}:{rest:02d}"


def examples(episodes: Sequence[Episode], per_list: int = 10) -> dict[str, list[dict]]:
    """Concrete cues that make the failure modes legible, chosen deterministically.

    At most three cues come from one episode per list, so the examples are not all from the
    single most extreme show; the rest fill from the remaining candidates.
    """
    ad_rows: list[dict] = []
    content_rows: list[dict] = []
    for episode in episodes:
        for cue, row, is_gold_ad in zip(episode.cues, episode.rows, episode.gold):
            entry = {
                "episode": episode.episode,
                "take": episode.take,
                "start": cue.start,
                "text": cue.text,
                "p_ad": row.get("p_ad"),
                "label": row.get("label"),
                "missing": bool(row.get("missing")),
            }
            (ad_rows if is_gold_ad else content_rows).append(entry)

    def pick(candidates: list[dict], key: str, reverse: bool) -> list[dict]:
        ordered = sorted(candidates, key=key, reverse=reverse)
        chosen: list[dict] = []
        seen: dict[str, int] = {}
        for item in ordered:
            if seen.get(item["episode"], 0) >= 3:
                continue
            chosen.append(item)
            seen[item["episode"]] = seen.get(item["episode"], 0) + 1
            if len(chosen) == per_list:
                break
        for item in ordered:
            if len(chosen) == per_list:
                break
            if item not in chosen:
                chosen.append(item)
        return chosen[:per_list]

    def by_pad(item: dict) -> float:
        return item["p_ad"] or 0.0

    return {
        "ad_best": pick(ad_rows, by_pad, True),
        "ad_worst": pick(ad_rows, by_pad, False),
        "content_best": pick(content_rows, by_pad, True),
    }


def _example_table(rows: Sequence[dict]) -> list[str]:
    out = ["| p_ad | model label | episode (take) | at | cue |", "| --- | --- | --- | --- | --- |"]
    for row in rows:
        out.append(
            "| {p} | {label} | {episode} (t{take}) | {at} | {text} |".format(
                p="n/a" if row["p_ad"] is None else f"{row['p_ad']:.3f}",
                label=row["label"] or "missing",
                episode=row["episode"],
                take=row["take"],
                at=_timestamp(row["start"]),
                text=_truncate(row["text"]).replace("|", "/"),
            )
        )
    return out


def build_report(
    episodes: Sequence[Episode],
    thresholds: Sequence[float],
    args: argparse.Namespace,
    elapsed: float,
    requests: int,
    tokens_in: int,
    tokens_out: int,
    skipped: Sequence[str],
    unscored: Sequence[str] = (),
    aborted: str | None = None,
) -> str:
    cue = {threshold: cue_metrics(episodes, threshold) for threshold in thresholds}
    cue_trans = {threshold: cue_metrics(episodes, threshold, include_transition=True) for threshold in thresholds}
    intervals = {threshold: interval_metrics(episodes, threshold) for threshold in thresholds}

    total_cues = sum(len(episode.cues) for episode in episodes)
    total_gold = sum(sum(1 for flag in episode.gold if flag) for episode in episodes)
    total_missing = sum(episode.missing for episode in episodes)
    baked_total = sum(len(episode.baked) for episode in episodes)
    baked_episodes = [episode for episode in episodes if episode.baked]

    overlap_gold = {
        episode.episode: [fraction >= 0.5 for fraction in episode.gold_overlap] for episode in episodes
    }
    flips = sum(
        1
        for episode in episodes
        for midpoint, overlap in zip(episode.gold, overlap_gold[episode.episode])
        if midpoint != overlap
    )
    overlap_cue = {threshold: cue_metrics(episodes, threshold, golds=overlap_gold) for threshold in REPORT_THRESHOLDS}

    lines: list[str] = [
        "# JEV against the M0 labelled corpus",
        "",
        f"Generated {time.strftime('%Y-%m-%d %H:%M:%S')} by `next/jev/eval_corpus.py`.",
        "",
        "## Run",
        "",
        f"- Model: `{args.model}`, take `{args.take}`, K={args.k} cues per request, "
        f"{args.workers} workers",
        f"- Episodes scored: {len(episodes)}"
        + (f" ({len(skipped)} candidates skipped: no cues or no dynamic slots)" if skipped else ""),
        f"- Cues: {total_cues} total, {total_gold} gold ad ({100 * total_gold / max(total_cues, 1):.1f}%)",
        f"- Requests made: {requests}" if requests else "- Requests: not recorded in this run (cache-only report)",
        *([f"- Wall clock {elapsed / 60:.1f} min"] if elapsed >= 5 else []),
        *([f"- Tokens reported by the service: {tokens_in} in / {tokens_out} out"] if tokens_in or tokens_out else []),
        f"- Missing answers: {total_missing} cues ({100 * total_missing / max(total_cues, 1):.2f}%)",
    ]
    note = getattr(args, "note", None)
    if note:
        lines.append(f"- Run note: {note}")
    if unscored or aborted:
        lines += [
            "",
            "> **Partial run.** " + (
                f"The API stopped with `{aborted.splitlines()[0][:200]}`"
                if aborted
                else "Not every episode could be fetched."
            ),
            f"> {len(unscored)} candidate episode(s) are not fully cached and are excluded from every",
            f"> number below, so this report covers {len(episodes)} episode(s) only. Re-run the same",
            "> command with the cache present to continue where it stopped.",
        ]
        if unscored:
            listed = ", ".join(f"`{name}`" for name in unscored[:12]) + ("…" if len(unscored) > 12 else "")
            lines += ["", f"Excluded (incomplete cache): {listed}", ""]

    lines += [
        "",
        "## Method",
        "",
        "1. **Gold.** For the scored take, `dynamic_slots` in `corpus/labels/ads.json` are the ad",
        "   pods. A cue is `advertisement` when its midpoint falls inside a slot, else `content`.",
        "   The midpoint is used because whisper cues straddle pod edges: a cue that starts",
        "   inside a pod and ends after it is mostly ad, one that starts before a pod is mostly",
        "   content, and midpoint is the cheapest tiebreaker at cue resolution. The overlap",
        "   fraction is recorded as well and the `>= 0.5` rule is reported as a check below.",
        f"2. **Classification.** Cues go out in non-overlapping windows of K={args.k}, one",
        "   `classify_batch` per window, cue index → integer id local to the window. Windows run",
        "   on a small thread pool; a failed window is retried 3 times with 1/2/4 s backoff.",
        "   No episode is ever sent as one request.",
        "3. **Cache.** `<episode>__take<N>.jsonl`, one line per cue with `start`, `end`, `text`,",
        "   `label`, `p_ad`, `p_content`, `p_transition`, `missing`. Only predictions are stored;",
        "   thresholds are applied here, at report time. Resume is on unless `--no-resume`; a",
        "   window counts as cached only when every cue in it is present, non-missing, and its",
        "   cached text still matches the transcript (a stale cache can never be reused).",
        "4. **Cue metrics.** Prediction is `p_ad >= t`; a missing answer counts as not-ad. The",
        "   variant where `p_transition >= t` also counts as ad shows the price of the",
        "   advertisement/transition boundary.",
        "5. **Interval metrics.** Consecutive ad cues merge into a pod, bridging gaps of up to",
        f"   {MAX_GAP_CUES} cue; pods shorter than {MIN_POD_SECONDS:.0f} s are dropped. A pod is a hit only when",
        f"   **both** boundaries are within {MATCH_TOLERANCE:.0f} s of a gold slot's boundaries (greedy one-to-one",
        "   matching). Episodes with no dynamic slots are skipped.",
        "6. **Baked candidates.** A predicted-in-content cue can be a real ad the labels omit.",
        "   The corpus labels carry `baked_candidates: []` for every episode, so the legacy files",
        "   (`next/labels/ads_v3.json`, `ads_v2.json`) are read for the ten episodes they overlap.",
        "   Nothing is relabelled; the confound is only counted.",
        "",
        "## Cue level",
        "",
        "| threshold | pred ad | TP | FP | FN | precision | recall | F1 | precision if transition counts |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for threshold in thresholds:
        row = cue[threshold]
        lines.append(
            f"| {threshold:.1f} | {row['predicted']} | {row['tp']} | {row['fp']} | {row['fn']} | "
            f"{_pct(row['precision'])} | {_pct(row['recall'])} | {_pct(row['f1'])} | "
            f"{_pct(cue_trans[threshold]['precision'])} |"
        )
    lines += [
        "",
        f"Gold is {total_gold} ad cues of {total_cues}. Calling every cue content gives recall 0; "
        f"calling every cue ad gives precision {_pct(_ratio(total_gold, total_cues))} and recall 1.00.",
        "",
        "## Interval level",
        "",
        "| threshold | pred pods | hits | gold slots | precision | recall |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for threshold in thresholds:
        row = intervals[threshold]
        lines.append(
            f"| {threshold:.1f} | {row['pods']} | {row['hits']} | {row['gold']} | "
            f"{_pct(row['precision'])} | {_pct(row['recall'])} |"
        )
    lines += [
        "",
        "A hit requires both edges within 5 s. A pod that covers an ad but starts 9 s early",
        "counts as a miss and as a false positive, which is the project's acceptance criterion.",
        "",
        "### Coverage diagnostic (not the acceptance criterion)",
        "",
        "To separate \"the ad was not found\" from \"the ad was found with loose edges\": does any",
        "predicted pod cover at least 80% of the gold slot? Deltas are the union span of the",
        "overlapping pods minus the gold slot, so a split pod still measures the edges it missed.",
        "",
        "| threshold | slots | found (>=80% covered) | split across pods | median abs start error "
        "| median abs end error | both edges within 5 s |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for threshold in thresholds:
        row = coverage_summary(episodes, threshold)
        lines.append(
            f"| {threshold:.1f} | {row['slots']} | {row['found_80']} | {row['split']} | "
            f"{_seconds(row['median_start_error'])} | {_seconds(row['median_end_error'])} | "
            f"{row['both_edges_within_5s']} |"
        )

    lines += [
        "",
        "### Per episode (pods / hits of gold slots)",
        "",
        "| episode | gold slots | " + " | ".join(f"t={t:.1f}" for t in thresholds) + " |",
        "| --- | --- | " + " | ".join("---" for _ in thresholds) + " |",
    ]
    by_threshold = {
        threshold: {item["episode"]: item for item in intervals[threshold]["per_episode"]}
        for threshold in thresholds
    }
    for episode in episodes:
        cells = [
            "{pods} / {hits} of {gold}".format(**by_threshold[threshold][episode.episode])
            for threshold in thresholds
        ]
        lines.append(f"| {episode.episode} | {len(episode.slots)} | " + " | ".join(cells) + " |")

    lines += [
        "",
        "### Every gold slot at t=0.5",
        "",
        "| episode | gold slot | predicted coverage | union start error | union end error "
        "| pods overlapping | hit (5 s, official) |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for item in slot_table(episodes, 0.5):
        lines.append(
            f"| {item['episode']} | {_timestamp(item['gold_start'])}-{_timestamp(item['gold_end'])} | "
            f"{100 * item['coverage']:.0f}% | {_signed(item['start_delta'])} | "
            f"{_signed(item['end_delta'])} | {item['pods']} | {'yes' if item['hit'] else 'no'} |"
        )

    lines += [
        "",
        "## Baked-candidate confound",
        "",
        f"Baked-ad intervals are available for {len(baked_episodes)} of {len(episodes)} episodes "
        f"({baked_total} intervals); the corpus labels contain none.",
        "",
    ]
    if not baked_total:
        lines += [
            "With no baked candidates for these episodes the confound cannot be quantified: every",
            "predicted-in-content cue is a plain false positive below, so cue precision is a lower",
            "bound on true ad precision.",
        ]
    else:
        lines += [
            "| threshold | content cues called ad | on a baked candidate | share "
            "| precision as scored | precision if those were ads |",
            "| --- | --- | --- | --- | --- | --- |",
        ]
        for threshold in thresholds:
            fp = on_baked = 0
            for episode in episodes:
                credit = baked_credit(episode, threshold)
                fp += int(credit["false_positives"])
                on_baked += int(credit["on_baked"])
            row = cue[threshold]
            credited = _ratio(row["tp"] + on_baked, row["tp"] + row["fp"])
            lines.append(
                f"| {threshold:.1f} | {fp} | {on_baked} | {100 * on_baked / max(fp, 1):.1f}% | "
                f"{_pct(row['precision'])} | {_pct(credited)} |"
            )
        lines += [
            "",
            "This covers the ten legacy episodes only; the other 37 have no baked candidates in",
            "any label file, so their precision loss stays unattributed. Where a baked candidate",
            "also sits inside a gold slot (99-invisible, freakonomics and the-daily in `ads_v3.json`)",
            "the labels contradict themselves: the same passage is both gold ad and unlabelled ad.",
            "Scoring follows the gold slot, so that case neither helps nor hurts precision here.",
        ]

    groups = cue_groups(episodes, 0.5)
    misplaced = content_pods(episodes, 0.5)
    lines += [
        "",
        "### What the false positives actually are",
        "",
        "The corpus `baked_candidates` field is empty, so the confound is quantified two ways: a",
        "mechanical promo-vocabulary match per cue group, and a list of predicted pods that fall",
        "entirely outside every gold slot. Neither changes a label.",
        "",
        "| cue group @0.5 | cues | contain a promo marker | rate |",
        "| --- | --- | --- | --- |",
    ]
    for key, title in (
        ("tp", "gold ad, called ad"),
        ("fp", "gold content, called ad"),
        ("fn", "gold ad, called content"),
        ("tn", "gold content, called content"),
    ):
        group = groups[key]
        rate = _ratio(group["markers"], group["cues"])
        lines.append(f"| {title} | {group['cues']} | {group['markers']} | {_pct(rate)} |")
    lines += [
        "",
        f"Predicted pods that overlap no gold slot: {len(misplaced)}"
        + (
            f", {sum(item['duration'] for item in misplaced) / 60:.1f} minutes in total, "
            f"median {_seconds(_median([item['duration'] for item in misplaced]))}, "
            f"mean p_ad {_pct(sum(item['mean_pad'] for item in misplaced) / len(misplaced))}"
            if misplaced
            else ""
        ),
        "",
    ]
    if misplaced:
        lines += [
            "| seconds | mean p_ad | episode | first cue |",
            "| --- | --- | --- | --- |",
        ]
        for item in misplaced[:8]:
            lines.append(
                f"| {item['duration']:.0f} | {item['mean_pad']:.2f} | {item['episode']} | "
                f"{_truncate(item['text'], 110).replace('|', '/')} |"
            )
        lines += [
            "",
            "If these pods read like ads, the labels are incomplete rather than the model wrong: the",
            "take-diff method only finds pods that differ between takes, so a sponsor read baked into",
            "every take is labelled `content`. The marker rates above are the evidence either way.",
        ]

    lines += [
        "",
        "## Midpoint vs overlap gold",        "",
        f"{flips} of {total_cues} cues change gold between the midpoint rule and `overlap >= 0.5`.",
        "",
        "| rule | gold ad cues | " + " | ".join(f"P/R @{t:.1f}" for t in REPORT_THRESHOLDS) + " |",
        "| --- | --- | " + " | ".join("---" for _ in REPORT_THRESHOLDS) + " |",
    ]
    rows_for_rule = {"midpoint": cue, "overlap>=0.5": overlap_cue}
    for label, values in rows_for_rule.items():
        cells = [
            f"{_pct(values[t]['precision'])}/{_pct(values[t]['recall'])}" for t in REPORT_THRESHOLDS
        ]
        gold_count = total_gold if label == "midpoint" else sum(
            1 for gold in overlap_gold.values() for flag in gold if flag
        )
        lines.append(f"| {label} | {gold_count} | " + " | ".join(cells) + " |")

    lines += [
        "",
        "The interval tables do not depend on this rule: they use the gold slots directly.",
        "",
        "## Examples",
        "",
        "### Gold ad cues with the highest p_ad (found)",
        "",
    ]
    lines += _example_table(examples(episodes, 10)["ad_best"])
    lines += ["", "### Gold ad cues with the lowest p_ad (missed)", ""]
    lines += _example_table(examples(episodes, 10)["ad_worst"])
    lines += [
        "",
        "Some of these are native ad reads that sound like the show (dan-snow's 30 s TikTok read",
        "opens \"This episode is brought to you by TikTok\" and then narrates a TikTok video), which",
        "is a real model failure. Others sit in slots the diff padded or truncated: the CA tourism",
        "slot in cautionary-tales covers 21 s of a 94 s ad, and the model finds the part the label",
        "left out. The two failures are not distinguishable from the label alone.",
    ]
    lines += ["", "### Gold content cues with the highest p_ad (false positives)", ""]
    lines += _example_table(examples(episodes, 10)["content_best"])
    lines += [
        "",
        "## Caveats",
        "",
        "- Gold is take-specific and derived by diffing sibling takes, so a passage that is ad-like",
        "  and present in every take (a baked ad) is not labelled at all. Precision is a lower",
        "  bound wherever those exist.",
        "- Cue boundaries are whisper's, not the audio's. A cue that straddles a pod edge can be",
        "  scored ad while its midpoint is content, or the reverse; the midpoint rule picks the",
        "  less noisy side, it does not remove the noise.",
        "- Native and host-read ads that imitate the show are where the real recall goes. dan-snow's",
        "  30 s TikTok read (25:27) opens \"This episode is brought to you by TikTok\", narrates a",
        "  TikTok video and scores 0.00-0.02 until its closing tagline; 99-invisible's opening ad",
        "  (0:00-0:12) is soft-sell copy that scores 0.01. The model needs the brand or a call to",
        "  action before it fires.",
        "- Some slots are truncated or padded by the take diff, which also moves both metrics. The",
        "  cautionary-tales slot at 14:07 covers 21 s of a 94 s CA tourism ad, so 70 s of real ad",
        "  is gold content; `odd-lots__what-nvidia-s-megaspeed-scandal-is-really` take 0 has a single",
        "  1431 s slot (38% of the episode). Cue precision is a lower bound wherever this happens.",
        "- The full 47-episode run did not finish: the free model returned HTTP 429",
        "  `FreeUsageLimitError` after about 200 requests. Only 5 episodes are scored in this",
        "  report, which is far too few for a corpus-level claim; the per-episode table shows how",
        "  much the numbers move between shows.",
        "- Sentences are scored inside a window of K cues, so a pod's first cue has no ad neighbour",
        "  for context; the README records that context changes the answer.",
        "- The model is nondeterministic (the same sentence measured 0.92 and 0.96 on two runs), so",
        "  thresholds near a probability plateau move a few cues between runs.",
        "- A pod inside a content stretch is not automatically an error: it can be a baked ad, a",
        "  sponsor mention in editorial copy, or a promo for a sister show.",
        "",
    ]
    return "\n".join(lines)


def print_tables(episodes: Sequence[Episode], thresholds: Sequence[float]) -> dict:
    cue = {threshold: cue_metrics(episodes, threshold) for threshold in thresholds}
    cue_trans = {threshold: cue_metrics(episodes, threshold, include_transition=True) for threshold in thresholds}
    intervals = {threshold: interval_metrics(episodes, threshold) for threshold in thresholds}

    print("\nCUE LEVEL  (gold = cue midpoint inside a dynamic slot; missing counts as not-ad)")
    print(" thr   predad    TP    FP    FN   prec   rec     F1   prec(ad|transition)")
    for threshold in thresholds:
        row = cue[threshold]
        print(
            f"{threshold:4.1f} {row['predicted']:8d} {row['tp']:5d} {row['fp']:5d} {row['fn']:5d} "
            f"{_pct(row['precision']):>6} {_pct(row['recall']):>5} {_pct(row['f1']):>6} "
            f"{_pct(cue_trans[threshold]['precision']):>12}"
        )

    print("\nINTERVAL LEVEL  (pods: gap<=1 cue, >=8 s, hit = both bounds within 5 s)")
    print(" thr   pods  hits  gold   prec    rec")
    for threshold in thresholds:
        row = intervals[threshold]
        print(
            f"{threshold:4.1f} {row['pods']:6d} {row['hits']:5d} {row['gold']:5d} "
            f"{_pct(row['precision']):>6} {_pct(row['recall']):>6}"
        )

    print("\nCOVERAGE DIAGNOSTIC  (diagnostic only: does any pod cover >=80% of the slot?)")
    print(" thr  slots  found  split  med|dstart|  med|dend|  both<=5s")
    for threshold in thresholds:
        row = coverage_summary(episodes, threshold)
        print(
            f"{threshold:4.1f} {row['slots']:6d} {row['found_80']:6d} {row['split']:6d} "
            f"{_seconds(row['median_start_error']):>11} {_seconds(row['median_end_error']):>9} "
            f"{row['both_edges_within_5s']:>8}"
        )
    return {"cue": cue, "cue_trans": cue_trans, "interval": intervals}


def parse_thresholds(text: str) -> tuple[float, ...]:
    values = sorted({float(part) for part in text.split(",") if part.strip()})
    if not values:
        raise argparse.ArgumentTypeError("at least one threshold is required")
    for value in values:
        if not 0.0 <= value <= 1.0:
            raise argparse.ArgumentTypeError(f"threshold {value} is outside 0..1")
    return tuple(values)


def select_episodes(
    corpus: Path, take: int, limit: int | None, use_legacy_baked: bool
) -> tuple[list[Episode], list[str]]:
    labels = load_labels(corpus)
    episodes: list[Episode] = []
    skipped: list[str] = []
    for name in sorted(labels):
        episode = load_episode(corpus, name, labels[name], take, use_legacy_baked)
        if episode is None:
            skipped.append(name)
            continue
        episodes.append(episode)
        if limit is not None and len(episodes) >= limit:
            break
    return episodes, skipped


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--limit", type=int, default=None, help="score at most N episodes")
    parser.add_argument("--take", type=int, default=0, help="take index to score")
    parser.add_argument("--k", type=int, default=20, help="cues per request")
    parser.add_argument("--workers", type=int, default=4, help="concurrent window requests")
    parser.add_argument("--thresholds", type=parse_thresholds, default=DEFAULT_THRESHOLDS)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--corpus", type=Path, default=CORPUS)
    parser.add_argument("--dry-run", action="store_true", help="count requests and episodes, call nothing")
    parser.add_argument("--no-resume", action="store_true", help="ignore cached predictions")
    parser.add_argument("--no-baked-fallback", action="store_true", help="do not read legacy baked candidates")
    parser.add_argument("--no-report", action="store_true", help="print tables only, write no REPORT.md")
    parser.add_argument(
        "--report-only", action="store_true", help="score the cache only; never call the API"
    )
    parser.add_argument("--requests", type=int, default=0, help="request count to record in the report")
    parser.add_argument("--note", default=None, help="free-text note recorded in the report")
    args = parser.parse_args(argv)

    if args.k < 1:
        parser.error("--k must be at least 1")
    if args.workers < 1:
        parser.error("--workers must be at least 1")

    started = time.perf_counter()
    episodes, skipped = select_episodes(
        args.corpus, args.take, args.limit, use_legacy_baked=not args.no_baked_fallback
    )
    if not episodes:
        print("no episodes with cues and dynamic slots for this take", file=sys.stderr)
        return 1

    cached: dict[str, dict[int, dict]] = {}
    pending: dict[str, list[tuple[int, int]]] = {}
    for episode in episodes:
        path = cache_path(args.out_dir, episode.episode, episode.take)
        rows = {} if args.no_resume else read_cache(path)
        cached[episode.episode] = rows
        pending[episode.episode] = [
            (start, stop)
            for start, stop in window_bounds(len(episode.cues), args.k)
            if not cached_window(rows, episode.cues, start, stop)
        ]

    total_pending = sum(len(windows) for windows in pending.values())
    total_cues = sum(len(episode.cues) for episode in episodes)
    print(
        f"{len(episodes)} episodes, {total_cues} cues, take {args.take}, K={args.k}, "
        f"{args.workers} workers; {total_pending} windows to fetch"
    )
    if skipped:
        print(f"skipped {len(skipped)} episodes without cues or dynamic slots for take {args.take}")

    if args.dry_run:
        print(f"\nDRY RUN: {total_pending} requests would be made across {len(episodes)} episodes")
        for episode in episodes:
            windows = pending[episode.episode]
            print(
                f"  {episode.episode[:66]:68s} cues {len(episode.cues):4d} "
                f"windows {len(windows):3d} slots {len(episode.slots):2d}"
            )
        return 0

    requests = 0
    tokens_in = 0
    tokens_out = 0
    failures: list[str] = []
    aborted: str | None = None

    for episode in episodes:
        if aborted or args.report_only:
            break
        windows = pending[episode.episode]
        if not windows:
            continue
        rows = cached[episode.episode]
        path = cache_path(args.out_dir, episode.episode, episode.take)
        lock = threading.Lock()
        episode_started = time.perf_counter()

        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = {
                pool.submit(
                    classify_window,
                    episode.show,
                    episode.cues,
                    start,
                    stop,
                    args.model,
                    args.timeout,
                ): (start, stop)
                for start, stop in windows
            }
            for future in as_completed(futures):
                start, stop = futures[future]
                try:
                    window_rows, meta = future.result()
                except MissingApiKey:
                    raise
                except SystemOneError as exc:
                    if is_quota_error(exc):
                        aborted = f"{exc}: {exc.body}"
                        for other in futures:
                            other.cancel()
                        break
                    failures.append(f"{episode.episode} [{start}:{stop}]: {exc}")
                    print(f"  FAILED {episode.episode} [{start}:{stop}]: {exc}")
                    continue
                requests += int(meta.get("attempts", 1))
                usage = meta.get("usage") or {}
                tokens_in += int(usage.get("input_tokens") or 0)
                tokens_out += int(usage.get("output_tokens") or 0)
                with lock:
                    for row in window_rows:
                        rows[row["idx"]] = row
                    write_cache(path, rows)
        print(
            f"  {episode.episode[:60]:62s} cues {len(episode.cues):4d} "
            f"windows {len(windows):3d} missing {episode.missing:3d} "
            f"({time.perf_counter() - episode_started:.0f}s)"
        )
        if aborted:
            print(f"\nQUOTA: {aborted}")
            print("stopped fetching; episodes that are not fully cached are excluded from the score")

    scored: list[Episode] = []
    unscored: list[str] = []
    for episode in episodes:
        rows = cached[episode.episode]
        complete = all(cue.idx in rows and not rows[cue.idx].get("missing") for cue in episode.cues)
        if not complete:
            unscored.append(episode.episode)
            continue
        episode.rows = [rows[cue.idx] for cue in episode.cues]
        scored.append(episode)

    if unscored:
        print(
            f"\n{len(unscored)} of {len(episodes)} episodes have incomplete cache and are excluded "
            f"({len(scored)} scored, {sum(len(e.cues) for e in scored)} cues)"
        )

    if failures:
        print(f"\n{len(failures)} failed window(s) (see above)")

    print_tables(scored, args.thresholds)

    print("\nBAKED CANDIDATES")
    for threshold in args.thresholds:
        fp = on_baked = 0
        for episode in scored:
            credit = baked_credit(episode, threshold)
            fp += int(credit["false_positives"])
            on_baked += int(credit["on_baked"])
        print(f"  t={threshold:.1f}: {on_baked} of {fp} content cues called ad sit on a baked candidate")
    baked_available = sum(len(episode.baked) for episode in scored)
    print(
        f"  baked intervals available for {sum(1 for e in scored if e.baked)} of {len(scored)} episodes "
        f"({baked_available} total); the corpus labels carry none"
    )

    groups = cue_groups(scored, 0.5)
    print("\nFALSE POSITIVES @0.5  (promo-marker rate per cue group; a proxy, not a relabel)")
    for key, title in (("tp", "gold ad, called ad"), ("fp", "content, called ad"), ("fn", "gold ad, called content"),
                       ("tn", "content, called content")):
        group = groups[key]
        print(
            f"  {title:26s} {group['cues']:6d} cues, {group['markers']:5d} with a promo marker "
            f"({_pct(_ratio(group['markers'], group['cues']))})"
        )
    misplaced = content_pods(scored, 0.5)
    if misplaced:
        total = sum(item["duration"] for item in misplaced)
        mean = sum(item["mean_pad"] for item in misplaced) / len(misplaced)
        print(
            f"  {len(misplaced)} predicted pods overlap no gold slot: {total / 60:.1f} min total, "
            f"median {_seconds(_median([item['duration'] for item in misplaced]))}, mean p_ad {_pct(mean)}"
        )

    elapsed = time.perf_counter() - started
    print(f"\n{requests} requests, {tokens_in} input / {tokens_out} output tokens, {elapsed / 60:.1f} min")

    if not args.no_report:
        args.out_dir.mkdir(parents=True, exist_ok=True)
        report = build_report(
            scored,
            args.thresholds,
            args,
            elapsed,
            args.requests or requests,
            tokens_in,
            tokens_out,
            skipped,
            unscored=unscored,
            aborted=aborted,
        )
        path = args.out_dir / f"REPORT_take{args.take}.md"
        path.write_text(report, encoding="utf-8")
        print(f"report: {path}")

    return 2 if aborted else (1 if failures else 0)


if __name__ == "__main__":
    raise SystemExit(main())
