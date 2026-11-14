# JEV results — what the corpus says

Two runs exist. They are not comparable as a quality ranking: one is a different model, the
other is a different endpoint. Read the caveats before using any number.

## Run A — `jev-1.13-free` through opencode, 5 episodes

- Endpoint `opencode.ai/inference/systemone/v1/systemone`, K=20, take 0.
- Stopped by `429 FreeUsageLimitError` after ~200 requests. Cache:
  `results/`, report `results/REPORT_take0.md`.
- Cue-level @0.5: **P 0.48 / R 0.67**; interval hits 0 of 18 slots inside 5 s.
- Two episodes with trustworthy labels: 0.88/0.87 (99% Invisible) and 0.93/0.75 (Dan Snow).
- The other three carry heavy unlabelled ad copy, which is what drags precision down.

## Run B — `respan/span-02` through OpenRouter, 46 episodes

- Endpoint `openrouter.ai/api/v1/systemone`, K=20, take 0, 4 workers.
- 1,872 requests, 38,639 cues, 23.99M input tokens, 7.9 min wall clock, 0 missing answers.
- Cost estimated at ~$0.24 (respan input is $10/M tokens; the eval does not sum `usage.cost`).
- Cache: `results_or_span02/`, report `results_or_span02/REPORT_take0.md`.
- Gold: 2,597 ad cues of 38,639 (6.7%); 142 gold slots.

| threshold | cue P | cue R | pod P | pod R | slots covered >= 80% |
| --- | --- | --- | --- | --- | --- |
| 0.3 | 0.46 | 0.60 | 0.10 | 0.23 | 69 / 142 |
| 0.5 | 0.47 | 0.55 | 0.06 | 0.16 | 55 / 142 |
| 0.7 | 0.48 | 0.49 | 0.04 | 0.09 | 37 / 142 |

## The three findings that matter

1. **Cue tagging is usable; pod clipping is not.** Where a pod is found at all, the median
   start error is 4.1 s and the median end error is 0.3 s — the model finds the end of an ad
   well and its start badly. Only 26 of 142 slots get *both* bounds inside 5 s at t=0.5,
   because start errors are bimodal: dead on or tens of seconds out (pods split when mid-pod
   copy dips to p_ad 0.2-0.33, and host reads bleed into the labelled slot).
2. **Precision is measuring the labels.** 153 predicted pods overlap no gold slot: 72.1
   minutes, mean p_ad 0.82. Their text is unambiguous ad copy (`querce`, `verizon $30 a
   line`, `nerdwalletwealthpartners.com`). Those cues carry a promo marker at 0.20, against
   0.01 for genuine content — a 20x difference, so they are not hallucinated ads. The
   sibling-take label method only sees ads that are *stitched differently per download*;
   baked pre-rolls and host reads are invisible to it.
3. **Native/host-read copy is the recall hole.** A 30 s TikTok read opens with "This episode
   is brought to you by TikTok", narrates the video and scores 0.00-0.02 until its closing
   tagline. The model needs a brand plus a call to action before it fires. This is the right
   failure for an app that shows suggestions rather than auto-skips.

## Consequence for the app

Cue-level, p_ad >= 0.5 is good enough to *mark* ad sentences (what M4 does: italic in the
transcript tab) and not good enough to *cut* pods (what the player's skip needs). Any pod
claim needs an edge-expansion or boundary pass on top, and the device transcriber's
chunk-spread timestamps (±7.5 s) make device-side boundaries worse than these numbers.

## Reproducing

```bash
cd dev/adaware
JEV_ENDPOINT=https://openrouter.ai/api/v1/systemone OPENROUTER_API_KEY=... \
  uv run python next/jev/eval_corpus.py --take 0 --model respan/span-02 \
  --out-dir next/jev/results_or_span02 --workers 4
```

Resume is on by default; `--report-only` re-scores the cache without calling the API.
