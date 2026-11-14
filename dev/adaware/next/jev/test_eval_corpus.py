"""Offline tests for the corpus scorer.

No network and no API key: every request path is patched. The corpus is only read inside a
``tmp_path`` fixture, never from ``next/m0``. Run from ``dev/adaware``::

    uv run python -m pytest next/jev -q
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

import eval_corpus as ev
from jev_client import SentenceResult, SystemOneError


def cue(idx: int, start: float, end: float, text: str = "text") -> ev.Cue:
    return ev.Cue(idx, start, end, text)


def row(idx: int, start: float, end: float, p_ad=None, p_transition=None) -> dict:
    return {
        "idx": idx,
        "start": start,
        "end": end,
        "text": f"cue {idx}",
        "label": "advertisement" if (p_ad or 0) >= 0.5 else "content",
        "p_ad": p_ad,
        "p_content": None if p_ad is None else 1 - p_ad,
        "p_transition": p_transition,
        "missing": p_ad is None,
    }


def make_episode(rows: list[dict], gold: list[bool], slots=None, baked=()) -> ev.Episode:
    cues = [cue(i, r["start"], r["end"], r["text"]) for i, r in enumerate(rows)]
    return ev.Episode(
        episode="show__episode",
        take=0,
        show="Show",
        duration=max((r["end"] for r in rows), default=0.0),
        cues=cues,
        slots=list(slots or []),
        gold=list(gold),
        gold_overlap=[0.0 for _ in rows],
        baked=list(baked),
        rows=list(rows),
    )


# --- Gold ------------------------------------------------------------------------------


def test_gold_slots_reads_the_take_and_drops_degenerate_slots():
    entry = {
        "dynamic_slots": {
            "0": [{"start": 30.0, "end": 20.0}, {"start": 10.0, "end": 20.0}, {"start": 40.5, "end": 44.0}],
            "1": [{"start": 1.0, "end": 2.0}],
        }
    }
    assert ev.gold_slots(entry, 0) == [(10.0, 20.0), (40.5, 44.0)]
    assert ev.gold_slots(entry, 1) == [(1.0, 2.0)]
    assert ev.gold_slots(entry, 2) == []


def test_gold_for_cues_uses_the_midpoint():
    slots = [(10.0, 20.0)]
    cues = [
        cue(0, 0.0, 8.0),  # before
        cue(1, 9.0, 11.0),  # midpoint 10.0: inside
        cue(2, 16.0, 24.0),  # midpoint 20.0: inside, ends outside
        cue(3, 24.0, 30.0),  # after
    ]
    gold, fractions = ev.gold_for_cues(cues, slots)
    assert gold == [False, True, True, False]
    # The trailing cue overlaps half the slot, but its midpoint is outside it.
    assert fractions[2] == pytest.approx(0.5)
    assert fractions[1] == pytest.approx(0.5)
    assert fractions[3] == 0.0


def test_overlap_fraction_does_not_double_count_adjacent_slots():
    slots = [(0.0, 5.0), (4.0, 10.0)]
    assert ev.overlap_fraction(0.0, 10.0, slots) == pytest.approx(1.0)
    assert ev.overlap_fraction(9.0, 20.0, slots) == pytest.approx(1 / 11)
    assert ev.overlap_fraction(10.0, 20.0, slots) == 0.0
    assert ev.overlap_fraction(10.0, 10.0, slots) == 0.0


# --- Windows ---------------------------------------------------------------------------


def test_window_bounds_cover_every_cue_once():
    assert ev.window_bounds(40, 20) == [(0, 20), (20, 40)]
    assert ev.window_bounds(41, 20) == [(0, 20), (20, 40), (40, 41)]
    assert ev.window_bounds(3, 20) == [(0, 3)]
    assert ev.window_bounds(5, 1) == [(i, i + 1) for i in range(5)]
    assert ev.window_bounds(0, 20) == []
    with pytest.raises(ValueError):
        ev.window_bounds(10, 0)


def test_window_sentences_use_ids_local_to_the_window():
    cues = [cue(i, i, i + 1, f"cue {i}") for i in range(5)]
    sentences = ev.window_sentences(cues, 2, 4)
    assert sentences == [{"id": 1, "text": "cue 2"}, {"id": 2, "text": "cue 3"}]


# --- Cache -----------------------------------------------------------------------------


def test_cache_roundtrip_and_stale_detection(tmp_path: Path):
    path = tmp_path / "show__take0.jsonl"
    cues = [cue(0, 0.0, 2.0, "cue 0"), cue(1, 2.0, 4.0, "cue 1")]
    rows = {0: row(0, 0.0, 2.0, 0.9)}
    ev.write_cache(path, rows)

    loaded = ev.read_cache(path)
    assert list(loaded) == [0]
    assert ev.cached_window(loaded, cues, 0, 1) is True
    assert ev.cached_window(loaded, cues, 0, 2) is False  # cue 1 not cached

    # A missing answer is never a cache hit.
    rows[1] = row(1, 2.0, 4.0)  # p_ad None: a missing answer
    ev.write_cache(path, rows)
    assert ev.cached_window(ev.read_cache(path), cues, 0, 2) is False

    # A changed transcript invalidates the window even when the index is present.
    rows[1] = row(1, 2.0, 4.0, 0.1)
    ev.write_cache(path, rows)
    assert ev.cached_window(ev.read_cache(path), cues, 0, 2) is True
    cues[1] = cue(1, 2.0, 4.0, "different text")
    assert ev.cached_window(ev.read_cache(path), cues, 0, 2) is False


def test_read_cache_ignores_unreadable_lines(tmp_path: Path):
    path = tmp_path / "cache.jsonl"
    path.write_text('{"idx": 2, "p_ad": 0.4}\nnot json\n{"no_idx": 1}\n', encoding="utf-8")
    assert list(ev.read_cache(path)) == [2]


# --- Merge and match -------------------------------------------------------------------


def test_merge_pods_bridges_a_single_gap_cue_and_splits_a_longer_one():
    cues = [cue(i, i * 4.0, (i + 1) * 4.0) for i in range(8)]
    flags = [True, True, False, True, True, False, False, True]
    pods = ev.merge_pods(cues, flags, max_gap=1, min_seconds=0.0)
    assert [(pod.first, pod.last) for pod in pods] == [(0, 4), (7, 7)]
    assert pods[0].start == 0.0 and pods[0].end == 20.0

    split = ev.merge_pods(cues, flags, max_gap=0, min_seconds=0.0)
    assert [(pod.first, pod.last) for pod in split] == [(0, 1), (3, 4), (7, 7)]


def test_merge_pods_drops_short_pods():
    cues = [cue(0, 0.0, 3.0), cue(1, 3.0, 6.0), cue(2, 6.0, 20.0)]
    flags = [True, True, False]
    assert ev.merge_pods(cues, flags, min_seconds=8.0) == []
    assert len(ev.merge_pods(cues, flags, min_seconds=6.0)) == 1


def test_merge_pods_drops_a_short_tail_but_keeps_a_long_last_pod():
    cues = [cue(0, 0.0, 30.0), cue(1, 30.0, 32.0), cue(2, 100.0, 140.0)]
    pods = ev.merge_pods(cues, [True, True, True], min_seconds=8.0)
    assert len(pods) == 1
    assert pods[0].start == 0.0 and pods[0].end == 140.0  # one pod, gap of 68 s is a gap cue only
    pods = ev.merge_pods(cues, [False, True, True], min_seconds=8.0)
    assert [(pod.first, pod.last) for pod in pods] == [(1, 2)]


def test_merge_pods_rejects_mismatched_lengths():
    with pytest.raises(ValueError):
        ev.merge_pods([cue(0, 0.0, 1.0)], [], min_seconds=0.0)


def test_match_pods_needs_both_boundaries_within_five_seconds():
    gold = [(100.0, 160.0), (300.0, 340.0)]
    hit = ev.Pod(104.9, 155.1, 0, 1)
    start_off = ev.Pod(94.9, 160.0, 0, 1)  # 5.1 s early on the start edge
    end_off = ev.Pod(100.0, 165.1, 0, 1)
    matches, unmatched_predicted, unmatched_gold = ev.match_pods([hit], gold)
    assert matches == [(0, 0)]
    assert unmatched_gold == [1]
    assert ev.match_pods([start_off], gold)[0] == []
    assert ev.match_pods([end_off], gold)[0] == []
    assert ev.match_pods([ev.Pod(100.0, 160.0, 0, 1), ev.Pod(100.0, 160.0, 0, 1)], gold)[0] == [(0, 0)]
    assert ev.match_pods([ev.Pod(104.999, 155.001, 0, 1)], gold)[0] == [(0, 0)]


def test_interval_metrics_count_hits_pods_and_slots():
    slots = [(100.0, 160.0), (300.0, 340.0)]
    rows = []
    flags = []
    for i in range(80):
        start = i * 5.0
        is_ad = 100.0 <= start < 160.0 or 250.0 <= start < 270.0
        rows.append(row(i, start, start + 5.0, 0.9 if is_ad else 0.05))
        flags.append(is_ad)
    episode = make_episode(rows, flags, slots=slots)
    metrics = ev.interval_metrics([episode], 0.5, max_gap=1, min_seconds=8.0)
    assert metrics["gold"] == 2
    assert metrics["pods"] == 2  # 250-270 is 50 s away from the second gold slot
    assert metrics["hits"] == 1
    assert metrics["precision"] == pytest.approx(0.5)
    assert metrics["recall"] == pytest.approx(0.5)
    assert metrics["per_episode"][0]["unmatched_gold"] == [1]


# --- Cue metrics -----------------------------------------------------------------------


def test_cue_metrics_counts_and_threshold_is_inclusive():
    rows = [
        row(0, 0.0, 1.0, 0.5),
        row(1, 1.0, 2.0, 0.9),
        row(2, 2.0, 3.0, 0.2),
        row(3, 3.0, 4.0, 0.05),
        row(4, 4.0, 5.0, None),
    ]
    gold = [True, False, True, False, True]
    episode = make_episode(rows, gold)

    at_half = ev.cue_metrics([episode], 0.5)
    assert (at_half["tp"], at_half["fp"], at_half["fn"]) == (1, 1, 2)
    assert at_half["precision"] == pytest.approx(0.5)
    assert at_half["recall"] == pytest.approx(1 / 3)
    assert at_half["f1"] == pytest.approx(0.4)

    at_twenty = ev.cue_metrics([episode], 0.2)
    assert (at_twenty["tp"], at_twenty["fp"], at_twenty["fn"]) == (2, 1, 1)


def test_cue_metrics_can_count_transition_as_ad():
    rows = [
        row(0, 0.0, 1.0, 0.1, p_transition=0.8),
        row(1, 1.0, 2.0, 0.1, p_transition=0.1),
    ]
    gold = [True, False]
    episode = make_episode(rows, gold)
    assert ev.cue_metrics([episode], 0.5)["tp"] == 0
    with_transition = ev.cue_metrics([episode], 0.5, include_transition=True)
    assert (with_transition["tp"], with_transition["fp"]) == (1, 0)


def test_cue_metrics_gold_override_is_used():
    rows = [row(0, 0.0, 1.0, 0.9)]
    episode = make_episode(rows, [False])
    assert ev.cue_metrics([episode], 0.5)["precision"] == 0.0
    assert ev.cue_metrics([episode], 0.5, golds={"show__episode": [True]})["precision"] == 1.0


def test_empty_denominators_report_none():
    metrics = ev.cue_metrics([make_episode([], [])], 0.5)
    assert metrics["precision"] is None and metrics["recall"] is None and metrics["f1"] is None


# --- Baked candidates ------------------------------------------------------------------


def test_baked_credit_only_counts_predicted_content_on_a_baked_interval():
    rows = [
        row(0, 0.0, 10.0, 0.9),  # inside the baked interval, predicted ad, gold content
        row(1, 10.0, 20.0, 0.2),  # not predicted
        row(2, 100.0, 110.0, 0.9),  # predicted, but away from the baked interval
        row(3, 120.0, 130.0, 0.9),  # predicted and gold ad: never a false positive
    ]
    episode = make_episode(rows, [False, False, False, True], baked=[(0.0, 10.0, "ads_v3.json")])
    credit = ev.baked_credit(episode, 0.5)
    assert credit == {"false_positives": 2, "on_baked": 1}

    plain = make_episode(rows, [False, False, False, True])
    assert ev.baked_credit(plain, 0.5) == {"false_positives": 0, "on_baked": 0}


def test_baked_intervals_reads_corpus_then_legacy(tmp_path: Path, monkeypatch):
    legacy_v3 = tmp_path / "ads_v3.json"
    legacy_v2 = tmp_path / "ads_v2.json"
    payload = {
        "show__episode": {
            "baked_candidates": [
                {"take_intervals": {"0": {"start": 1.0, "end": 2.0}, "1": {"start": 3.0, "end": 4.0}}}
            ]
        }
    }
    legacy_v3.write_text(json.dumps(payload), encoding="utf-8")
    legacy_v2.write_text("{}", encoding="utf-8")
    monkeypatch.setattr(ev, "LEGACY_LABEL_FILES", (legacy_v3, legacy_v2))

    assert ev.baked_intervals({}, "show__episode", 0) == [(1.0, 2.0, "ads_v3.json")]
    assert ev.baked_intervals({}, "show__episode", 1) == [(3.0, 4.0, "ads_v3.json")]
    assert ev.baked_intervals({}, "show__episode", 2) == []
    assert ev.baked_intervals({}, "show__episode", 0, use_legacy=False) == []

    corpus_entry = {"baked_candidates": [{"start": 9.0, "end": 10.0}]}
    assert ev.baked_intervals(corpus_entry, "show__episode", 0) == [(9.0, 10.0, "corpus")]


def test_load_episode_skips_takes_without_slots(tmp_path: Path):
    transcripts = tmp_path / "transcripts" / "show__episode"
    transcripts.mkdir(parents=True)
    (transcripts / "take0.json").write_text(
        json.dumps({"segments": [{"start": 0.0, "end": 2.0, "text": " hello  world "}]}), encoding="utf-8"
    )
    entry = {"dynamic_slots": {"0": [{"start": 0.0, "end": 2.0}], "1": []}, "duration": 2.0}
    episode = ev.load_episode(tmp_path, "show__episode", entry, 0, use_legacy_baked=False)
    assert episode is not None
    assert episode.cues[0].text == "hello world"
    assert episode.gold == [True]
    assert ev.load_episode(tmp_path, "show__episode", entry, 1, use_legacy_baked=False) is None
    assert ev.load_episode(tmp_path, "missing", entry, 0, use_legacy_baked=False) is None


# --- Request path ----------------------------------------------------------------------


class FakeBatch:
    def __init__(self, results):
        self.per_sentence = results
        self.usage = {"input_tokens": 10, "output_tokens": 3}


def test_rows_from_batch_maps_by_position_and_marks_missing():
    cues = [cue(0, 0.0, 1.0, "a"), cue(1, 1.0, 2.0, "b")]
    batch = FakeBatch(
        [
            SentenceResult(1, "a", "advertisement", {"advertisement": 0.9, "content": 0.05, "transition": 0.05}, 0.9),
            SentenceResult(2, "b", None, {}, None, True),
        ]
    )
    rows = ev.rows_from_batch(batch, cues, 0, 2)
    assert [r["idx"] for r in rows] == [0, 1]
    assert rows[0]["p_ad"] == pytest.approx(0.9)
    assert rows[0]["missing"] is False
    assert rows[1]["missing"] is True and rows[1]["p_ad"] is None


def test_classify_window_never_sends_more_than_k(monkeypatch):
    cues = [cue(i, i, i + 1, f"cue {i}") for i in range(45)]
    seen: list[int] = []

    def fake(podcast, sentences, model=None, timeout=0):
        assert podcast == "Show"
        seen.append(len(sentences))
        return FakeBatch([SentenceResult(s["id"], s["text"], "content", {"content": 1.0}, 0.0) for s in sentences])

    monkeypatch.setattr(ev, "classify_batch", fake)
    rows, meta = ev.classify_window("Show", cues, 20, 40, "m", 5.0)
    assert seen == [20]
    assert meta["attempts"] == 1
    assert len(rows) == 20 and rows[0]["idx"] == 20


def test_classify_window_retries_then_succeeds(monkeypatch):
    cues = [cue(0, 0.0, 1.0)]
    calls = {"n": 0}

    def flaky(podcast, sentences, model=None, timeout=0):
        calls["n"] += 1
        if calls["n"] < 3:
            raise SystemOneError(429, "rate limited", "429")
        return FakeBatch([SentenceResult(1, "text", "content", {"content": 1.0}, 0.0)])

    slept: list[float] = []
    monkeypatch.setattr(ev, "classify_batch", flaky)
    rows, meta = ev.classify_window("Show", cues, 0, 1, "m", 5.0, sleep=slept.append)
    assert calls["n"] == 3
    assert meta["attempts"] == 3
    assert slept == [1.0, 2.0]
    assert rows[0]["label"] == "content" and rows[0]["p_ad"] is None


def test_classify_window_gives_up_after_three_retries(monkeypatch):
    cues = [cue(0, 0.0, 1.0)]
    calls = {"n": 0}

    def always_fails(podcast, sentences, model=None, timeout=0):
        calls["n"] += 1
        raise SystemOneError(500, "boom", "boom")

    slept: list[float] = []
    monkeypatch.setattr(ev, "classify_batch", always_fails)
    with pytest.raises(SystemOneError):
        ev.classify_window("Show", cues, 0, 1, "m", 5.0, sleep=slept.append)
    assert calls["n"] == 4  # one attempt plus three retries
    assert slept == [1.0, 2.0, 4.0]


def test_parse_thresholds_sorts_dedupes_and_validates():
    assert ev.parse_thresholds("0.5,0.3,0.5") == (0.3, 0.5)
    with pytest.raises(Exception):
        ev.parse_thresholds("")
    with pytest.raises(Exception):
        ev.parse_thresholds("1.5")


QUOTA_BODY = (
    '{"type":"error","error":{"type":"FreeUsageLimitError","message":"Free usage limit reached. '
    'Free models work in OpenCode; subscribe to OpenCode Go to keep going."}}'
)


def test_is_quota_error_recognises_the_measured_body():
    assert ev.is_quota_error(SystemOneError(429, QUOTA_BODY, "429")) is True
    assert ev.is_quota_error(SystemOneError(402, "", "402")) is True
    assert ev.is_quota_error(SystemOneError(429, '{"error":"slow down"}', "429")) is False
    assert ev.is_quota_error(SystemOneError(500, "boom", "500")) is False


def test_classify_window_does_not_retry_an_exhausted_quota(monkeypatch):
    cues = [cue(0, 0.0, 1.0)]
    calls = {"n": 0}

    def quota(podcast, sentences, model=None, timeout=0):
        calls["n"] += 1
        raise SystemOneError(429, QUOTA_BODY, "429")

    slept: list[float] = []
    monkeypatch.setattr(ev, "classify_batch", quota)
    with pytest.raises(SystemOneError):
        ev.classify_window("Show", cues, 0, 1, "m", 5.0, sleep=slept.append)
    assert calls["n"] == 1
    assert slept == []


# --- Diagnostics -----------------------------------------------------------------------


def test_slot_coverage_measures_the_union_span_across_split_pods():
    cues = [cue(i, i * 10.0, (i + 1) * 10.0) for i in range(20)]
    rows = [row(i, i * 10.0, (i + 1) * 10.0, 0.9 if i in (2, 3, 6, 7) else 0.0) for i in range(20)]
    # Gold slot 20-80 s; predictions cover 20-40 and 60-80, split by a gap of two cues.
    episode = make_episode(rows, [False] * 20, slots=[(20.0, 80.0)])
    diagnostic = ev.slot_coverage(episode, 0.5)[0]
    assert diagnostic["coverage"] == pytest.approx(40 / 60)
    assert diagnostic["start_delta"] == 0.0 and diagnostic["end_delta"] == 0.0
    assert diagnostic["pods"] == 2

    summary = ev.coverage_summary([episode], 0.5)
    assert summary["slots"] == 1
    assert summary["found_80"] == 0  # only 67% covered
    assert summary["split"] == 0


def test_slot_coverage_is_zero_when_no_pod_overlaps():
    cues = [cue(i, i * 10.0, (i + 1) * 10.0) for i in range(10)]
    rows = [row(i, i * 10.0, (i + 1) * 10.0, 0.0) for i in range(10)]
    episode = make_episode(rows, [False] * 10, slots=[(20.0, 80.0)])
    assert ev.slot_coverage(episode, 0.5) == [{"coverage": 0.0, "start_delta": None, "end_delta": None}]


def test_slot_table_hit_flag_agrees_with_interval_metrics():
    cues = [cue(i, i * 10.0, (i + 1) * 10.0) for i in range(12)]
    rows = [row(i, i * 10.0, (i + 1) * 10.0, 0.9 if 2 <= i <= 4 else 0.0) for i in range(12)]
    episode = make_episode(rows, [False] * 12, slots=[(20.0, 50.0), (300.0, 340.0)])
    table = ev.slot_table([episode], 0.5)
    assert [item["hit"] for item in table] == [True, False]
    assert ev.interval_metrics([episode], 0.5)["hits"] == 1


def test_cue_groups_counts_promo_markers_per_group():
    rows = [
        row(0, 0.0, 1.0, 0.9),  # TP with a marker
        row(1, 1.0, 2.0, 0.9),  # TP without one
        row(2, 2.0, 3.0, 0.8),  # FP with a marker
        row(3, 3.0, 4.0, 0.1),  # FN
        row(4, 4.0, 5.0, 0.1),  # TN
    ]
    rows[0]["text"] = "Visit acme.com and use code SAVE20."
    rows[1]["text"] = "Acme makes tools for developers."
    rows[2]["text"] = "Our sponsor is Acme."
    episode = make_episode(rows, [True, True, False, True, False])
    groups = ev.cue_groups([episode], 0.5)
    assert groups["tp"] == {"cues": 2, "markers": 1}
    assert groups["fp"] == {"cues": 1, "markers": 1}
    assert groups["fn"] == {"cues": 1, "markers": 0}
    assert groups["tn"] == {"cues": 1, "markers": 0}


def test_content_pods_returns_only_pods_outside_every_gold_slot():
    cues = [cue(i, i * 10.0, (i + 1) * 10.0, f"cue {i}") for i in range(30)]
    flags = [i in (1, 2, 3) or i in (10, 11, 12) for i in range(30)]
    rows = [row(i, i * 10.0, (i + 1) * 10.0, 0.9 if flags[i] else 0.0) for i in range(30)]
    episode = make_episode(rows, [False] * 30, slots=[(100.0, 130.0)])
    pods = ev.content_pods([episode], 0.5)
    assert len(pods) == 1
    assert pods[0]["start"] == 10.0 and pods[0]["end"] == 40.0
    assert pods[0]["mean_pad"] == pytest.approx(0.9)
    assert pods[0]["text"] == "cue 1"
