# JEV — OpenCode "System One" client (M1)

Minimal stdlib-only client for the OpenCode System One classifier. One request sends a
podcast name, a list of sentences and **one multiple-choice question per sentence**, and
returns a label (`advertisement`, `content`, `transition`) plus the full probability
distribution for each.

This module is transport and parsing only. It does not chunk episodes, merge sentence
labels into segments, or evaluate the model.

> **Status: measured against the live service** with `jev-1.13-free`. Every claim below is
> an observation, not an inference. Only the response shape documented here is supported;
> anything else raises `SystemOneError` with the raw body attached.

## Files

| File | Purpose |
| --- | --- |
| `jev_client.py` | `build_request()`, `classify_batch()`, `BatchResult`, errors. |
| `smoke.py` | Five-sentence CLI sample; exit codes below. |
| `bench.py` | Two timed batches (K=10, K=40) with accuracy against known labels. |
| `eval_corpus.py` | Scores the classifier against the M0 labelled corpus (cue and interval metrics). |
| `test_jev_client.py` | Offline pytest suite (patches `urllib.request.urlopen`). |
| `test_eval_corpus.py` | Offline tests for the scorer's pure parts (gold, pods, matcher, cache, quotas). |
| `results/` | Per-cue prediction cache (`<episode>__take<N>.jsonl`) and `REPORT_take<N>.md`. |

## Set the key

The key lives at `secrets/opencode.key` in the repo root and is gitignored. Never print
it. The client reads it from `OPENCODE_API_KEY` only, never from disk, and scrubs it from
every error body and message.

```bash
# From dev/adaware, WSL / bash:
export OPENCODE_API_KEY=$(tr -d '\r\n' < ../../secrets/opencode.key)
```

The same one-liner without `export`, as used for the smoke command:

```bash
OPENCODE_API_KEY=$(tr -d '\r\n' < ../../secrets/opencode.key) \
    uv run python next/jev/smoke.py
```

PowerShell: `$env:OPENCODE_API_KEY = "<key>"`.

## Run

All commands run from `dev/adaware`.

```bash
# 5-sentence smoke sample (one request)
uv run python next/jev/smoke.py [--model ID] [--timeout SECONDS] [--raw]

# offline tests
uv run python -m pytest next/jev -q

# timing and accuracy on synthetic batches (two requests)
uv run python next/jev/bench.py

# dry run: how many window requests a corpus pass needs, no API call
OPENCODE_API_KEY=$(tr -d '\r\n' < ../../secrets/opencode.key) \
    uv run python next/jev/eval_corpus.py --limit 1 --dry-run

# score take 0 of the labelled corpus (resumes from next/jev/results/)
OPENCODE_API_KEY=$(tr -d '\r\n' < ../../secrets/opencode.key) \
    uv run python next/jev/eval_corpus.py --take 0

# rescore the cache after changing thresholds or metrics; never calls the API
uv run python next/jev/eval_corpus.py --take 0 --report-only
```

Smoke exit codes: `0` ok, `1` request failed, `2` key missing or rejected (401/403),
`4` at least one sentence got no answer.

## Corpus scoring (M2)

`eval_corpus.py` answers "how well does this find ads" against
`next/m0/corpus/labels/ads.json`. Gold is the take's `dynamic_slots`; a cue is an ad when
its midpoint is inside a slot (the overlap-fraction rule is checked in the report and,
measured on the 3602 scored cues, changes no cue). Cues go out in windows of K=20 cues,
one request each, on 4 threads; predictions are cached per cue as JSONL and every
threshold is applied at report time.

Two levels are reported:

* **Cue level** — precision/recall/F1 for `p_ad >= t`, plus the variant where
  `transition` also counts as ad.
* **Interval level** — consecutive ad cues merge into pods (one non-ad cue may be
  bridged, pods under 8 s are dropped) and a pod is a hit only when **both** boundaries
  are within 5 s of a gold slot. That is the acceptance criterion, and it is much harsher
  than the cue numbers; a coverage diagnostic sits next to it to separate "not found"
  from "found with loose edges".

The app's pod builder is a plain threshold on that probability: a cue is an ad when its
advertisement or transition probability is at least 0.5, and a pod is the run of such cues,
bridged across one non-ad cue and dropped under 8 s. Nothing widens a pod, so the edges are
exactly the first and last sentences the model put in the break. A prompt or model change
therefore requires a fresh corpus pass — each cached row records the prompt revision, and a
mismatch is never reused.

`--report-only` scores the cache without a call, `--no-resume` ignores it, and a window
is cached only when every cue is present, non-missing and its text still matches the
transcript. A hard quota error (`429 FreeUsageLimitError`) stops the run instead of
retrying; episodes that are not fully cached are excluded from the score and listed in
the report.

**Measured:** the free model's daily allowance ran out after ~200 requests (five
episodes), so only a partial corpus pass exists today. The report says so at the top.

## Request contract

`POST https://opencode.ai/inference/systemone/v1/systemone`

