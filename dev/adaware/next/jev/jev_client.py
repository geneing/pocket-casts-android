"""Client for the OpenCode "System One" choice classifier.

The contract in one place. One POST asks one multiple-choice question per sentence::

    POST ENDPOINT
    Authorization: Bearer $OPENCODE_API_KEY
    Content-Type: application/json
    User-Agent: USER_AGENT        # mandatory

    {"model": "jev-1.13-free",
     "state": {"podcast": "<show>", "sentences": [{"id": 7, "text": "..."}]},
     "questions": {"sentence_7": {"type": "choice", "instructions": "...",
                                  "criteria": {"advertisement": "...", "content": "...",
                                               "transition": "..."}}}}

The response is::

    {"model": "jev-1.13-free",
     "answers": {"sentence_7": {"type": "choice", "choice": "advertisement",
                                "confidence": 0.86,
                                "probabilities": {"advertisement": 0.91, "content": 0.01,
                                                  "transition": 0.08}}},
     "usage": {...}}

``probabilities`` is the signal; ``confidence`` is informational and close to the largest
probability. Any other shape raises :class:`SystemOneError` with the raw body attached.

Cloudflare rejects urllib's default ``Python-urllib/3.x`` with ``403 error_code 1010
browser_signature_banned``; :data:`USER_AGENT` is one measured working.

Granularity: one answer per *question*, not per sentence. :func:`classify_batch` asks one
question per sentence, keyed ``sentence_<id>``, so 40 sentences give 40 answers (measured:
40/40, ~380 ms, ~6.2k input tokens). Sentences are sent with their neighbours because
context changes the answer: "Acme makes tools for developers." scores content 0.90 alone
but advertisement 0.99 between two ad sentences.

``"type": "score"`` is not supported (HTTP 422) and is not offered.
"""

from __future__ import annotations

import json
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Sequence

OPENCODE_ENDPOINT = "https://opencode.ai/inference/systemone/v1/systemone"
#: OpenRouter exposes the same System One contract; the app uses this one.
OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/systemone"
ENDPOINT = OPENCODE_ENDPOINT
ENDPOINT_ENV = "JEV_ENDPOINT"
DEFAULT_MODEL = "jev-1.13-free"
FALLBACK_MODEL = "jev-1.13"
KEY_ENV = "OPENCODE_API_KEY"

#: Any non-Python-urllib UA gets past Cloudflare; this one is measured working.
USER_AGENT = "pocket-casts-adaware/0.1"

LABELS = ("advertisement", "content", "transition")

#: Longest error body kept in an exception.
MAX_BODY = 2000

MISSING_KEY_MESSAGE = (
    f"{KEY_ENV} is not set; no request was made.\n"
    "The repo key lives in secrets/opencode.key (gitignored). From dev/adaware:\n"
    "  OPENCODE_API_KEY=$(tr -d '\\r\\n' < ../../secrets/opencode.key) uv run python next/jev/smoke.py\n"
)

INSTRUCTIONS = (
    "Classify only the sentence with id {id}. Decide whether it belongs to an advertising break, "
    "not whether it sounds like an ad: the break starts at its spoken hand-over and ends when the "
    "show resumes, and every sentence between those two points is part of it. Use the other "
    "sentences only to locate those two edges. Ignore every other sentence: they are separate "
    "questions."
)

CRITERIA = {
    "advertisement": (
        "The paid read itself: naming or addressing the sponsor, its offer, claims, price, "
        "instructions, call to action, or the closing terms and disclaimer. A sentence at the "
        "seam that mixes the ad with the show belongs here."
    ),
    "content": (
        "The show's own editorial discussion, outside every advertising break. A brand mentioned "
        "in passing with no offer or call to action is content."
    ),
    "transition": (
        "The spoken hand-over that opens or closes an advertising break and sells nothing: "
        "announcing a break, naming the sponsor as a hand-over, or returning the listener to the "
        "show. It is part of the break."
    ),
}

#: Bumped whenever :data:`INSTRUCTIONS` or :data:`CRITERIA` changes. Cached predictions record it,
#: so a prompt change invalidates them instead of being scored against old answers. This tracks the
#: wording the corpus was scored with, not the app's analysis identity (which also pins the pod
#: rule in ``JevPrompt.VERSION``).
PROMPT_VERSION = 3


