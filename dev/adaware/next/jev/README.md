# JEV — OpenCode "System One" client (M1)

A minimal, dependency-free client for the OpenCode System One inference API, used to ask
one question per request: **is this podcast material an `advertisement`, `content`, or a
`transition` into/out of an ad break?**

This is a *transport and parsing* layer only. It does not decide how to chunk episodes, how
to aggregate sentence labels into segments, or how to evaluate the model.

> **Status: not yet exercised against the live service.** No `OPENCODE_API_KEY` was
> available when this was written, so every claim below about the *response* is an
> assumption derived from the vendor's request contract, not an observation. The offline
> tests prove the client's behaviour against mocked responses only.

## Files

| File | Purpose |
| --- | --- |
| `jev_client.py` | The client: `build_request`, `classify_sentences`, `SystemOneError`/`MissingApiKey`, response normalisation, smoke `main()`. |
| `smoke.py` | The obvious entry point; delegates to `jev_client.main()` so both commands behave identically. |
| `test_jev_client.py` | Offline pytest suite (patches `urllib.request.urlopen`). |
| `conftest.py` | Puts this directory on `sys.path` so `import jev_client` works from any pytest rootdir. |

## Set the key

The key is read from the environment only. It is never written to disk, printed, or
included in an exception message; error bodies are scrubbed before they are surfaced.

```bash
# WSL / bash
export OPENCODE_API_KEY="<your key>"

# PowerShell
$env:OPENCODE_API_KEY = "<your key>"
```

Optional: `JEV_MODEL` overrides the model id for every call (default `jev-1.13-free`).
`classify_sentences(..., model=...)` and `--model` override `JEV_MODEL`.

## Run

All commands are run from `dev/adaware`.

```bash
# smoke test (5 fixed sentences, one call)
uv run python next/jev/smoke.py

# same thing through the library module
uv run python next/jev/jev_client.py

# offline tests
uv run python -m pytest next/jev -q
```

Useful smoke flags: `--model`, `--timeout`, `--raw` (print the raw JSON even when parsing
worked), `--save-raw PATH` (keep the first real response body for review).

### Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Request succeeded and at least one sentence got a label. |
| 1 | Transport failure, unexpected HTTP status, or a request that could not be built. |
| 2 | `OPENCODE_API_KEY` is unset or was rejected (401/403). No request is made when it is unset. |
| 3 | Both `jev-1.13-free` and `jev-1.13` were rejected as unknown models. |
| 4 | The API answered, but no answer could be parsed from the body. The raw body is printed. This is a client parser gap, not a failed call. |

## Request shape

One POST to `https://opencode.ai/inference/systemone/v1/systemone` with
`Authorization: Bearer $OPENCODE_API_KEY` and `Content-Type: application/json`:

```json
{
  "model": "jev-1.13-free",
  "state": {
    "podcast": "Technology Today",
    "sentences": [
      {"id": 1, "text": "Today we discuss the latest AI developments."},
      {"id": 2, "text": "This episode is sponsored by Acme."}
    ]
  },
  "questions": {
    "segment_type": {
      "type": "choice",
      "instructions": "Classify the supplied podcast material according to whether it is an advertisement or editorial content.",
      "criteria": {
        "advertisement": "Paid sponsorship, promotional content, commercial offer or call to action.",
        "content": "Normal editorial podcast discussion.",
        "transition": "Introduction to or return from an advertising break."
      }
    }
  }
}
```

`build_request()` is a pure function that returns exactly this dict (it validates the
sentences, defaults missing `id`s to the 1-based position, and never mutates the caller's
list), which is what the request-shape test asserts.

**The endpoint is stateless per request.** The service does not remember anything from a
previous call, so a request must carry every sentence the model needs for context; asking
about one sentence in isolation will not see the ones sent earlier. Prompt/prefix caching
is not documented and is not assumed here — no request relies on a previous one having
warmed a cache.

## Response handling

The vendor does not document the response body, so `classify_sentences()` **never assumes
one layout and never raises on an unknown one**. It walks the decoded JSON tree for
answer-like objects, then maps them onto the caller's sentence ids.

Accepted, with the shapes the tests cover:

