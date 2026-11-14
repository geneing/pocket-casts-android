import sys
from pathlib import Path

import json

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import detector
import diagnostics
import boundary_head


def samples_with_durations(durations, gaps=None):
    rows = []
    cursor = 0.0
    for index, duration in enumerate(durations):
        if gaps and index:
            cursor += gaps[index - 1]
        rows.append(detector.Sample("episode", "0", index, cursor, cursor + duration, "text", "text", "text", "context"))
        cursor += duration
    return rows


def test_semi_markov_emits_a_coherent_pod():
    rows = samples_with_durations([3.0] * 10)
    intervals = detector.decode_intervals(rows, list(range(len(rows))), [0.9] * len(rows), detector.DecodeParams(0.5, 2.0, 0.75, 5.0))
    assert len(intervals) == 1
    assert intervals[0]["start"] == pytest.approx(0.0)
    assert intervals[0]["end"] == pytest.approx(30.0)


def test_duration_prior_rejects_a_sentence_sized_blip():
    rows = samples_with_durations([3.0] * 8)
    probabilities = [0.05, 0.05, 0.95, 0.05, 0.05, 0.05, 0.05, 0.05]
    intervals = detector.decode_intervals(rows, list(range(len(rows))), probabilities, detector.DecodeParams(0.5, 2.0, 0.75, 5.0))
    assert intervals == []


def test_merge_keeps_a_contiguous_pod_together():
    rows = samples_with_durations([10.0, 10.0], gaps=[3.0])
    intervals = detector.decode_intervals(rows, [0, 1], [0.95, 0.95], detector.DecodeParams(0.5, 1.0, 0.0, 5.0))
    assert len(intervals) == 1
    assert intervals[0] == {"start": 0.0, "end": 23.0}


def test_zero_edge_weight_preserves_the_base_decoder():
    rows = samples_with_durations([3.0] * 10)
    indices = list(range(len(rows)))
    probs = [0.8] * len(rows)
    params = detector.DecodeParams(0.5, 2.0, 0.75, 5.0)
    baseline = detector.decode_intervals(rows, indices, probs, params)
    zero_edge = detector.decode_intervals(rows, indices, probs, params, [2.0] * len(rows), [-2.0] * len(rows), 0.0)
    assert zero_edge == baseline


def test_v2_source_metadata_separates_semantics_and_audio_alignment():
    assert detector.provenance_for({"provenance": "transcript_semantics"}, "dynamic_slots", True) == "semantic-only"
    assert detector.provenance_for({"provenance": "audio_difference"}, "dynamic_slots", True) == "audio-difference"


def test_audio_edge_evidence_does_not_change_ad_identity_source():
    row = {"provenance": "transcript_semantics", "boundary_basis": "localized_audio_match_consensus"}
    assert detector.provenance_for(row, "dynamic_slots", True) == "semantic-only"
    assert detector.boundary_support_for(row) == "localized-audio-supported"


def test_overlap_diagnostics_separate_edges_and_grouping():
    predictions = {"ep": {"0": [{"start": 101.0, "end": 115.0}, {"start": 110.0, "end": 120.0}, {"start": 199.0, "end": 221.0}]}}
    references = {"ep": {"0": [{"start": 100.0, "end": 120.0}, {"start": 200.0, "end": 220.0}]}}
    result = diagnostics.interval_stats(predictions, references)
    assert result["any_overlap"]["tp"] == 2
    assert result["iou_at_least_0_5"]["tp"] == 2
    assert result["strict_10_seconds"]["tp"] == 2
    assert result["gold_pods_overlapped_by_multiple_predictions"] == 1


def test_boundary_feature_layout_uses_two_context_embeddings():
    starts = __import__("numpy").array([0.0, 4.0, 8.0])
    ends = __import__("numpy").array([3.0, 7.0, 11.0])
    mids = (starts + ends) / 2
    probs = __import__("numpy").array([0.1, 0.8, 0.2])
    embeddings = __import__("numpy").eye(3, 384, dtype=__import__("numpy").float32)
    row = boundary_head._feature_row(starts, ends, mids, probs, embeddings, 1, 4.0)
    assert row.shape == (boundary_head.SCALAR_COUNT + 768,)
    assert row[0] == pytest.approx(0.1)
    assert row[4] == pytest.approx(0.8)


def test_edge_targets_ignore_nearby_alternatives_and_weight_uncertain_positive():
    table = {"start": {
        "features": np.zeros((3, boundary_head.SCALAR_COUNT + 768), dtype=np.float32),
        "keys": [("ep", "0", 0), ("ep", "0", 1), ("ep", "0", 2)],
        "times": np.asarray([10.0, 20.0, 30.0]),
    }}
    references = {"ep": {"0": [{"start": 15.0, "end": 18.0, "uncertainty_seconds": 10.0}]}}
    targets = boundary_head.build_targets(table, references, ["ep"], "start")
    assert targets["labels"].tolist() == [1, 0]
    assert targets["weights"].tolist() == pytest.approx([0.5, 1.0])
    assert targets["ignored"] == 1


def test_boundary_payload_serializes_nested_numpy_values():
    payload = {"outer": [{"array": np.asarray([np.float32(1.25), np.float32(2.5)]), "scalar": np.int64(3)}]}
    encoded = json.dumps(boundary_head.json_safe(payload))
    assert json.loads(encoded) == {"outer": [{"array": [1.25, 2.5], "scalar": 3}]}


def test_boundary_report_matches_written_payload_shape():
    strict = {
        "tp": 1, "fp": 2, "fn": 3, "precision": 1 / 3, "recall": 1 / 4,
        "per_episode": {"ep": {"tp": 1, "fp": 2, "fn": 3, "precision": 1 / 3, "recall": 1 / 4}},
        "frame": {"precision": 0.5, "recall": 0.6, "average_precision": 0.55},
        "overlap_diagnostics": {
            "any_overlap": {"recall": 0.8}, "iou_at_least_0_5": {"recall": 0.5},
            "overlap_matched_but_edge_missed": 2, "gold_without_any_overlap": 1,
            "gold_pods_overlapped_by_multiple_predictions": 0,
            "predictions_overlapping_multiple_gold_pods": 0,
        },
    }
    payload = {
        "protocol": {"complete_outer_folds": False, "label_sha256": "hash", "episodes": ["ep"], "evaluated_outer_episodes": ["ep"]},
        "baseline": strict,
        "boundary_head": {
            "interval": strict,
            "folds": [{
                "held_out_episode": "ep", "selected_lambda_edge": 0,
                "lambda_scores_on_outer_training_episodes": [{"lambda_edge": 0, "precision": 0.5, "recall": 0.4}],
                "baseline_lambda_zero_equal": True,
            }],
        },
        "compute": {"base_model_fits": 10, "score_cache_hits": 2, "wall_seconds": 1.25, "feature_width": 786},
    }
    report = boundary_head.report_text(payload)
    assert "incomplete feasibility run" in report
    assert "TF-IDF + boundary heads" in report
    assert "ep" in report

