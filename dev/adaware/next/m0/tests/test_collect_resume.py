"""Collection resume rules: finished work is not repeated, transient errors are retried."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from collect import should_process  # noqa: E402


def test_missing_entry_runs():
    assert should_process(None) is True


def test_completed_entries_are_not_repeated():
    assert should_process({"status": "ok", "slots": 4}) is False


def test_intentional_no_label_entries_are_not_repeated():
    assert should_process({"status": "no-labels"}) is False


def test_errors_are_retried():
    assert should_process({"status": "error", "attempts": 1}, max_attempts=3) is True
    assert should_process({"status": "error", "attempts": 2}, max_attempts=3) is True


def test_errors_stop_at_the_attempt_limit():
    assert should_process({"status": "error", "attempts": 3}, max_attempts=3) is False
    assert should_process({"status": "error", "attempts": 9}, max_attempts=3) is False


def test_legacy_error_without_attempt_count_is_retried():
    assert should_process({"status": "error"}, max_attempts=3) is True