class SystemOneError(Exception):
    """A failed call or unsupported response; ``body`` is the redacted response body."""

    def __init__(self, status: int | None, body: str, message: str = "") -> None:
        super().__init__(message or f"System One request failed (HTTP {status})")
        self.status = status
        self.body = body


class MissingApiKey(SystemOneError):
    """``OPENCODE_API_KEY`` is unset or rejected (401/403); the message never has the key."""


@dataclass
class SentenceResult:
    id: int
    text: str
    label: str | None
    probabilities: dict[str, float]
    ad_score: float | None
    missing: bool = False


@dataclass
class BatchResult:
    per_sentence: list[SentenceResult]
    usage: dict[str, Any]
    model: str
    elapsed_ms: float
    raw: dict[str, Any]


# --- Request -------------------------------------------------------------------------


def build_request(podcast: str, sentences: Sequence[dict], model: str | None = None) -> dict:
    """Return the request body. Pure: no I/O, and the caller's list is not mutated."""
    if not isinstance(podcast, str) or not podcast.strip():
        raise ValueError("podcast must be a non-empty string")
    normalised = _normalise_sentences(sentences)
    return {
        "model": model or DEFAULT_MODEL,
        "state": {"podcast": podcast, "sentences": normalised},
        "questions": {
            f"sentence_{sentence['id']}": {
                "type": "choice",
                "instructions": INSTRUCTIONS.format(id=sentence["id"]),
                "criteria": dict(CRITERIA),
            }
            for sentence in normalised
        },
    }


def _normalise_sentences(sentences: Sequence[dict]) -> list[dict]:
    if isinstance(sentences, (str, bytes)) or not isinstance(sentences, Sequence) or not sentences:
        raise ValueError("sentences must be a non-empty sequence of {'id': int, 'text': str}")
    result: list[dict] = []
    seen: set[int] = set()
    for position, sentence in enumerate(sentences, start=1):
        if not isinstance(sentence, dict):
            raise ValueError(f"sentence {position} is not a dict")
        text = sentence.get("text")
        if not isinstance(text, str) or not text.strip():
            raise ValueError(f"sentence {position} has no non-empty 'text'")
        sentence_id = sentence.get("id")
        if isinstance(sentence_id, bool) or not isinstance(sentence_id, int) or sentence_id in seen:
            raise ValueError(f"sentence {position} needs a unique integer 'id'")
        seen.add(sentence_id)
        result.append({"id": sentence_id, "text": text})
    return result


def resolve_endpoint(endpoint: str | None = None) -> str:
    """Endpoint choice: argument, then ``JEV_ENDPOINT``, then the opencode default."""
    if isinstance(endpoint, str) and endpoint.strip():
        return endpoint.strip()
    return (os.environ.get(ENDPOINT_ENV) or "").strip() or ENDPOINT


def is_openrouter(endpoint: str) -> bool:
    return "openrouter.ai" in endpoint


# --- Transport -----------------------------------------------------------------------


def _api_key(endpoint: str) -> str:
    """Read the key for this endpoint; OpenRouter and opencode use different variables."""
    names = ["JEV_API_KEY"]
    names.append("OPENROUTER_API_KEY" if is_openrouter(endpoint) else KEY_ENV)
    names.append(KEY_ENV)
    for name in names:
        key = os.environ.get(name, "").strip()
        if key:
            return key
    raise MissingApiKey(None, "", MISSING_KEY_MESSAGE)


def _scrub(text: str, secret: str) -> str:
    """Remove the bearer token from a string so it can be surfaced safely."""
    if len(secret) >= 4:
        text = text.replace(secret, "[redacted]")
    return re.sub(r"(?i)(bearer\s+)\S+", r"\1[redacted]", text)


def _read_body(exc: urllib.error.HTTPError, api_key: str) -> str:
    try:
        data = exc.read()
    except Exception:  # pragma: no cover - a broken error stream
        return ""
    return _scrub(data.decode("utf-8", "replace"), api_key)[:MAX_BODY]


