# Jev on OpenRouter — verified contract

Measured 2026-11-13 from `dev/adaware` with the key in `secrets/openrouter.key`.
This is what the app client must implement. Nothing here is guessed.

## Endpoint

Two paths accept the System One body; both returned identical results:

- `POST https://openrouter.ai/api/v1/systemone`
- `POST https://openrouter.ai/api/alpha/decisions`

Prefer `/api/v1/systemone` (versioned). `/api/v1/alpha/decisions` and
`/api/alpha/decisions` (no `v1`) are **404** — do not use them.

`POST https://openrouter.ai/api/v1/chat/completions` **rejects** both jev models:

```
400 {"error":{"message":"respan/span-02 is a decisions model and cannot be used with the
     chat/completions endpoint. Use the /api/alpha/decisions endpoint instead."}}
```

Only `typesafe/jev-router` works on chat/completions; it is not one of the requested models.

## Headers

```
Authorization: Bearer $OPENROUTER_API_KEY
Content-Type: application/json
User-Agent: <anything but Python-urllib on opencode.ai; OpenRouter does not care>
```

## Request (identical to the opencode System One body)

```json
{
  "model": "respan/span-02",
  "state": {
    "podcast": "Technology Today",
    "sentences": [{"id": 1, "text": "..."}, {"id": 2, "text": "..."}]
  },
  "questions": {
    "sentence_1": {
      "type": "choice",
      "instructions": "Classify only the sentence with id 1. Judge that sentence on its own wording, using the rest of the transcript only as context. Ignore every other sentence: they are separate questions.",
      "criteria": {
        "advertisement": "Paid sponsorship, promotional content, commercial offer or call to action.",
        "content": "Normal editorial podcast discussion.",
        "transition": "Introduction to or return from an advertising break."
      }
    }
  }
}
```

One question per sentence, keyed `sentence_<id>`. One answer always comes back per question;
a single question for N sentences answers the whole state once, which is useless here.

## Response

```json
{
  "model": "respan/span-02-20261015",
  "answers": {
    "sentence_1": {
      "type": "choice",
      "choice": "advertisement",
      "probabilities": {"advertisement": 0.9969, "content": 0.0031},
      "confidence": 0.9937
    }
  },
  "usage": {
    "input_tokens": 132,
    "output_tokens": 0,
    "cost": 0.00000132,
    "is_byok": false,
    "cost_details": {"upstream_inference_cost": 0.00000132}
  },
  "id": "gen-dec-1794640610-scbAzqDlQoQHthhxiEXw",
  "provider": "Respan"
}
```

- `answers[key].probabilities["advertisement"]` is the signal. `confidence` ≈ max probability.
- `model` in the response carries a dated suffix (`respan/span-02-20261015`,
  `typesafe/jev-1.14-20261014`) — use the response model for the analysis identity, not the
  requested slug.
- Probabilities are **not** normalised to sum to 1 in every answer (respan returns the two
  chosen classes); always read `probabilities["advertisement"]` and fall back to `choice`.
- `usage.cost` is in USD.

## Models

| model id | response model | latency (K=20) | cost / K=20 request | notes |
|---|---|---|---|---|
| `respan/span-02` (default) | `respan/span-02-20261015` | ~1.2 s | $0.000118 | 19/20 on a synthetic mix; once called a plain sponsor sentence `transition` (p_ad 0.15) |
| `typesafe/jev-1.14` | `typesafe/jev-1.14-20261014` | ~0.38 s | $0.000134 | 20/20 on the same mix |

`"type": "score"` questions are unsupported by the upstream model (HTTP 422 on opencode,
"Endpoint is unavailable"). Use `choice` only.

## Batching, thresholds, pods

- K=20 sentences per request is verified for both models; K=40 verified on opencode.
  ~585 cues per 45-minute episode ⇒ ~30 requests at K=20.
- Classify cue-level, then build pods on device: keep cues with `p_ad >= 0.5`, allow a
  one-cue gap, drop pods shorter than 8 s.
- Context matters: a sentence is judged with its neighbours in the same request. Do not
  send one sentence alone.
- Sampling is nondeterministic and there is no seed/temperature control on this endpoint.

## Device caveat (carried over from M2)

Corpus numbers use whisper timestamps. The Android transcriber spreads each 15 s
SpeechRecognizer chunk evenly across its returned segments, so cue times are only accurate
to ±7.5 s. Pod edges inherit that error; do not claim 5 s accuracy on device.
