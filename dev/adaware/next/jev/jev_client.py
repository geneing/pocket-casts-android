"""Client for the OpenCode "System One" inference API.

One call sends a podcast name, a list of sentences and one multiple-choice question
("is this advertisement, content or transition?"). The vendor documents the request but
not the response in detail, so this module accepts several plausible answer layouts,
normalises them to per-sentence labels, and always returns the raw JSON untouched.

The endpoint is **stateless per request**: nothing from an earlier call is remembered, so
each request has to carry every sentence that the model needs for context.

Standard library only (``urllib.request``, ``json``, ``os``, ``time``, plus ``re`` to
redact the key from error bodies). Python 3.12.

Usage, from ``dev/adaware``::

    export OPENCODE_API_KEY=...           # PowerShell: $env:OPENCODE_API_KEY = "..."
    uv run python next/jev/smoke.py

    from jev_client import classify_sentences
    result = classify_sentences("Technology Today", [{"id": 1, "text": "..."}])
    for row in result["sentences"]:
        print(row["id"], row["label"], row["probability"])
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from typing import Any, Sequence, TextIO

__all__ = [
    "ENDPOINT",
    "DEFAULT_MODEL",
    "FALLBACK_MODEL",
    "KEY_ENV",
    "MODEL_ENV",
    "QUESTION_ID",
    "LABELS",
    "SystemOneError",
    "MissingApiKey",
    "build_request",
    "build_question",
    "classify_sentences",
    "is_unknown_model_error",
    "resolve_model",
    "run_smoke",
    "main",
]

ENDPOINT = "https://opencode.ai/inference/systemone/v1/systemone"
DEFAULT_MODEL = "jev-1.13-free"
FALLBACK_MODEL = "jev-1.13"
KEY_ENV = "OPENCODE_API_KEY"
MODEL_ENV = "JEV_MODEL"
QUESTION_ID = "segment_type"
LABELS = ("advertisement", "content", "transition")
DEFAULT_TIMEOUT = 60.0

#: Longest error body kept in memory / printed in a diagnostic.
MAX_BODY_CHARS = 4000

#: Exit codes of :func:`run_smoke` / :func:`main`.
EXIT_OK = 0
EXIT_ERROR = 1
EXIT_NO_KEY = 2
EXIT_MODEL_REJECTED = 3
EXIT_UNPARSED = 4

# The smoke case: one clean ad read inside otherwise editorial material, so a correct
# answer is unambiguous (1 content, 2-4 advertisement, 5 content).
SAMPLE_PODCAST = "Technology Today"
SAMPLE_SENTENCES: list[dict] = [
    {"id": 1, "text": "Today we discuss the latest AI developments."},
    {"id": 2, "text": "This episode is sponsored by Acme."},
    {"id": 3, "text": "Acme makes tools for developers."},
    {"id": 4, "text": "Use code TECH20 for 20% off."},
    {"id": 5, "text": "Now, back to our discussion of AI."},
]


class SystemOneError(Exception):
    """A failed System One call.

    ``status`` is the HTTP status, or ``None`` when the request never reached the server
    (DNS, connection, timeout). ``body`` is the (redacted, truncated) response body, or an
    empty string when there was none.
    """

    def __init__(self, message: str, status: int | None = None, body: str | None = None) -> None:
        super().__init__(message)
        self.status = status
        self.body = body or ""


class MissingApiKey(SystemOneError):
    """No usable ``OPENCODE_API_KEY``: unset in the environment, or rejected with 401/403.

    The message explains how to set a key and never contains the key itself.
    """


# --------------------------------------------------------------------------------------
# Request building
# --------------------------------------------------------------------------------------


def build_question() -> dict:
    """Return the single multiple-choice question sent with every request."""
    return {
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


def resolve_model(model: str | None = None) -> str:
    """Pick the model id: argument, then ``JEV_MODEL``, then :data:`DEFAULT_MODEL`."""
    if isinstance(model, str) and model.strip():
        return model.strip()
    from_env = os.environ.get(MODEL_ENV, "").strip()
    return from_env or DEFAULT_MODEL


def build_request(
    podcast: str,
    sentences: Sequence[dict],
    model: str | None = None,
) -> dict:
    """Build the request body. Pure: no I/O, and the caller's list is never mutated.

    ``sentences`` is a sequence of ``{"id": <int|str>, "text": str}``. A missing ``id``
    defaults to the 1-based position.
    """
    if not isinstance(podcast, str) or not podcast.strip():
        raise ValueError("podcast must be a non-empty string")
    return {
        "model": resolve_model(model),
        "state": {"podcast": podcast, "sentences": _normalise_sentences(sentences)},
        "questions": {QUESTION_ID: build_question()},
    }


def _normalise_sentences(sentences: Sequence[dict]) -> list[dict]:
    """Validate and copy the sentences, defaulting and de-duplicating ids."""
    if isinstance(sentences, (str, bytes)) or not isinstance(sentences, Sequence):
        raise ValueError("sentences must be a sequence of {'id', 'text'} dicts")
    if not sentences:
        raise ValueError("sentences must not be empty")

    normalised: list[dict] = []
    seen: set[str] = set()
    for index, sentence in enumerate(sentences, start=1):
        if not isinstance(sentence, dict):
            raise ValueError(f"sentence {index} is not a dict")
        text = sentence.get("text")
        if not isinstance(text, str) or not text.strip():
            raise ValueError(f"sentence {index} has no non-empty 'text'")
        raw_id = sentence.get("id", index)
        if isinstance(raw_id, bool) or not isinstance(raw_id, (int, str)):
            raise ValueError(f"sentence {index} has an unsupported id {raw_id!r} (use int or str)")
        key = str(raw_id)
        if key in seen:
            raise ValueError(f"duplicate sentence id {raw_id!r}")
        seen.add(key)
        normalised.append({"id": raw_id, "text": text})
    return normalised


# --------------------------------------------------------------------------------------
# Transport
# --------------------------------------------------------------------------------------


def _missing_key_message() -> str:
    return (
        f"{KEY_ENV} is not set.\n\n"
        "System One calls need a bearer token in the environment. Set one and re-run:\n\n"
        '  WSL / bash : export OPENCODE_API_KEY="<your key>"\n'
        '  PowerShell : $env:OPENCODE_API_KEY = "<your key>"\n\n'
        "The key is read from the environment only; it is never written to disk, logged\n"
        "or included in an exception message. No request was made."
    )


def _rejected_key_message(status: int, detail: str) -> str:
    reason = {401: "401 Unauthorized", 403: "403 Forbidden"}.get(status, f"HTTP {status}")
    return (
        f"{KEY_ENV} was rejected by OpenCode ({reason}).\n\n"
        "The key is set but not accepted: it may be invalid, expired, or issued for a\n"
        "different service. Set a valid key and retry:\n\n"
        '  WSL / bash : export OPENCODE_API_KEY="<your key>"\n'
        '  PowerShell : $env:OPENCODE_API_KEY = "<your key>"\n\n'
        f"Server said: {detail or '(empty body)'}"
    )


def _require_api_key() -> str:
    """Return the key from the environment or raise :class:`MissingApiKey`."""
    key = os.environ.get(KEY_ENV, "").strip()
    if not key:
        raise MissingApiKey(_missing_key_message())
    return key


def _scrub(text: str, secret: str) -> str:
    """Remove the bearer token from a string so it can be logged safely."""
    redacted = text
    if secret and len(secret) >= 4:
        redacted = redacted.replace(secret, "[redacted]")
    return re.sub(r"(?i)(bearer\s+)[^\s\"',}]+", r"\1[redacted]", redacted)


def _read_error_body(exc: urllib.error.HTTPError) -> str:
    try:
        data = exc.read()
    except Exception:  # pragma: no cover - defensive, a broken error stream
        return ""
    text = data.decode("utf-8", "replace") if isinstance(data, bytes) else str(data)
    return text[:MAX_BODY_CHARS]


def _post_json(payload: dict, api_key: str, timeout: float) -> tuple[str, int]:
    """POST the request and return ``(body_text, http_status)``.

    Raises :class:`MissingApiKey` on 401/403 and :class:`SystemOneError` on any other
    failure. Response bodies are redacted before they reach an exception.
    """
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(ENDPOINT, data=body, method="POST")
    request.add_header("Authorization", f"Bearer {api_key}")
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/json")

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode("utf-8", "replace")
            status = int(getattr(response, "status", 200) or 200)
            return text, status
    except urllib.error.HTTPError as exc:
        detail = _scrub(_read_error_body(exc), api_key)
        if exc.code in (401, 403):
            raise MissingApiKey(
                _rejected_key_message(exc.code, detail), status=exc.code, body=detail
            ) from None
        message = f"System One returned HTTP {exc.code}"
        if exc.reason:
            message = f"{message} {exc.reason}"
        raise SystemOneError(message, status=exc.code, body=detail) from None
    except urllib.error.URLError as exc:
        reason = getattr(exc, "reason", exc)
        raise SystemOneError(f"could not reach {ENDPOINT}: {reason}") from None
    except (TimeoutError, OSError) as exc:
        raise SystemOneError(f"request to {ENDPOINT} failed: {exc}") from None


def is_unknown_model_error(exc: BaseException, model: str | None = None) -> bool:
    """True when ``exc`` looks like a 400/404/422 rejecting the model id.

    Used by callers to retry the alternate id (:data:`FALLBACK_MODEL`) once.
    """
    if not isinstance(exc, SystemOneError) or exc.status not in (400, 404, 422):
        return False
    text = f"{exc}\n{exc.body}".lower()
    if model and model.strip() and model.strip().lower() in text:
        return True
    return "model" in text and any(
        marker in text
        for marker in (
            "not found",
            "unknown",
            "invalid",
            "unsupported",
            "not supported",
            "does not exist",
            "no such",
        )
    )


# --------------------------------------------------------------------------------------
# Response parsing
# --------------------------------------------------------------------------------------

#: Keys that may hold the chosen label.
_LABEL_KEYS = ("value", "label", "answer", "choice", "classification", "class", "prediction", "verdict")

#: Keys that may hold a single probability for the chosen label.
_PROB_KEYS = ("probability", "prob", "confidence", "score", "likelihood", "p")

#: Keys that may hold an id for the sentence an answer belongs to.
_ID_KEYS = (
    "id",
    "sentence_id",
    "sentenceId",
    "statement_id",
    "statementId",
    "sentence_index",
    "sentenceIndex",
    "index",
    "position",
    "sentence",
)

#: Keys that may hold a label -> probability mapping.
_SCORE_CONTAINERS = (
    "probabilities",
    "probability",
    "scores",
    "score",
    "distribution",
    "likelihoods",
    "likelihood",
    "confidences",
    "confidence",
)

#: Subtrees that are never answers (an echoed request or question spec).
_SKIP_SUBTREES = frozenset(
    {"questions", "criteria", "instructions", "state", "request", "echo", "input", "prompt", "usage", "meta",
     "metadata", "model", "debug"}
)

_LABEL_SYNONYMS = {
    "advertisement": "advertisement",
    "ad": "advertisement",
    "ads": "advertisement",
    "advert": "advertisement",
    "adverts": "advertisement",
    "advertising": "advertisement",
    "advertorial": "advertisement",
    "sponsor": "advertisement",
    "sponsored": "advertisement",
    "sponsorship": "advertisement",
    "promo": "advertisement",
    "promotion": "advertisement",
    "promotional": "advertisement",
    "commercial": "advertisement",
    "content": "content",
    "editorial": "content",
    "discussion": "content",
    "organic": "content",
    "nonad": "content",
    "transition": "transition",
    "intro": "transition",
    "outro": "transition",
    "return": "transition",
    "break": "transition",
    "handoff": "transition",
}


def _normalise_label(value: str) -> str:
    """Map a free-form label to one of :data:`LABELS`, or return a cleaned unknown label."""
    text = " ".join(value.strip().lower().replace("_", " ").replace("-", " ").split())
    compact = text.replace(" ", "")
    return _LABEL_SYNONYMS.get(text) or _LABEL_SYNONYMS.get(compact) or text


def _coerce_probability(value: Any) -> float | None:
    """Coerce a number or numeric string to a probability in ``[0, 1]``.

    Values above 1 up to 100 are read as percentages, since some APIs answer in percent.
    """
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, str):
        text = value.strip().rstrip("%")
        try:
            number = float(text)
        except ValueError:
            return None
        percent = value.strip().endswith("%")
    elif isinstance(value, (int, float)):
        number = float(value)
        percent = False
    else:
        return None
    if percent or 1.0 < number <= 100.0:
        number /= 100.0
    if 0.0 <= number <= 1.0:
        return number
    return None


def _coerce_probability_first(node: dict, exclude: str | None = None) -> float | None:
    for key in _PROB_KEYS:
        if key == exclude:
            continue
        if key in node:
            probability = _coerce_probability(node[key])
            if probability is not None:
                return probability
    return None


def _coerce_id(value: Any) -> int | str | None:
    """Accept an int or a short token as an id; reject prose (e.g. a sentence text)."""
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        text = value.strip()
        if text and len(text) <= 64 and not any(char.isspace() for char in text):
            return int(text) if text.isdigit() else text
    return None


def _first_id(node: dict) -> int | str | None:
    for key in _ID_KEYS:
        if key in node:
            candidate = _coerce_id(node[key])
            if candidate is not None:
                return candidate
    return None


def _looks_like_id(key: Any) -> bool:
    return isinstance(key, str) and key.strip().isdigit()


def _scores_from(node: dict) -> dict[str, float]:
    """Collect a label -> probability mapping from ``node`` or its nested score maps."""
    scores: dict[str, float] = {}

    def absorb(mapping: dict) -> None:
        for key, value in mapping.items():
            if not isinstance(key, str):
                continue
            label = _normalise_label(key)
            if label not in LABELS:
                continue
            probability = _coerce_probability(value)
            if probability is not None:
                scores[label] = probability

    absorb(node)
    for key in _SCORE_CONTAINERS:
        nested = node.get(key)
        if isinstance(nested, dict):
            absorb(nested)
    return scores


def _as_atom(node: dict) -> dict | None:
    """Recognise one answer object, or return ``None`` if ``node`` is not an answer."""
    scores = _scores_from(node)
    for key in _LABEL_KEYS:
        value = node.get(key)
        if isinstance(value, str) and value.strip():
            label = _normalise_label(value)
            probability = scores.get(label)
            if probability is None:
                probability = _coerce_probability_first(node, exclude=key)
            if probability is None and scores:
                probability = max(scores.values())
            return {"label": label, "probability": probability, "scores": scores or None}
    if len(scores) >= 2:
        label = max(scores, key=lambda name: scores[name])
        return {"label": label, "probability": scores[label], "scores": scores}
    return None


def _walk_answers(node: Any, path: str, inherited_id: Any, out: list[dict], depth: int) -> None:
    """Collect answer atoms from an arbitrary JSON tree, in document order."""
    if depth > 10:
        return
    if isinstance(node, list):
        for index, item in enumerate(node):
            _walk_answers(item, f"{path}[{index}]", None, out, depth + 1)
        return
    if not isinstance(node, dict):
        return
    if "criteria" in node or ("instructions" in node and "type" in node):
        return  # an echoed question spec, not an answer

    atom = _as_atom(node)
    if atom is not None:
        atom["id"] = _first_id(node)
        if atom["id"] is None:
            atom["id"] = inherited_id
        atom["path"] = path
        if atom["label"] in LABELS or atom["probability"] is not None or atom["id"] is not None:
            out.append(atom)
        return

    node_id = _first_id(node)
    if node_id is None:
        node_id = inherited_id
    for key, value in node.items():
        if key in _SKIP_SUBTREES:
            continue
        if key in _ID_KEYS and not isinstance(value, (dict, list)):
            continue
        child_id = node_id
        if _looks_like_id(key):
            child_id = _coerce_id(key)
        _walk_answers(value, f"{path}.{key}", child_id, out, depth + 1)


def _extract_answer_atoms(payload: Any) -> list[dict]:
    """Return every answer-like object found in ``payload``."""
    atoms: list[dict] = []
    _walk_answers(payload, "$", None, atoms, 0)
    return atoms


def _shift_zero_based(atoms: list[dict], sentence_ids: Sequence[Any]) -> list[dict] | None:
    """Remap dense 0-based answer ids (``0..n-1``) onto 1-based caller ids, if that fits."""
    if any(atom["id"] is None for atom in atoms):
        return None
    try:
        answer_ids = [int(str(atom["id"])) for atom in atoms]
        caller_ids = sorted(int(str(sentence_id)) for sentence_id in sentence_ids)
    except (TypeError, ValueError):
        return None
    if sorted(answer_ids) != list(range(len(sentence_ids))):
        return None
    if caller_ids != list(range(1, len(sentence_ids) + 1)):
        return None
    shifted: list[dict] = []
    for atom, answer_id in zip(atoms, answer_ids):
        copy = dict(atom)
        copy["id"] = answer_id + 1
        shifted.append(copy)
    return shifted


def _collapse_candidates(atoms: list[dict]) -> list[dict]:
    """Collapse a full probability distribution over the labels into one answer.

    A choice question is sometimes answered as a list of candidate objects rather than a
    single object. Exactly one atom per label, all id-less, is treated as one answer: the
    arg-max becomes the label and the whole distribution is kept in ``scores``.
    """
    if len(atoms) != len(LABELS) or any(atom["id"] is not None for atom in atoms):
        return atoms
    labels = [atom["label"] for atom in atoms]
    if sorted(labels) != sorted(LABELS):
        return atoms
    probabilities = [atom["probability"] for atom in atoms]
    if any(probability is None for probability in probabilities):
        return atoms
    best = max(atoms, key=lambda atom: atom["probability"] or 0.0)
    merged = dict(best)
    merged["scores"] = {atom["label"]: atom["probability"] for atom in atoms}
    merged["id"] = None
    return [merged]


def _assign_atoms(
    atoms: list[dict], sentence_ids: Sequence[Any]
) -> tuple[dict[str, dict], list[dict]]:
    """Map atoms onto caller sentence ids, falling back to document order.

    Returns ``(entries keyed by str(sentence id), leftover atoms)``.
    """
    # A dense 0..n-1 id range can collide with 1..n caller ids (id 1 and 2 look like a
    # match), so try the 0-based reading first: it only applies when it yields a complete,
    # exact mapping, which makes the shift unambiguous.
    shifted = _shift_zero_based(atoms, sentence_ids) if atoms else None
    if shifted is not None:
        atoms = shifted

    known = {str(sentence_id) for sentence_id in sentence_ids}
    entries: dict[str, dict] = {}
    extras: list[dict] = []
    for atom in atoms:
        if atom["id"] is None:
            continue
        key = str(atom["id"])
        if key in known:
            entries.setdefault(key, atom)
        else:
            extras.append(atom)

    pending = [str(sentence_id) for sentence_id in sentence_ids if str(sentence_id) not in entries]
    for atom in atoms:
        if atom["id"] is not None:
            continue
        if pending:
            entries[pending.pop(0)] = atom
        else:
            extras.append(atom)
    return entries, extras


def _normalise_response(text: str, status: int, request: dict, elapsed_ms: float) -> dict:
    """Turn a raw response body into the normalised result dict (never raises)."""
    sentences_in: list[dict] = request["state"]["sentences"]
    raw: Any = None
    error: str | None = None

    try:
        raw = json.loads(text)
    except json.JSONDecodeError as exc:
        error = f"response body was not JSON ({exc.msg} at char {exc.pos})"

    atoms: list[dict] = []
    if error is None:
        atoms = _extract_answer_atoms(raw)
        if atoms:
            atoms = _collapse_candidates(atoms)
        if not atoms:
            error = "no answer-like object found in the response"

    entries: dict[str, dict] = {}
    extras: list[dict] = []
    scope = "none"
    per_sentence = False
    if atoms:
        sentence_ids = [sentence["id"] for sentence in sentences_in]
        if len(atoms) == 1 and atoms[0]["id"] is None and len(sentence_ids) > 1:
            entries = {str(sentence_id): atoms[0] for sentence_id in sentence_ids}
            scope = "request"
        else:
            entries, extras = _assign_atoms(atoms, sentence_ids)
            scope = "sentence"
            per_sentence = len(entries) == len(sentence_ids) and len(entries) > 1

    rows: list[dict] = []
    for sentence in sentences_in:
        atom = entries.get(str(sentence["id"]))
        rows.append(
            {
                "id": sentence["id"],
                "text": sentence["text"],
                "label": atom["label"] if atom else None,
                "probability": atom["probability"] if atom else None,
                "known_label": bool(atom and atom["label"] in LABELS),
                "scores": atom.get("scores") if atom else None,
                "shape": atom.get("path") if atom else None,
            }
        )

    summary = {label: 0 for label in LABELS}
    for row in rows:
        if row["label"] in summary:
            summary[row["label"]] += 1
    parsed = any(row["label"] for row in rows)
    if error is None and not parsed:
        error = "answers were parsed but none matched a sentence id"

    return {
        "model": request["model"],
        "podcast": request["state"]["podcast"],
        "question": QUESTION_ID,
        "status": status,
        "elapsed_ms": round(elapsed_ms, 1),
        "parsed": parsed,
        "scope": scope,
        "per_sentence": per_sentence,
        "answered": sum(1 for row in rows if row["label"]),
        "shape": next((row["shape"] for row in rows if row["shape"]), None),
        "summary": summary,
        "sentences": rows,
        "unmatched": [
            {
                "id": atom["id"],
                "label": atom["label"],
                "probability": atom["probability"],
                "shape": atom.get("path"),
            }
            for atom in extras
        ],
        "error": error,
        "raw": raw,
        "raw_text": text,
    }


def classify_sentences(
    podcast: str,
    sentences: list[dict],
    model: str | None = None,
    timeout: float = DEFAULT_TIMEOUT,
) -> dict:
    """Send one classification request and return the normalised result.

    The result carries ``sentences`` (``id``, ``text``, ``label``, ``probability``, ...),
    ``raw`` (decoded JSON, or ``None`` when the body was not JSON), ``raw_text`` and
    ``elapsed_ms``. Unknown response shapes are not an error: ``parsed`` is ``False``,
    ``error`` says why, and the raw body is preserved.

    Raises :class:`MissingApiKey` when the key is unset or rejected, and
    :class:`SystemOneError` for any other HTTP or transport failure.
    """
    request = build_request(podcast, sentences, model=model)
    api_key = _require_api_key()
    started = time.perf_counter()
    text, status = _post_json(request, api_key, timeout)
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    return _normalise_response(text, status, request, elapsed_ms)


# --------------------------------------------------------------------------------------
# Smoke test / CLI
# --------------------------------------------------------------------------------------


def _clip(text: str, width: int) -> str:
    if width <= 3 or len(text) <= width:
        return text
    return text[: width - 3] + "..."


def _format_table(rows: Sequence[dict]) -> list[str]:
    """Render the per-sentence result as a fixed-width table."""
    id_width = max([2] + [len(str(row["id"])) for row in rows])
    text_width = min(58, max([len("sentence")] + [len(row["text"]) for row in rows]))
    label_width = max([5] + [len(row["label"] or "-") for row in rows])
    lines = [
        f"{'id':>{id_width}} | {'sentence':<{text_width}} | {'label':<{label_width}} | probability",
        "-" * id_width + "-+-" + "-" * text_width + "-+-" + "-" * label_width + "-+-" + "-" * 11,
    ]
    for row in rows:
        label = row["label"] or "-"
        probability = "-" if row["probability"] is None else f"{row['probability']:.3f}"
        lines.append(
            f"{str(row['id']):>{id_width}} | {_clip(row['text'], text_width):<{text_width}} "
            f"| {label:<{label_width}} | {probability:>11}"
        )
    return lines


def _dump_raw(result: dict, out: TextIO) -> None:
    print("", file=out)
    print("raw response:", file=out)
    if result["raw"] is not None:
        print(json.dumps(result["raw"], indent=2, ensure_ascii=True), file=out)
    else:
        print(result["raw_text"] or "(empty body)", file=out)


def run_smoke(
    model: str | None = None,
    timeout: float = DEFAULT_TIMEOUT,
    *,
    show_raw: bool = False,
    save_raw: str | os.PathLike[str] | None = None,
    out: TextIO | None = None,
    err: TextIO | None = None,
) -> int:
    """Run the fixed smoke case and return a process exit code.

    ``2`` means the key is missing or rejected (no request is made when it is unset),
    ``3`` that both model ids were rejected, ``4`` that the API answered but the response
    shape was unrecognised (the raw body is printed), ``1`` any other failure.
    """
    out = out if out is not None else sys.stdout
    err = err if err is not None else sys.stderr
    requested_model = resolve_model(model)

    print("System One smoke test", file=out)
    print(f"  endpoint : {ENDPOINT}", file=out)
    print(f"  model    : {requested_model}", file=out)
    print(f"  podcast  : {SAMPLE_PODCAST}", file=out)
    print(f"  sentences: {len(SAMPLE_SENTENCES)}", file=out)
    print("", file=out)

    if not os.environ.get(KEY_ENV, "").strip():
        # Flush first so the header and the diagnostic stay in a readable order even when
        # stdout is a pipe and stderr is not.
        try:
            out.flush()
        except (AttributeError, ValueError, OSError):  # pragma: no cover - exotic streams
            pass
        print(f"ERROR: {_missing_key_message()}", file=err)
        return EXIT_NO_KEY

    model_in_use = requested_model
    result: dict | None = None
    while True:
        try:
            result = classify_sentences(
                SAMPLE_PODCAST, SAMPLE_SENTENCES, model=model_in_use, timeout=timeout
            )
            break
        except MissingApiKey as exc:
            print(f"ERROR: {exc}", file=err)
            if exc.body:
                print(f"  server body: {_clip(str(exc.body), 1000)}", file=err)
            print("  No result was produced. Exit code 2 means: supply a working key.", file=err)
            return EXIT_NO_KEY
        except SystemOneError as exc:
            if is_unknown_model_error(exc, model_in_use):
                if model_in_use != FALLBACK_MODEL:
                    print(
                        f"[warn] model {model_in_use!r} was rejected (HTTP {exc.status}); "
                        f"retrying with {FALLBACK_MODEL!r}.",
                        file=err,
                    )
                    model_in_use = FALLBACK_MODEL
                    continue
                print(
                    f"ERROR: the service rejected both model ids "
                    f"({requested_model!r} and {FALLBACK_MODEL!r}).",
                    file=err,
                )
                if exc.body:
                    print(f"  server body: {_clip(str(exc.body), 1000)}", file=err)
                return EXIT_MODEL_REJECTED
            print(f"ERROR: {exc}", file=err)
            if exc.body:
                print(f"  server body: {_clip(str(exc.body), 1000)}", file=err)
            return EXIT_ERROR
        except (ValueError, TypeError) as exc:
            print(f"ERROR: could not build the request: {exc}", file=err)
            return EXIT_ERROR

    assert result is not None  # for type checkers; every other branch returned above

    if save_raw:
        try:
            with open(save_raw, "w", encoding="utf-8") as handle:
                handle.write(result["raw_text"])
            print(f"saved raw response to {save_raw}", file=out)
        except OSError as exc:
            print(f"[warn] could not save the raw response: {exc}", file=err)

    print(f"status   : HTTP {result['status']}", file=out)
    print(f"timing   : {result['elapsed_ms']:.0f} ms client-side, 1 request", file=out)

    if not result["parsed"]:
        print(f"parse    : FAILED - {result['error']}", file=err)
        print(
            "[warn] the API responded but no answer could be parsed from it. This is a "
            "client-side parser gap, not a failed call.",
            file=err,
        )
        _dump_raw(result, err)
        return EXIT_UNPARSED

    scope = "per-sentence" if result["per_sentence"] else "single answer for the whole request"
    print(f"parse    : {result['answered']}/{len(result['sentences'])} sentences, {scope}", file=out)
    if result["shape"]:
        print(f"shape    : {result['shape']}", file=out)
    print(
        "labels   : " + "  ".join(f"{label}={result['summary'].get(label, 0)}" for label in LABELS),
        file=out,
    )
    print("", file=out)
    for line in _format_table(result["sentences"]):
        print(line, file=out)

    if result["unmatched"]:
        print("", file=out)
        print(f"[warn] {len(result['unmatched'])} answer(s) did not match a sentence id.", file=out)

    if show_raw:
        _dump_raw(result, out)

    if not result["per_sentence"] and len(result["sentences"]) > 1:
        print("", file=out)
        print("[warn] only one answer came back for the whole request, so every sentence", file=out)
        print("[warn] shows the same label; per-sentence labels are NOT proven by this run.", file=out)

    return EXIT_OK


def _use_utf8_streams() -> None:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError, OSError):  # pragma: no cover - exotic stdout
            pass


def main(argv: Sequence[str] | None = None) -> int:
    """Command-line entry point; also used by ``smoke.py``."""
    parser = argparse.ArgumentParser(
        description="Smoke-test the OpenCode System One advertisement/content classifier."
    )
    parser.add_argument(
        "--model",
        default=None,
        help=f"model id (default: ${MODEL_ENV} or {DEFAULT_MODEL})",
    )
    parser.add_argument(
        "--timeout", type=float, default=DEFAULT_TIMEOUT, help="HTTP timeout in seconds"
    )
    parser.add_argument("--raw", action="store_true", help="also print the raw JSON response")
    parser.add_argument(
        "--save-raw", metavar="PATH", default=None, help="write the raw response body to PATH"
    )
    args = parser.parse_args(argv)
    _use_utf8_streams()
    return run_smoke(
        args.model, args.timeout, show_raw=args.raw, save_raw=args.save_raw
    )


if __name__ == "__main__":
    raise SystemExit(main())
