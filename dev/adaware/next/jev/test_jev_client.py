"""Offline tests for the System One client.

No network and no key are needed: ``urllib.request.urlopen`` is patched everywhere.
Run from ``dev/adaware``::

    uv run python -m pytest next/jev -q
"""

from __future__ import annotations

import io
import json
import urllib.error
from typing import Any, Sequence

import pytest

import jev_client as jev

TEST_KEY = "sk-test-key-do-not-leak-0123456789"

SENTENCES = [
    {"id": 1, "text": "Today we discuss the latest AI developments."},
    {"id": 2, "text": "This episode is sponsored by Acme."},
    {"id": 3, "text": "Acme makes tools for developers."},
    {"id": 4, "text": "Use code TECH20 for 20% off."},
    {"id": 5, "text": "Now, back to our discussion of AI."},
]


# --------------------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------------------


class FakeResponse:
    """Minimal stand-in for the object ``urlopen`` returns as a context manager."""

    def __init__(self, body: Any, status: int = 200) -> None:
        if isinstance(body, (dict, list)):
            body = json.dumps(body)
        self._body = body.encode("utf-8") if isinstance(body, str) else body
        self.status = status
        self.headers = {"Content-Type": "application/json"}

    def read(self) -> bytes:
        return self._body

    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(self, *exc_info: object) -> bool:
        return False


def http_error(status: int, body: Any, reason: str = "error") -> urllib.error.HTTPError:
    """Build an ``HTTPError`` carrying a body, the way urllib does."""
    if isinstance(body, (dict, list)):
        body = json.dumps(body)
    return urllib.error.HTTPError(
        jev.ENDPOINT,
        status,
        reason,
        {"Content-Type": "application/json"},
        io.BytesIO(body.encode("utf-8")),
    )


class Recorder:
    """Records the outgoing requests and replays a scripted sequence of results."""

    def __init__(self, effects: Sequence[Any]) -> None:
        self.effects = list(effects)
        self.requests: list[Any] = []
        self.timeouts: list[float | None] = []

    def __call__(self, request: Any, timeout: float | None = None) -> Any:
        self.requests.append(request)
        self.timeouts.append(timeout)
        if not self.effects:
            raise AssertionError("unexpected extra HTTP call")
        effect = self.effects.pop(0)
        if isinstance(effect, BaseException):
            raise effect
        return effect

    @property
    def bodies(self) -> list[dict]:
        return [json.loads(request.data.decode("utf-8")) for request in self.requests]


@pytest.fixture
def key(monkeypatch: pytest.MonkeyPatch) -> str:
    monkeypatch.setenv(jev.KEY_ENV, TEST_KEY)
    monkeypatch.delenv(jev.MODEL_ENV, raising=False)
    return TEST_KEY


def call(
    effects: Sequence[Any],
    *,
    sentences: Sequence[dict] = SENTENCES,
    model: str | None = None,
    timeout: float = 5.0,
) -> tuple[dict, Recorder]:
    """Run ``classify_sentences`` against scripted responses."""
    recorder = Recorder(effects)
    with pytest.MonkeyPatch.context() as patcher:
        patcher.setattr("urllib.request.urlopen", recorder)
        result = jev.classify_sentences("Technology Today", list(sentences), model=model, timeout=timeout)
    return result, recorder


# --------------------------------------------------------------------------------------
# Request shape
# --------------------------------------------------------------------------------------


def test_build_request_matches_the_documented_contract() -> None:
    request = jev.build_request(
        "Technology Today",
        [{"id": 1, "text": "Today we discuss the latest AI developments."}, {"id": 2, "text": "Sponsored by Acme."}],
    )
    assert request == {
        "model": "jev-1.13-free",
        "state": {
            "podcast": "Technology Today",
            "sentences": [
                {"id": 1, "text": "Today we discuss the latest AI developments."},
                {"id": 2, "text": "Sponsored by Acme."},
            ],
        },
        "questions": {
            "segment_type": {
                "type": "choice",
                "instructions": (
                    "Classify the supplied podcast material according to whether it is an "
                    "advertisement or editorial content."
                ),
                "criteria": {
                    "advertisement": "Paid sponsorship, promotional content, commercial offer or call to action.",
                    "content": "Normal editorial podcast discussion.",
                    "transition": "Introduction to or return from an advertising break.",
                },
            }
        },
    }


