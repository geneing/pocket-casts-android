"""Offline tests for the System One client.

No network and no key are needed: ``urllib.request.urlopen`` is patched everywhere.
Run from ``dev/adaware``::

    uv run python -m pytest next/jev -q
"""

from __future__ import annotations

import io
import json
import urllib.error

import pytest

import jev_client as jev
import smoke

TEST_KEY = "sk-test-key-do-not-leak-0123456789"

SENTENCES = [
    {"id": 1, "text": "Today we discuss the latest AI developments."},
    {"id": 2, "text": "This episode is sponsored by Acme."},
    {"id": 3, "text": "Acme makes tools for developers."},
    {"id": 4, "text": "Use code TECH20 for 20% off."},
    {"id": 5, "text": "Now, back to our discussion of AI."},
]

USAGE = {
    "input_tokens": 396,
    "output_tokens": 45,
    "cost": 0,
    "opencode": {"billing_source": "free", "list_cost": 0},
}

# The exact response shape measured from the live service.
REAL_RESPONSE = {
    "model": "jev-1.13-free",
    "answers": {
        "sentence_2": {
            "type": "choice",
            "choice": "advertisement",
            "confidence": 0.86,
            "probabilities": {"advertisement": 0.91, "content": 0.01, "transition": 0.08},
        }
    },
    "usage": USAGE,
}


class FakeResponse:
    """Minimal stand-in for the object ``urlopen`` returns as a context manager."""

    def __init__(self, body: object, status: int = 200) -> None:
        text = body if isinstance(body, str) else json.dumps(body)
        self._body = text.encode("utf-8")
        self.status = status

    def read(self) -> bytes:
        return self._body

    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(self, *exc_info: object) -> bool:
        return False


def http_error(status: int, body: object) -> urllib.error.HTTPError:
    text = body if isinstance(body, str) else json.dumps(body)
    return urllib.error.HTTPError(
        jev.ENDPOINT, status, "error", {}, io.BytesIO(text.encode("utf-8"))
    )


def response(labels: dict[int, str], model: str = "jev-1.13-free") -> dict:
    """Build a response with one answer per ``{sentence id: label}`` entry."""
    answers = {}
    for sentence_id, label in labels.items():
        probabilities = {"advertisement": 0.02, "content": 0.02, "transition": 0.02}
        probabilities[label] = 0.91
        answers[f"sentence_{sentence_id}"] = {
            "type": "choice",
            "choice": label,
            "confidence": 0.9,
            "probabilities": probabilities,
        }
    return {"model": model, "answers": answers, "usage": USAGE}


class Recorder:
    """Replays a scripted sequence of results and records the outgoing requests."""

    def __init__(self, *effects: object) -> None:
        self.effects = list(effects)
        self.calls: list[tuple[object, float | None]] = []

    def __call__(self, request: object, timeout: float | None = None) -> object:
        self.calls.append((request, timeout))
        effect = self.effects.pop(0)
        if isinstance(effect, BaseException):
            raise effect
        return effect


def header(request: object, name: str) -> str | None:
    return next(
        (value for key, value in request.header_items() if key.lower() == name.lower()), None
    )


@pytest.fixture
def recorder(monkeypatch):
    monkeypatch.setenv(jev.KEY_ENV, TEST_KEY)

    def install(*effects: object) -> Recorder:
        rec = Recorder(*effects)
        monkeypatch.setattr("urllib.request.urlopen", rec)
        return rec

    return install


# --------------------------------------------------------------------------------------
# Request contract
# --------------------------------------------------------------------------------------


def test_build_request_puts_every_sentence_in_state():
    sentences = [dict(sentence) for sentence in SENTENCES]
    request = jev.build_request("Technology Today", sentences)
    assert request["model"] == "jev-1.13-free"
    assert request["state"] == {"podcast": "Technology Today", "sentences": SENTENCES}
    assert sentences == SENTENCES  # the caller's list is not mutated


def test_build_request_asks_one_question_per_sentence():
    questions = jev.build_request("Show", SENTENCES)["questions"]
    assert set(questions) == {f"sentence_{sentence['id']}" for sentence in SENTENCES}
    for sentence in SENTENCES:
        question = questions[f"sentence_{sentence['id']}"]
        assert question["type"] == "choice"
        assert f"id {sentence['id']}" in question["instructions"]
        assert set(question["criteria"]) == set(jev.LABELS)