```http
Authorization: Bearer $OPENCODE_API_KEY
Content-Type: application/json
User-Agent: pocket-casts-adaware/0.1
```

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
    "sentence_2": {
      "type": "choice",
      "instructions": "Classify only the sentence with id 2. Decide whether it belongs to an advertising break, not whether it sounds like an ad: the break starts at its spoken hand-over and ends when the show resumes, and every sentence between those two points is part of it. Use the other sentences only to locate those two edges. Ignore every other sentence: they are separate questions.",
      "criteria": {
        "advertisement": "The paid read itself: naming or addressing the sponsor, its offer, claims, price, instructions, call to action, or the closing terms and disclaimer. A sentence at the seam that mixes the ad with the show belongs here.",
        "content": "The show's own editorial discussion, outside every advertising break. A brand mentioned in passing with no offer or call to action is content.",
        "transition": "The spoken hand-over that opens or closes an advertising break and sells nothing: announcing a break, naming the sponsor as a hand-over, or returning the listener to the show. It is part of the break."
      }
    }
  }
}
```

**The User-Agent is mandatory.** Python's default `Python-urllib/3.x` is rejected by
Cloudflare with `403 error_code 1010 browser_signature_banned`. `curl/8.5.0`, a Chrome UA
and `pocket-casts-adaware/0.1` all work; the client sends the last one explicitly.

The endpoint is stateless: nothing from a previous call is remembered. Every request
carries the whole sentence list the model needs for context.

## Response contract

```json
{
  "model": "jev-1.13-free",
  "answers": {
    "sentence_2": {
      "type": "choice",
      "choice": "advertisement",
      "confidence": 0.86,
      "probabilities": {"advertisement": 0.91, "content": 0.01, "transition": 0.08}
    }
  },
  "usage": {
    "input_tokens": 396,
    "output_tokens": 45,
    "cost": 0,
    "opencode": {
      "billing_source": "free",
      "list_cost": 0,
      "tokens": {"input": 396, "output": 45, "reasoning": 0, "cache_read": 0,
                 "cache_write_5m": 0, "cache_write_1h": 0}
    }
  }
}
```

`answers[key].probabilities` is the real signal. `confidence` is close to the largest
probability and is kept in `raw` but not interpreted. A missing answer for one id sets
`SentenceResult.missing = True` with `label = None`; the client never invents a label.
Malformed answers, a non-JSON body or a missing `answers` object raise `SystemOneError`
with the raw body attached.

`classify_batch()` returns a `BatchResult`:

| Field | Meaning |
| --- | --- |
| `per_sentence` | `list[SentenceResult]` in request order (`id`, `text`, `label`, `probabilities`, `ad_score`, `missing`). |
| `usage` | The response's `usage` object. |
| `model` | The model id echoed by the service. |
| `elapsed_ms` | Client-side round trip. |
| `raw` | Decoded response JSON. |

## Granularity and batching

One answer per **question**, not per sentence. Five sentences with one question yield one
label for the whole state; `classify_batch()` therefore asks one question per sentence,
keyed `sentence_<id>`, with instructions naming that id.

* Verified at K=40: 40/40 answers returned, 381 ms, ~6.2k input tokens.
* Verified accuracy on a synthetic 40-sentence mix: 40/40.
* Latency is ~300-800 ms per request.
* A sentence is always sent with its neighbours in `state`, because context changes the
  answer: "Acme makes tools for developers." scores content 0.90 alone, but advertisement
  0.99 when the two neighbouring ad sentences are present.

`bench.py` re-checks this on its own mix. One measured run: K=10 9/10 (514 ms, 1.7k input
tokens), K=40 37/40 (1109 ms, 6.2k input tokens). Every answer came back; the three misses
were segment hand-offs and a sponsor announcement ("Our final sponsor is Summit Outdoor
Gear." answered `transition`), so the weak spot is the advertisement/transition boundary,
not answer retrieval.

`"type": "score"` questions are not supported: the service answers HTTP 422
`Upstream request failed: Endpoint is unavailable.` Only `choice` is offered.

Sampling is nondeterministic: the same sentence scored 0.92 and 0.96 on different runs.
Nothing about temperature or seed is exposed, so treat probabilities as noisy scores.

Billing is free: `billing_source: "free"`, `cost: 0`.

## Errors and retries

* `MissingApiKey` — `OPENCODE_API_KEY` unset (no request is made) or rejected with
  401/403. The message never contains the key, and error bodies are scrubbed.
* `SystemOneError(status, body)` — any other HTTP, transport or response-shape failure.
* The client does not retry, except for a single fallback from the requested model id to
  `jev-1.13` when the error body shows the model id itself was rejected. The measured
  rejection is HTTP 400 `Upstream request failed: Model is unavailable.` On this account
  `jev-1.13` then answers 402 `Insufficient balance`, so the free model is currently the
  only usable id; the fallback is one attempt, not a guaranteed path.

## Not yet known

* The true batch ceiling — K=40 works; larger K is untested.
* Rate limits and quotas.
* Whether responses are cached (the endpoint behaves statelessly).
* Probability calibration; `confidence` and `probabilities` are reported, not trusted.
* Whether `transition` is reliable at the edges of an ad pod, as opposed to the pod's
  interior.