def test_build_request_is_pure_and_defaults_ids(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(jev.MODEL_ENV, raising=False)
    sentences = [{"text": "one"}, {"text": "two"}]
    request = jev.build_request("Show", sentences)
    assert [s["id"] for s in request["state"]["sentences"]] == [1, 2]
    assert sentences == [{"text": "one"}, {"text": "two"}], "caller input must not be mutated"


def test_build_request_model_precedence(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(jev.MODEL_ENV, raising=False)
    assert jev.build_request("S", SENTENCES[:1])["model"] == "jev-1.13-free"
    monkeypatch.setenv(jev.MODEL_ENV, "jev-9.9")
    assert jev.build_request("S", SENTENCES[:1])["model"] == "jev-9.9"
    assert jev.build_request("S", SENTENCES[:1], model="jev-1.13")["model"] == "jev-1.13"
    monkeypatch.setenv(jev.MODEL_ENV, "   ")
    assert jev.build_request("S", SENTENCES[:1])["model"] == "jev-1.13-free"


@pytest.mark.parametrize(
    ("podcast", "sentences", "match"),
    [
        ("", SENTENCES, "podcast"),
        ("Show", [], "empty"),
        ("Show", [{"id": 1}], "text"),
        ("Show", [{"id": 1, "text": "  "}], "text"),
        ("Show", [{"id": 1, "text": "a"}, {"id": 1, "text": "b"}], "duplicate"),
        ("Show", ["not a dict"], "dict"),
        ("Show", [{"id": True, "text": "a"}], "id"),
    ],
)
def test_build_request_rejects_bad_input(podcast: str, sentences: Sequence[Any], match: str) -> None:
    with pytest.raises(ValueError, match=match):
        jev.build_request(podcast, sentences)  # type: ignore[arg-type]


def test_outgoing_request_uses_bearer_token_and_json(key: str) -> None:
    payload = {"answers": {"segment_type": {"value": "content", "probability": 0.9}}}
    _, recorder = call([FakeResponse(payload)], timeout=7.5)

    assert len(recorder.requests) == 1
    request = recorder.requests[0]
    assert request.get_method() == "POST"
    assert request.full_url == jev.ENDPOINT
    headers = {name.lower(): value for name, value in request.header_items()}
    assert headers["authorization"] == f"Bearer {TEST_KEY}"
    assert headers["content-type"] == "application/json"
    assert recorder.timeouts == [7.5]
    assert recorder.bodies[0] == jev.build_request("Technology Today", SENTENCES)


def test_endpoint_is_a_single_constant() -> None:
    assert jev.ENDPOINT == "https://opencode.ai/inference/systemone/v1/systemone"


# --------------------------------------------------------------------------------------
# Response shapes and id normalisation
# --------------------------------------------------------------------------------------


def test_shape_single_answer_for_the_request(key: str) -> None:
    payload = {"answers": {"segment_type": {"value": "advertisement", "probability": 0.91}}}
    result, _ = call([FakeResponse(payload)])

    assert result["parsed"] is True
    assert result["scope"] == "request"
    assert result["per_sentence"] is False
    assert result["shape"] == "$.answers.segment_type"
    assert [row["label"] for row in result["sentences"]] == ["advertisement"] * 5
    assert all(row["probability"] == pytest.approx(0.91) for row in result["sentences"])
    assert result["summary"] == {"advertisement": 5, "content": 0, "transition": 0}


def test_shape_results_list_with_ids_and_confidences(key: str) -> None:
    payload = {
        "results": [
            {"sentence_id": 2, "label": "advertisement", "confidence": 0.97},
            {"sentence_id": 1, "label": "content", "confidence": 0.93},
            {"sentence_id": 5, "label": "content", "confidence": 0.88},
            {"sentence_id": 3, "label": "advertisement", "confidence": 0.81},
            {"sentence_id": 4, "label": "advertisement", "confidence": 0.76},
        ]
    }
    result, _ = call([FakeResponse(payload)])

    assert result["parsed"] is True
    assert result["per_sentence"] is True
    labels = {row["id"]: row["label"] for row in result["sentences"]}
    assert labels == {
        1: "content",
        2: "advertisement",
        3: "advertisement",
        4: "advertisement",
        5: "content",
    }
    assert result["sentences"][1]["probability"] == pytest.approx(0.97)


def test_shape_probability_map_per_sentence(key: str) -> None:
    payload = {
        "answers": [
            {"id": 1, "probabilities": {"advertisement": 0.02, "content": 0.95, "transition": 0.03}},
            {"id": 2, "probabilities": {"advertisement": 0.90, "content": 0.05, "transition": 0.05}},
            {"id": 3, "probabilities": {"advertisement": 0.80, "content": 0.15, "transition": 0.05}},
            {"id": 4, "probabilities": {"advertisement": 0.99, "content": 0.005, "transition": 0.005}},
            {"id": 5, "probabilities": {"advertisement": 0.01, "content": 0.09, "transition": 0.90}},
        ]
    }
    result, _ = call([FakeResponse(payload)])

    assert result["parsed"] is True
    assert result["per_sentence"] is True
    assert [row["label"] for row in result["sentences"]] == [
        "content",
        "advertisement",
        "advertisement",
        "advertisement",
        "transition",
    ]
    assert result["sentences"][0]["probability"] == pytest.approx(0.95)
    assert result["sentences"][4]["scores"]["transition"] == pytest.approx(0.90)


def test_shape_nested_response_with_scores(key: str) -> None:
    payload = {
        "response": {
            "segment_type": {
                "answer": "transition",
                "scores": {"advertisement": 0.1, "content": 0.2, "transition": 0.7},
            }
        }
    }
    result, _ = call([FakeResponse(payload)])

    assert result["parsed"] is True
    assert result["sentences"][0]["label"] == "transition"
    assert result["sentences"][0]["probability"] == pytest.approx(0.7)


def test_shape_answers_keyed_by_sentence_id(key: str) -> None:
    payload = {
        "answers": {
            "1": {"value": "content", "probability": 0.9},
            "2": {"value": "advertisement", "probability": 0.8},
        }
    }
    result, _ = call([FakeResponse(payload)], sentences=SENTENCES[:2])

    assert [row["label"] for row in result["sentences"]] == ["content", "advertisement"]
    assert result["per_sentence"] is True


def test_shape_scores_only_argmax(key: str) -> None:
    payload = {"answers": {"segment_type": {"advertisement": 0.2, "content": 0.75, "transition": 0.05}}}
    result, _ = call([FakeResponse(payload)])

    assert result["sentences"][0]["label"] == "content"
    assert result["sentences"][0]["probability"] == pytest.approx(0.75)


def test_shape_candidate_list_is_collapsed_to_one_answer(key: str) -> None:
    payload = {
        "answers": {
            "segment_type": [
                {"label": "content", "probability": 0.12},
                {"label": "advertisement", "probability": 0.81},
                {"label": "transition", "probability": 0.07},
            ]
        }
    }
    result, _ = call([FakeResponse(payload)])

    assert result["parsed"] is True
    assert result["scope"] == "request"
    assert result["sentences"][0]["label"] == "advertisement"
    assert result["sentences"][0]["probability"] == pytest.approx(0.81)
    assert result["sentences"][0]["scores"] == pytest.approx(
        {"advertisement": 0.81, "content": 0.12, "transition": 0.07}
    )


def test_ids_are_matched_as_strings_and_zero_based_ids_are_shifted(key: str) -> None:
    payload = {
        "results": [
            {"index": index, "value": "content", "probability": 0.5}
            for index in range(len(SENTENCES))
        ]
    }
    result, _ = call([FakeResponse(payload)])

    assert [row["id"] for row in result["sentences"]] == [1, 2, 3, 4, 5]
    assert result["per_sentence"] is True
    assert result["unmatched"] == []


def test_unmatched_ids_are_reported_not_dropped(key: str) -> None:
    payload = {
        "results": [
            {"id": 1, "label": "content", "probability": 0.9},
            {"id": 99, "label": "advertisement", "probability": 0.8},
        ]
    }
    result, _ = call([FakeResponse(payload)])

    assert result["sentences"][0]["label"] == "content"
    assert [atom["id"] for atom in result["unmatched"]] == [99]


def test_answers_that_match_no_sentence_are_reported_as_unparsed(key: str) -> None:
    payload = {"results": [{"id": index, "label": "content"} for index in range(101, 106)]}
    result, _ = call([FakeResponse(payload)])

    assert result["parsed"] is False
    assert result["error"] == "answers were parsed but none matched a sentence id"
    assert len(result["unmatched"]) == 5


def test_unknown_label_is_kept_but_flagged(key: str) -> None:
    payload = {"results": [{"id": 1, "label": "banana", "probability": 0.5}]}
    result, _ = call([FakeResponse(payload)])

    assert result["sentences"][0]["label"] == "banana"
    assert result["sentences"][0]["known_label"] is False


def test_probabilities_may_arrive_as_percent_or_string(key: str) -> None:
    payload = {
        "results": [
            {"id": 1, "label": "advertisement", "probability": 92},
            {"id": 2, "label": "content", "confidence": "0.87"},
        ]
    }
    result, _ = call([FakeResponse(payload)], sentences=SENTENCES[:2])

    assert result["sentences"][0]["probability"] == pytest.approx(0.92)
    assert result["sentences"][1]["probability"] == pytest.approx(0.87)


def test_labels_are_normalised(key: str) -> None:
    payload = {
        "results": [
            {"id": 1, "label": "Sponsored"},
            {"id": 2, "label": "EDITORIAL"},
            {"id": 3, "label": "ad break"},
        ]
    }
    result, _ = call([FakeResponse(payload)], sentences=SENTENCES[:3])

    assert result["sentences"][0]["label"] == "advertisement"
    assert result["sentences"][1]["label"] == "content"
    assert result["sentences"][2]["label"] == "ad break"  # unknown, kept verbatim


def test_unrecognised_shape_returns_raw_without_raising(key: str) -> None:
    payload = {"unexpected": {"nested": [1, 2, 3]}, "note": "new format"}
    result, _ = call([FakeResponse(payload)])

    assert result["parsed"] is False
    assert result["raw"] == payload
    assert result["raw_text"] == json.dumps(payload)
    assert result["error"] == "no answer-like object found in the response"
    assert all(row["label"] is None for row in result["sentences"])
    assert result["elapsed_ms"] >= 0.0


def test_non_json_body_is_kept_as_raw_text(key: str) -> None:
    result, _ = call([FakeResponse("<html>gateway error</html>")])

    assert result["parsed"] is False
    assert result["raw"] is None
    assert result["raw_text"] == "<html>gateway error</html>"
    assert "not JSON" in result["error"]


def test_echoed_question_spec_is_not_mistaken_for_an_answer(key: str) -> None:
    payload = {
        "questions": jev.build_question(),
        "answers": {"segment_type": {"value": "content", "probability": 0.6}},
    }
    result, _ = call([FakeResponse(payload)])

    assert result["parsed"] is True
    assert result["sentences"][0]["label"] == "content"
    assert len(result["unmatched"]) == 0


# --------------------------------------------------------------------------------------
# Errors
# --------------------------------------------------------------------------------------


def test_missing_key_raises_missing_api_key_without_calling_out(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(jev.KEY_ENV, raising=False)
    recorder = Recorder([])
    with pytest.MonkeyPatch.context() as patcher:
        patcher.setattr("urllib.request.urlopen", recorder)
        with pytest.raises(jev.MissingApiKey) as info:
            jev.classify_sentences("Technology Today", SENTENCES)

    message = str(info.value)
    assert "OPENCODE_API_KEY is not set" in message
    assert "export OPENCODE_API_KEY" in message
    assert info.value.status is None
    assert recorder.requests == []


def test_401_error_message_never_contains_the_key(key: str) -> None:
    body = {"error": f"invalid api key {TEST_KEY}", "authorization": f"Bearer {TEST_KEY}"}
    recorder = Recorder([http_error(401, body, reason="Unauthorized")])
    with pytest.MonkeyPatch.context() as patcher:
        patcher.setattr("urllib.request.urlopen", recorder)
        with pytest.raises(jev.MissingApiKey) as info:
            jev.classify_sentences("Technology Today", SENTENCES)

    exc = info.value
    assert exc.status == 401
    assert "OPENCODE_API_KEY was rejected" in str(exc)
    assert TEST_KEY not in str(exc)
    assert TEST_KEY not in exc.body
    assert "[redacted]" in exc.body
    assert len(recorder.requests) == 1


def test_403_is_also_a_key_problem(key: str) -> None:
    recorder = Recorder([http_error(403, {"error": "forbidden"})])
    with pytest.MonkeyPatch.context() as patcher:
        patcher.setattr("urllib.request.urlopen", recorder)
        with pytest.raises(jev.MissingApiKey) as info:
            jev.classify_sentences("Technology Today", SENTENCES)
    assert info.value.status == 403


def test_other_http_errors_are_system_one_errors(key: str) -> None:
    recorder = Recorder([http_error(500, {"error": "boom", "key": TEST_KEY})])
    with pytest.MonkeyPatch.context() as patcher:
        patcher.setattr("urllib.request.urlopen", recorder)
        with pytest.raises(jev.SystemOneError) as info:
            jev.classify_sentences("Technology Today", SENTENCES)

    exc = info.value
    assert not isinstance(exc, jev.MissingApiKey)
    assert exc.status == 500
    assert "HTTP 500" in str(exc)
    assert TEST_KEY not in exc.body


def test_network_failure_has_no_status(key: str) -> None:
    recorder = Recorder([urllib.error.URLError("name resolution failed")])
    with pytest.MonkeyPatch.context() as patcher:
        patcher.setattr("urllib.request.urlopen", recorder)
        with pytest.raises(jev.SystemOneError) as info:
            jev.classify_sentences("Technology Today", SENTENCES)

    assert info.value.status is None
    assert "could not reach" in str(info.value)


def test_timeout_is_reported_as_such(key: str) -> None:
    recorder = Recorder([TimeoutError("timed out")])
    with pytest.MonkeyPatch.context() as patcher:
        patcher.setattr("urllib.request.urlopen", recorder)
        with pytest.raises(jev.SystemOneError) as info:
            jev.classify_sentences("Technology Today", SENTENCES, timeout=0.01)

    assert info.value.status is None
    assert "timed out" in str(info.value)


@pytest.mark.parametrize(
    ("status", "body", "model", "expected"),
    [
        (400, {"error": "unknown model: jev-1.13-free"}, "jev-1.13-free", True),
        (404, {"error": "model 'jev-1.13-free' does not exist"}, "jev-1.13-free", True),
        (400, {"error": "unsupported model jev-1.13"}, "jev-1.13", True),
        (422, {"detail": "model is invalid"}, None, True),
        (400, {"error": "questions must not be empty"}, "jev-1.13-free", False),
        (500, {"error": "model jev-1.13-free exploded"}, "jev-1.13-free", False),
        (401, {"error": "model jev-1.13-free"}, "jev-1.13-free", False),
    ],
)
def test_unknown_model_detection(status: int, body: dict, model: str | None, expected: bool) -> None:
    exc = jev.SystemOneError("boom", status=status, body=json.dumps(body))
    assert jev.is_unknown_model_error(exc, model) is expected


# --------------------------------------------------------------------------------------
# Smoke CLI (still fully offline)
# --------------------------------------------------------------------------------------


def good_response() -> FakeResponse:
    return FakeResponse(
        {
            "results": [
                {"sentence_id": 1, "label": "content", "probability": 0.95},
                {"sentence_id": 2, "label": "advertisement", "probability": 0.93},
                {"sentence_id": 3, "label": "advertisement", "probability": 0.90},
                {"sentence_id": 4, "label": "advertisement", "probability": 0.98},
                {"sentence_id": 5, "label": "content", "probability": 0.85},
            ]
        }
    )


def run_smoke_with(
    effects: Sequence[Any],
    capsys: pytest.CaptureFixture[str],
    **kwargs: Any,
) -> tuple[int, Recorder]:
    recorder = Recorder(effects)
    with pytest.MonkeyPatch.context() as patcher:
        patcher.setattr("urllib.request.urlopen", recorder)
        code = jev.run_smoke(**kwargs)
    return code, recorder


def test_smoke_prints_a_table_and_uses_the_fallback_model(
    key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    effects = [
        http_error(400, {"error": "unknown model: jev-1.13-free"}, reason="Bad Request"),
        good_response(),
    ]
    code, recorder = run_smoke_with(effects, capsys)

    captured = capsys.readouterr()
    assert code == jev.EXIT_OK
    assert len(recorder.requests) == 2
    assert recorder.bodies[0]["model"] == "jev-1.13-free"
    assert recorder.bodies[1]["model"] == jev.FALLBACK_MODEL == "jev-1.13"
    assert "retrying with 'jev-1.13'" in captured.err
    # table: header plus one row per sentence, with the labels the fake returned
    assert "sentence" in captured.out and "probability" in captured.out
    for label in ("content", "advertisement"):
        assert label in captured.out
    assert "advertisement=3" in captured.out
    assert "timing" in captured.out


def test_smoke_missing_key_exits_2_without_any_request(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.delenv(jev.KEY_ENV, raising=False)
    code, recorder = run_smoke_with([], capsys)

    captured = capsys.readouterr()
    assert code == jev.EXIT_NO_KEY == 2
    assert "OPENCODE_API_KEY is not set" in captured.err
    assert "export OPENCODE_API_KEY" in captured.err
    assert recorder.requests == []


def test_smoke_rejected_key_exits_2_and_does_not_leak(
    key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    effects = [http_error(401, {"error": f"bad key {TEST_KEY}"}, reason="Unauthorized")]
    code, recorder = run_smoke_with(effects, capsys)

    captured = capsys.readouterr()
    assert code == jev.EXIT_NO_KEY
    assert "rejected" in captured.err
    assert TEST_KEY not in captured.err
    assert TEST_KEY not in captured.out
    assert len(recorder.requests) == 1


def test_smoke_unrecognised_shape_exits_4_and_dumps_raw(
    key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    payload = {"brand_new_format": {"score": [1, 2, 3]}}
    code, _ = run_smoke_with([FakeResponse(payload)], capsys)

    captured = capsys.readouterr()
    assert code == jev.EXIT_UNPARSED == 4
    assert "client-side parser gap" in captured.err
    assert "brand_new_format" in captured.err


def test_smoke_other_error_exits_1(key: str, capsys: pytest.CaptureFixture[str]) -> None:
    effects = [http_error(500, {"error": "boom"}, reason="Internal Server Error")]
    code, recorder = run_smoke_with(effects, capsys)

    captured = capsys.readouterr()
    assert code == jev.EXIT_ERROR == 1
    assert "HTTP 500" in captured.err
    assert len(recorder.requests) == 1


def test_smoke_400_without_model_wording_does_not_fall_back(
    key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    effects = [http_error(400, {"error": "questions must not be empty"}, reason="Bad Request")]
    code, recorder = run_smoke_with(effects, capsys)

    assert code == jev.EXIT_ERROR
    assert len(recorder.requests) == 1


def test_smoke_both_model_ids_rejected_exits_3(
    key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    effects = [
        http_error(404, {"error": "unknown model: jev-1.13-free"}),
        http_error(404, {"error": "unknown model: jev-1.13"}),
    ]
    code, recorder = run_smoke_with(effects, capsys)

    captured = capsys.readouterr()
    assert code == jev.EXIT_MODEL_REJECTED == 3
    assert len(recorder.requests) == 2
    assert "rejected both model ids" in captured.err


def test_smoke_does_not_retry_twice_when_fallback_is_already_requested(
    key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    effects = [http_error(404, {"error": "unknown model: jev-1.13"})]
    code, recorder = run_smoke_with(effects, capsys, model="jev-1.13")

    assert code == jev.EXIT_MODEL_REJECTED
    assert len(recorder.requests) == 1


def test_smoke_single_answer_warns_about_scope(
    key: str, capsys: pytest.CaptureFixture[str]
) -> None:
    payload = {"answers": {"segment_type": {"value": "advertisement", "probability": 0.9}}}
    code, _ = run_smoke_with([FakeResponse(payload)], capsys)

    captured = capsys.readouterr()
    assert code == jev.EXIT_OK
    assert "single answer for the whole request" in captured.out
    assert "NOT proven" in captured.out


def test_smoke_saves_raw_response(key: str, capsys: pytest.CaptureFixture[str], tmp_path: Any) -> None:
    target = tmp_path / "raw.json"
    code, _ = run_smoke_with([good_response()], capsys, save_raw=target)

    assert code == jev.EXIT_OK
    saved = json.loads(target.read_text(encoding="utf-8"))
    assert saved["results"][0]["label"] == "content"


def test_cli_parses_arguments_and_honours_the_environment_model(
    key: str, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(jev.MODEL_ENV, "jev-1.13")
    recorder = Recorder([http_error(404, {"error": "unknown model: jev-1.13"})])
    with pytest.MonkeyPatch.context() as patcher:
        patcher.setattr("urllib.request.urlopen", recorder)
        code = jev.main(["--timeout", "2.5", "--model", "jev-1.13"])

    captured = capsys.readouterr()
    assert code == jev.EXIT_MODEL_REJECTED
    assert "model    : jev-1.13" in captured.out
    assert recorder.timeouts == [2.5]


def test_cli_without_a_key_exits_2(monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.delenv(jev.KEY_ENV, raising=False)
    recorder = Recorder([])
    with pytest.MonkeyPatch.context() as patcher:
        patcher.setattr("urllib.request.urlopen", recorder)
        code = jev.main([])

    captured = capsys.readouterr()
    assert code == 2
    assert "OPENCODE_API_KEY is not set" in captured.err
    assert recorder.requests == []