@pytest.mark.parametrize(
    "podcast,sentences",
    [
        ("", SENTENCES),
        ("Show", []),
        ("Show", [{"id": 1, "text": "  "}]),
        ("Show", [{"id": 1, "text": "a"}, {"id": 1, "text": "b"}]),
    ],
)
def test_build_request_rejects_bad_input(podcast, sentences):
    with pytest.raises(ValueError):
        jev.build_request(podcast, sentences)


def test_classify_batch_sends_the_documented_headers_and_body(recorder):
    rec = recorder(FakeResponse(response({1: "content"})))
    jev.classify_batch("Technology Today", [{"id": 1, "text": "Hi."}], timeout=7)

    (request, timeout), = rec.calls
    assert request.full_url == jev.ENDPOINT
    assert request.get_method() == "POST"
    assert header(request, "Authorization") == f"Bearer {TEST_KEY}"
    assert header(request, "Content-Type") == "application/json"
    assert header(request, "User-Agent") == jev.USER_AGENT
    assert "python-urllib" not in jev.USER_AGENT.lower()  # the default UA is banned
    assert timeout == 7
    assert json.loads(request.data) == jev.build_request("Technology Today", [{"id": 1, "text": "Hi."}])


# --------------------------------------------------------------------------------------
# Response contract
# --------------------------------------------------------------------------------------


def test_real_response_parses_into_one_result_per_sentence(recorder):
    recorder(FakeResponse(REAL_RESPONSE))
    result = jev.classify_batch("Technology Today", [{"id": 2, "text": "This episode is sponsored by Acme."}])

    (row,) = result.per_sentence
    assert (row.id, row.label, row.missing) == (2, "advertisement", False)
    assert row.probabilities == {"advertisement": 0.91, "content": 0.01, "transition": 0.08}
    assert row.ad_score == 0.91
    assert result.model == "jev-1.13-free"
    assert result.usage == USAGE
    assert result.raw == REAL_RESPONSE
    assert result.elapsed_ms >= 0


def test_each_sentence_keeps_its_own_label(recorder):
    labels = {1: "content", 2: "advertisement", 3: "advertisement", 4: "advertisement", 5: "transition"}
    recorder(FakeResponse(response(labels)))
    result = jev.classify_batch("Technology Today", SENTENCES)

    assert [row.label for row in result.per_sentence] == list(labels.values())
    assert [row.id for row in result.per_sentence] == list(labels)
    assert result.per_sentence[0].ad_score == 0.02
    assert result.per_sentence[1].ad_score == 0.91


def test_missing_answer_is_flagged_not_invented(recorder):
    recorder(FakeResponse(response({1: "content", 3: "advertisement"})))
    result = jev.classify_batch("Technology Today", SENTENCES[:3])

    assert [row.missing for row in result.per_sentence] == [False, True, False]
    row = result.per_sentence[1]
    assert row.label is None
    assert row.probabilities == {}
    assert row.ad_score is None


@pytest.mark.parametrize(
    "body",
    [
        {"model": "jev-1.13-free", "answers": []},
        "<html>not json</html>",
    ],
)
def test_unsupported_response_raises_with_the_raw_body(recorder, body):
    recorder(FakeResponse(body))
    text = body if isinstance(body, str) else json.dumps(body)
    with pytest.raises(jev.SystemOneError) as info:
        jev.classify_batch("Technology Today", SENTENCES[:1])
    assert info.value.body == text
    assert info.value.status == 200


def test_answer_without_probabilities_raises_with_the_raw_body(recorder):
    body = response({1: "content"})
    del body["answers"]["sentence_1"]["probabilities"]
    recorder(FakeResponse(body))
    with pytest.raises(jev.SystemOneError) as info:
        jev.classify_batch("Technology Today", SENTENCES[:1])
    assert info.value.body == json.dumps(body)


# --------------------------------------------------------------------------------------
# Errors
# --------------------------------------------------------------------------------------


def test_unset_key_raises_before_any_request(monkeypatch):
    monkeypatch.delenv(jev.KEY_ENV, raising=False)
    called: list[int] = []
    monkeypatch.setattr("urllib.request.urlopen", lambda *args, **kwargs: called.append(1))

    with pytest.raises(jev.MissingApiKey) as info:
        jev.classify_batch("Technology Today", SENTENCES[:1])

    assert called == []
    assert "secrets/opencode.key" in str(info.value)