```jsonc
// 1. one answer per question (no ids: applies to the whole request)
{"answers": {"segment_type": {"value": "advertisement", "probability": 0.91}}}

// 2. a list of per-sentence answers
{"results": [{"sentence_id": 2, "label": "advertisement", "confidence": 0.97}, ...]}

// 3. a label -> probability map per sentence
{"answers": [{"id": 2, "probabilities": {"advertisement": 0.9, "content": 0.05, "transition": 0.05}}]}

// 4. a nested envelope
{"response": {"segment_type": {"answer": "transition", "scores": {"transition": 0.7, "content": 0.2}}}}

// 5. answers keyed by sentence id
{"answers": {"1": {"value": "content", "probability": 0.9}}}

// 6. a full distribution over the three labels (collapsed to its arg-max)
{"answers": {"segment_type": [{"label": "content", "probability": 0.12},
                              {"label": "advertisement", "probability": 0.81},
                              {"label": "transition", "probability": 0.07}]}}
```

When exactly one candidate per label comes back with no ids, the three are read as one
answer (a distribution), not as three sentences: the arg-max becomes the label and the
whole distribution is kept in `scores`.

Details of the parser:

* Label keys: `value`, `label`, `answer`, `choice`, `classification`, `class`,
  `prediction`, `verdict`. Probability keys: `probability`, `prob`, `confidence`,
  `score`, `likelihood`, `p`; label→probability maps are also accepted
  (`probabilities`, `scores`, `distribution`, …) and the arg-max is used.
* Labels are normalised (`Sponsored` → `advertisement`, `EDITORIAL` → `content`,
  `intro`/`outro` → `transition`). An unrecognised label is kept verbatim and flagged
  `known_label: false` rather than dropped.
* Percent-style probabilities (`92`) and numeric strings (`"0.87"`) are coerced to
  `[0, 1]`.
* Ids are matched after `str()` coercion, so `"2"` matches `{"id": 2}`. If no id matches
  and the returned ids are a dense `0..n-1` range, they are treated as 0-based indices and
  shifted by one. Answers without ids are assigned in document order. Ids that match no
  sentence are returned under `unmatched`, not discarded.
* An echoed `questions`/`criteria`/`state` subtree is never mistaken for an answer.
* If the body is not JSON, or contains nothing answer-like, `parsed` is `false`, `error`
  says why, `raw`/`raw_text` keep the body intact, and the smoke test prints it and exits
  with code 4.

`classify_sentences()` returns:

```python
{
  "model": "jev-1.13-free",
  "podcast": "Technology Today",
  "question": "segment_type",
  "status": 200,
  "elapsed_ms": 812.4,          # client-side round trip
  "parsed": True,
  "scope": "sentence",          # "sentence" | "request" | "none"
  "per_sentence": True,         # False when one answer covered the whole request
  "answered": 5,
  "shape": "$.results[0]",      # where the first answer was found
  "summary": {"advertisement": 3, "content": 2, "transition": 0},
  "sentences": [
      {"id": 1, "text": "...", "label": "content", "probability": 0.95,
       "known_label": True, "scores": {...}, "shape": "$.results[0]"}
  ],
  "unmatched": [],
  "error": None,
  "raw": {...},                 # decoded JSON, or None if the body was not JSON
  "raw_text": "..."
}
```

**Scope caveat:** if the service returns a single answer for the whole material, the client
applies it to every sentence, sets `per_sentence: false`, and the smoke test prints a
warning. That is a parsing convenience, not evidence that the API classifies per sentence.

## Model id

`JEV_MODEL` / `--model` / `model=` select the id; the default is `jev-1.13-free`. On a
400/404/422 whose body names the model (`unknown model`, `not found`, `unsupported`, …),
`is_unknown_model_error()` returns true and the smoke test retries once with `jev-1.13`
(the plausible fallback). It never retries more than once, and never retries when the
error does not look model-related.

## What is not proven yet

* **Which model id is correct.** `jev-1.13-free` vs `jev-1.13` is unverified; the fallback
  exists precisely because this is unknown.
* **The response shape.** Everything above is defensive inference. The first real call
  (ideally with `--save-raw`) should be inspected before anyone trusts `per_sentence`.
* **Probability semantics.** It is unknown whether a reported probability is calibrated,
  per-label, per-sentence or per-request, or whether it is a confidence over the chosen
  choice only. The client reports the number it was given and does not reinterpret it.
* **Whether one request can carry many sentences usefully.** The contract accepts a list,
  but the answer may cover the whole list rather than each item.
* **Rate limits, quotas, maximum sentence count and token limits.** None are documented
  here; the smoke test sends 5 sentences and one question.
* **Caching / idempotency.** Not documented. This client assumes statelessness and sends
  full context every time; it does not assume prefix caching.
* **Retry/backoff on 429/5xx.** Deliberately absent: the client reports the error and lets
  the caller decide.