def _rejected_message(status: int, body: str) -> str:
    reason = {401: "401 Unauthorized", 403: "403 Forbidden"}.get(status, f"HTTP {status}")
    return (
        f"{KEY_ENV} was rejected by OpenCode ({reason}); the key is set but not accepted.\n"
        "Check the key in secrets/opencode.key (gitignored) and retry.\n"
        f"Server said: {body or '(empty body)'}"
    )


def _post(payload: dict, api_key: str, timeout: float, endpoint: str = ENDPOINT) -> tuple[int, str]:
    """POST the body, returning ``(status, text)`` or raising a client error."""
    request = urllib.request.Request(
        endpoint,
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": USER_AGENT,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = int(getattr(response, "status", 200) or 200)
            return status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        body = _read_body(exc, api_key)
        if exc.code in (401, 403):
            raise MissingApiKey(exc.code, body, _rejected_message(exc.code, body)) from None
        raise SystemOneError(exc.code, body, f"System One returned HTTP {exc.code}") from None
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        reason = getattr(exc, "reason", exc)
        raise SystemOneError(None, "", f"could not reach {endpoint}: {reason}") from None


def _model_rejected(exc: SystemOneError) -> bool:
    """True when the body looks like the model id itself was rejected.

    Measured rejection body: ``{"error":{"type":"server_error","message":
    "Upstream request failed: Model is unavailable."}}`` with HTTP 400.
    """
    if exc.status not in (400, 404, 422):
        return False
    text = exc.body.lower()
    return "model" in text and any(
        marker in text
        for marker in ("unavailable", "unknown", "not found", "unsupported", "invalid", "no such")
    )


# --- Response ------------------------------------------------------------------------


def _parse(text: str, request: dict, elapsed_ms: float) -> BatchResult:
    try:
        raw = json.loads(text)
    except json.JSONDecodeError as exc:
        raise SystemOneError(200, text, f"response was not JSON: {exc.msg}") from None
    if not isinstance(raw, dict) or not isinstance(raw.get("answers"), dict):
        raise SystemOneError(200, text, "response has no 'answers' object")

    rows: list[SentenceResult] = []
    for sentence in request["state"]["sentences"]:
        key = f"sentence_{sentence['id']}"
        answer = raw["answers"].get(key)
        if answer is None:
            rows.append(SentenceResult(sentence["id"], sentence["text"], None, {}, None, True))
            continue
        probabilities = answer.get("probabilities") if isinstance(answer, dict) else None
        choice = answer.get("choice") if isinstance(answer, dict) else None
        if not isinstance(choice, str) or not isinstance(probabilities, dict):
            raise SystemOneError(200, text, f"answer {key!r} is not a choice with probabilities")
        try:
            scores = {str(label): float(value) for label, value in probabilities.items()}
        except (TypeError, ValueError):
            raise SystemOneError(200, text, f"answer {key!r} has a non-numeric probability")
        rows.append(SentenceResult(sentence["id"], sentence["text"], choice, scores,
                                   scores.get("advertisement")))

    usage = raw.get("usage")
    return BatchResult(rows, usage if isinstance(usage, dict) else {},
                       str(raw.get("model") or request["model"]), round(elapsed_ms, 1), raw)


def classify_batch(
    podcast: str,
    sentences: Sequence[dict],
    model: str | None = None,
    timeout: float = 120.0,
    endpoint: str | None = None,
) -> BatchResult:
    """Classify every sentence in one request, one question per sentence.

    Retries once with :data:`FALLBACK_MODEL` only when the model id itself is rejected;
    every other failure is raised immediately. ``endpoint`` overrides ``JEV_ENDPOINT``.
    """
    target = resolve_endpoint(endpoint)
    request = build_request(podcast, sentences, model=model)
    api_key = _api_key(target)
    started = time.perf_counter()
    try:
        _status, text = _post(request, api_key, timeout, target)
    except SystemOneError as exc:
        if request["model"] == FALLBACK_MODEL or not _model_rejected(exc):
            raise
        request = build_request(podcast, sentences, model=FALLBACK_MODEL)
        _status, text = _post(request, api_key, timeout, target)
    return _parse(text, request, (time.perf_counter() - started) * 1000.0)