def test_cloudflare_403_is_a_key_error(recorder):
    body = '{"error_code": 1010, "error_name": "browser_signature_banned"}'
    recorder(http_error(403, body))
    with pytest.raises(jev.MissingApiKey) as info:
        jev.classify_batch("Technology Today", SENTENCES[:1])
    assert info.value.status == 403
    assert "403" in str(info.value)


def test_401_body_is_scrubbed_of_the_key(recorder):
    recorder(http_error(401, {"error": f"invalid api key {TEST_KEY}"}))
    with pytest.raises(jev.MissingApiKey) as info:
        jev.classify_batch("Technology Today", SENTENCES[:1])
    assert TEST_KEY not in str(info.value)
    assert TEST_KEY not in info.value.body
    assert "[redacted]" in info.value.body


def test_server_error_carries_status_and_body(recorder):
    recorder(http_error(500, {"error": "boom"}))
    with pytest.raises(jev.SystemOneError) as info:
        jev.classify_batch("Technology Today", SENTENCES[:1])
    assert not isinstance(info.value, jev.MissingApiKey)
    assert info.value.status == 500
    assert "boom" in info.value.body


def test_transport_failure_is_a_system_one_error(recorder):
    recorder(urllib.error.URLError("name resolution failed"))
    with pytest.raises(jev.SystemOneError) as info:
        jev.classify_batch("Technology Today", SENTENCES[:1])
    assert info.value.status is None
    assert info.value.body == ""


# --------------------------------------------------------------------------------------
# Model fallback
# --------------------------------------------------------------------------------------


def test_unknown_model_falls_back_exactly_once(recorder):
    rejected = {"error": {"type": "server_error", "message": "Upstream request failed: Model is unavailable."}}
    rec = recorder(
        http_error(400, rejected),
        FakeResponse(response({1: "content"}, model=jev.FALLBACK_MODEL)),
    )
    result = jev.classify_batch("Technology Today", SENTENCES[:1], model="jev-does-not-exist")

    assert len(rec.calls) == 2
    assert json.loads(rec.calls[0][0].data)["model"] == "jev-does-not-exist"
    assert json.loads(rec.calls[1][0].data)["model"] == jev.FALLBACK_MODEL
    assert result.model == jev.FALLBACK_MODEL


def test_fallback_is_not_retried_again(recorder):
    rejected = {"error": {"type": "server_error", "message": "Upstream request failed: Model is unavailable."}}
    rec = recorder(http_error(400, rejected), http_error(400, rejected))
    with pytest.raises(jev.SystemOneError) as info:
        jev.classify_batch("Technology Today", SENTENCES[:1], model="jev-does-not-exist")
    assert len(rec.calls) == 2
    assert info.value.status == 400


def test_score_style_422_does_not_retry(recorder):
    rec = recorder(http_error(422, {"error": "Upstream request failed: Endpoint is unavailable."}))
    with pytest.raises(jev.SystemOneError) as info:
        jev.classify_batch("Technology Today", SENTENCES[:1])
    assert len(rec.calls) == 1
    assert info.value.status == 422


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------


def test_smoke_success_prints_labels_and_exits_zero(recorder, capsys):
    labels = {sentence["id"]: ("advertisement" if sentence["id"] in (2, 3, 4) else "content") for sentence in smoke.SENTENCES}
    recorder(FakeResponse(response(labels)))
    assert smoke.main([]) == 0
    out = capsys.readouterr().out
    assert "advertisement" in out
    assert "0.910" in out
    assert "latency:" in out


def test_smoke_missing_key_exits_two(monkeypatch, capsys):
    monkeypatch.delenv(jev.KEY_ENV, raising=False)
    assert smoke.main([]) == 2
    assert jev.KEY_ENV in capsys.readouterr().err


def test_smoke_missing_answer_exits_four(recorder, capsys):
    recorder(FakeResponse(response({1: "content", 2: "advertisement"})))
    assert smoke.main([]) == 4
    assert "MISSING" in capsys.readouterr().out


def test_smoke_request_failure_exits_one(recorder, capsys):
    recorder(http_error(500, {"error": "boom"}))
    assert smoke.main([]) == 1
    assert "HTTP 500" in capsys.readouterr().err
