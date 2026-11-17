# JEV against the M0 labelled corpus

Generated 2026-11-13 21:26:45 by `next/jev/eval_corpus.py`.

## Run

- Model: `jev-1.13-free`, take `0`, K=20 cues per request, 4 workers
- Episodes scored: 5 (1 candidates skipped: no cues or no dynamic slots)
- Cues: 3602 total, 329 gold ad (9.1%)
- Requests made: 200
- Missing answers: 0 cues (0.00%)
- Run note: 200 window requests succeeded and are in the cache (5 episodes complete, one partial). The full 47-episode run was stopped when the free model returned HTTP 429 FreeUsageLimitError after about 200 requests; roughly 1.4k further calls were rejected before the runner learned to abort on that error.

> **Partial run.** Not every episode could be fetched.
> 41 candidate episode(s) are not fully cached and are excluded from every
> number below, so this report covers 5 episode(s) only. Re-run the same
> command with the cache present to continue where it stopped.

Excluded (incomplete cache): `decoder-ring__the-spooky-saga-of-the-icelandic-dracula`, `freakonomics-radio__694-does-democracy-really-need-elections`, `gone-medieval__investigating-viking-burials`, `grammar-girl__4-tiny-writing-errors-that-caused-huge-pro`, `grammar-girl__the-language-of-superstition-jinxes-the-sc`, `grammar-girl__why-english-loves-animals-from-the-bee-s-k`, `hard-fork__jensen-huang-is-out-at-nvidia`, `how-i-built-this-with-guy-raz__advice-line-with-tope-awotona-of-calendly`, `how-i-built-this-with-guy-raz__airbnb-ceo-brian-chesky-i-was-a-great-foun`, `how-i-built-this-with-guy-raz__dr-squatch-jack-haldrup-from-a-closet-full`, `odd-lots__james-bosworth-on-what-comes-after-lula-s`, `odd-lots__this-is-how-new-york-city-takes-out-its-tr`…


## Method

1. **Gold.** For the scored take, `dynamic_slots` in `corpus/labels/ads.json` are the ad
   pods. A cue is `advertisement` when its midpoint falls inside a slot, else `content`.
   The midpoint is used because whisper cues straddle pod edges: a cue that starts
   inside a pod and ends after it is mostly ad, one that starts before a pod is mostly
   content, and midpoint is the cheapest tiebreaker at cue resolution. The overlap
   fraction is recorded as well and the `>= 0.5` rule is reported as a check below.
2. **Classification.** Cues go out in non-overlapping windows of K=20, one
   `classify_batch` per window, cue index → integer id local to the window. Windows run
   on a small thread pool; a failed window is retried 3 times with 1/2/4 s backoff.
   No episode is ever sent as one request.
3. **Cache.** `<episode>__take<N>.jsonl`, one line per cue with `start`, `end`, `text`,
   `label`, `p_ad`, `p_content`, `p_transition`, `missing`. Only predictions are stored;
   thresholds are applied here, at report time. Resume is on unless `--no-resume`; a
   window counts as cached only when every cue in it is present, non-missing, and its
   cached text still matches the transcript (a stale cache can never be reused).
4. **Cue metrics.** Prediction is `p_ad >= t`; a missing answer counts as not-ad. The
   variant where `p_transition >= t` also counts as ad shows the price of the
   advertisement/transition boundary.
5. **Interval metrics.** Consecutive ad cues merge into a pod, bridging gaps of up to
   1 cue; pods shorter than 8 s are dropped. A pod is a hit only when
   **both** boundaries are within 5 s of a gold slot's boundaries (greedy one-to-one
   matching). Episodes with no dynamic slots are skipped.
6. **Baked candidates.** A predicted-in-content cue can be a real ad the labels omit.
   The corpus labels carry `baked_candidates: []` for every episode, so the legacy files
   (`next/labels/ads_v3.json`, `ads_v2.json`) are read for the ten episodes they overlap.
   Nothing is relabelled; the confound is only counted.

## Cue level

| threshold | pred ad | TP | FP | FN | precision | recall | F1 | precision if transition counts |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0.2 | 560 | 270 | 290 | 59 | 0.48 | 0.82 | 0.61 | 0.45 |
| 0.3 | 534 | 254 | 280 | 75 | 0.48 | 0.77 | 0.59 | 0.45 |
| 0.4 | 500 | 238 | 262 | 91 | 0.48 | 0.72 | 0.57 | 0.45 |
| 0.5 | 462 | 222 | 240 | 107 | 0.48 | 0.67 | 0.56 | 0.45 |
| 0.6 | 424 | 206 | 218 | 123 | 0.49 | 0.63 | 0.55 | 0.47 |
| 0.7 | 389 | 193 | 196 | 136 | 0.50 | 0.59 | 0.54 | 0.48 |

Gold is 329 ad cues of 3602. Calling every cue content gives recall 0; calling every cue ad gives precision 0.09 and recall 1.00.

## Interval level

| threshold | pred pods | hits | gold slots | precision | recall |
| --- | --- | --- | --- | --- | --- |
| 0.2 | 40 | 1 | 18 | 0.03 | 0.06 |
| 0.3 | 48 | 0 | 18 | 0.00 | 0.00 |
| 0.4 | 50 | 0 | 18 | 0.00 | 0.00 |
| 0.5 | 56 | 0 | 18 | 0.00 | 0.00 |
| 0.6 | 53 | 0 | 18 | 0.00 | 0.00 |
| 0.7 | 51 | 0 | 18 | 0.00 | 0.00 |

A hit requires both edges within 5 s. A pod that covers an ad but starts 9 s early
counts as a miss and as a false positive, which is the project's acceptance criterion.

### Coverage diagnostic (not the acceptance criterion)

To separate "the ad was not found" from "the ad was found with loose edges": does any
predicted pod cover at least 80% of the gold slot? Deltas are the union span of the
overlapping pods minus the gold slot, so a split pod still measures the edges it missed.

| threshold | slots | found (>=80% covered) | split across pods | median abs start error | median abs end error | both edges within 5 s |
| --- | --- | --- | --- | --- | --- | --- |
| 0.2 | 18 | 9 | 3 | 11.7s | 4.5s | 2 |
| 0.3 | 18 | 7 | 6 | 22.6s | 4.8s | 2 |
| 0.4 | 18 | 5 | 4 | 22.6s | 4.8s | 1 |
| 0.5 | 18 | 3 | 2 | 24.5s | 4.8s | 0 |
| 0.6 | 18 | 2 | 1 | 20.6s | 5.3s | 0 |
| 0.7 | 18 | 0 | 0 | n/a | n/a | 0 |

### Per episode (pods / hits of gold slots)

| episode | gold slots | t=0.2 | t=0.3 | t=0.4 | t=0.5 | t=0.6 | t=0.7 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 99-invisible__perfectly-leveraged-for-the-squat | 3 | 4 / 1 of 3 | 7 / 0 of 3 | 8 / 0 of 3 | 11 / 0 of 3 | 9 / 0 of 3 | 8 / 0 of 3 |
| cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season | 4 | 9 / 0 of 4 | 11 / 0 of 4 | 10 / 0 of 4 | 9 / 0 of 4 | 9 / 0 of 4 | 9 / 0 of 4 |
| dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 4 | 9 / 0 of 4 | 10 / 0 of 4 | 10 / 0 of 4 | 11 / 0 of 4 | 11 / 0 of 4 | 9 / 0 of 4 |
| decoder-ring__gandhi-never-said-be-the-change | 5 | 11 / 0 of 5 | 12 / 0 of 5 | 11 / 0 of 5 | 14 / 0 of 5 | 13 / 0 of 5 | 14 / 0 of 5 |
| decoder-ring__presenting-the-sporkful-does-the-world-nee | 2 | 7 / 0 of 2 | 8 / 0 of 2 | 11 / 0 of 2 | 11 / 0 of 2 | 11 / 0 of 2 | 11 / 0 of 2 |

### Every gold slot at t=0.5

| episode | gold slot | predicted coverage | union start error | union end error | pods overlapping | hit (5 s, official) |
| --- | --- | --- | --- | --- | --- | --- |
| 99-invisible__perfectly-leveraged-for-the-squat | 0:00-1:03 | 65% | +11.7s | -4.2s | 2 | no |
| 99-invisible__perfectly-leveraged-for-the-squat | 24:57-27:08 | 73% | +0.0s | +0.0s | 4 | no |
| 99-invisible__perfectly-leveraged-for-the-squat | 38:48-40:42 | 77% | -22.6s | -0.5s | 4 | no |
| cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season | 14:07-14:27 | 1% | -36.2s | -20.0s | 1 | no |
| cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season | 27:51-28:21 | 62% | +11.4s | +15.4s | 1 | no |
| cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season | 28:36-28:56 | 3% | -33.4s | +73.7s | 2 | no |
| cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season | 43:21-45:06 | 86% | -34.8s | +17.1s | 2 | no |
| dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 0:29-2:59 | 66% | +18.7s | -27.9s | 2 | no |
| dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 25:27-27:35 | 57% | +33.0s | +0.0s | 3 | no |
| dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 37:57-38:59 | 65% | +21.6s | -0.3s | 1 | no |
| dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 50:56-52:45 | 76% | -2.2s | +0.0s | 3 | no |
| decoder-ring__gandhi-never-said-be-the-change | 4:38-5:17 | 82% | -24.5s | +4.3s | 2 | no |
| decoder-ring__gandhi-never-said-be-the-change | 19:28-20:38 | 69% | +21.8s | -0.3s | 1 | no |
| decoder-ring__gandhi-never-said-be-the-change | 22:07-23:07 | 59% | -39.3s | -0.3s | 2 | no |
| decoder-ring__gandhi-never-said-be-the-change | 33:35-35:36 | 71% | +6.2s | +14.8s | 2 | no |
| decoder-ring__gandhi-never-said-be-the-change | 1:08:09-1:09:07 | 76% | +14.2s | +0.0s | 1 | no |
| decoder-ring__presenting-the-sporkful-does-the-world-nee | 0:56-1:20 | 100% | -6.3s | +4.8s | 1 | no |
| decoder-ring__presenting-the-sporkful-does-the-world-nee | 36:40-37:09 | 72% | -16.1s | -0.3s | 2 | no |

## Baked-candidate confound

Baked-ad intervals are available for 1 of 5 episodes (1 intervals); the corpus labels contain none.

| threshold | content cues called ad | on a baked candidate | share | precision as scored | precision if those were ads |
| --- | --- | --- | --- | --- | --- |
| 0.2 | 9 | 0 | 0.0% | 0.48 | 0.48 |
| 0.3 | 8 | 0 | 0.0% | 0.48 | 0.48 |
| 0.4 | 8 | 0 | 0.0% | 0.48 | 0.48 |
| 0.5 | 7 | 0 | 0.0% | 0.48 | 0.48 |
| 0.6 | 4 | 0 | 0.0% | 0.49 | 0.49 |
| 0.7 | 1 | 0 | 0.0% | 0.50 | 0.50 |

This covers the ten legacy episodes only; the other 37 have no baked candidates in
any label file, so their precision loss stays unattributed. Where a baked candidate
also sits inside a gold slot (99-invisible, freakonomics and the-daily in `ads_v3.json`)
the labels contradict themselves: the same passage is both gold ad and unlabelled ad.
Scoring follows the gold slot, so that case neither helps nor hurts precision here.

### What the false positives actually are

The corpus `baked_candidates` field is empty, so the confound is quantified two ways: a
mechanical promo-vocabulary match per cue group, and a list of predicted pods that fall
entirely outside every gold slot. Neither changes a label.

| cue group @0.5 | cues | contain a promo marker | rate |
| --- | --- | --- | --- |
| gold ad, called ad | 222 | 44 | 0.20 |
| gold content, called ad | 240 | 49 | 0.20 |
| gold ad, called content | 107 | 3 | 0.03 |
| gold content, called content | 3033 | 10 | 0.00 |

Predicted pods that overlap no gold slot: 21, 9.0 minutes in total, median 21.6s, mean p_ad 0.79

| seconds | mean p_ad | episode | first cue |
| --- | --- | --- | --- |
| 68 | 0.92 | cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season | day in CA. Learn more at justanotherdayinca.com. |
| 55 | 0.81 | decoder-ring__gandhi-never-said-be-the-change | And to keep doing it, we need your help. |
| 51 | 0.83 | decoder-ring__presenting-the-sporkful-does-the-world-nee | That's where Vinted comes in. |
| 45 | 0.87 | decoder-ring__presenting-the-sporkful-does-the-world-nee | Everything at Quince is priced 50 to 80% less than similar brands. |
| 39 | 0.74 | decoder-ring__gandhi-never-said-be-the-change | This holiday season, Quince is where you can find |
| 35 | 0.98 | decoder-ring__gandhi-never-said-be-the-change | Please consider signing up for Decoder Ring Plus. |
| 31 | 0.85 | decoder-ring__gandhi-never-said-be-the-change | And beforehand, you'll have the opportunity to submit questions to me and the team and |
| 27 | 0.92 | decoder-ring__presenting-the-sporkful-does-the-world-nee | the headline hammer. With Google Workspace with Gemini, Google's AI is built right into the |

If these pods read like ads, the labels are incomplete rather than the model wrong: the
take-diff method only finds pods that differ between takes, so a sponsor read baked into
every take is labelled `content`. The marker rates above are the evidence either way.

## Midpoint vs overlap gold

0 of 3602 cues change gold between the midpoint rule and `overlap >= 0.5`.

| rule | gold ad cues | P/R @0.3 | P/R @0.5 | P/R @0.7 |
| --- | --- | --- | --- | --- |
| midpoint | 329 | 0.48/0.77 | 0.48/0.67 | 0.50/0.59 |
| overlap>=0.5 | 329 | 0.48/0.77 | 0.48/0.67 | 0.50/0.59 |

The interval tables do not depend on this rule: they use the gold slots directly.

## Examples

### Gold ad cues with the highest p_ad (found)

| p_ad | model label | episode (take) | at | cue |
| --- | --- | --- | --- | --- |
| 1.000 | advertisement | 99-invisible__perfectly-leveraged-for-the-squat (t0) | 0:36 | gets you quality candidates when you need them most. Join the 3.3 million employers |
| 1.000 | advertisement | 99-invisible__perfectly-leveraged-for-the-squat (t0) | 0:46 | of this show will get a $75 sponsored job credit to help get your job the premium status it |
| 1.000 | advertisement | 99-invisible__perfectly-leveraged-for-the-squat (t0) | 25:27 | So grab the layers and people you love and get outside. |
| 1.000 | advertisement | cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season (t0) | 44:32 | reality. So don't wait. Move affordable hep C treatment from your to-do list to your today list. |
| 1.000 | advertisement | cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season (t0) | 44:37 | Ask your doctor about EPCLUSA and visit EPCLUSA.com for important facts sponsored by Gilead. |
| 1.000 | advertisement | dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with (t0) | 0:51 | Just $30 a line per month for you or the whole family. |
| 1.000 | advertisement | dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with (t0) | 1:10 | three free select wall craftsmen or cobalt power tools with a purchase of a |
| 1.000 | advertisement | dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with (t0) | 1:15 | select battery kit three for free you better believe it plus select ladders are |
| 1.000 | advertisement | decoder-ring__gandhi-never-said-be-the-change (t0) | 20:28 | Download the Vintage app for free |
| 1.000 | advertisement | decoder-ring__gandhi-never-said-be-the-change (t0) | 20:30 | to start listing with no seller fees. |

### Gold ad cues with the lowest p_ad (missed)

| p_ad | model label | episode (take) | at | cue |
| --- | --- | --- | --- | --- |
| 0.000 | transition | dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with (t0) | 0:29 | Yeah. |
| 0.000 | content | dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with (t0) | 25:30 | I never thought about the history of coffee. |
| 0.000 | content | dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with (t0) | 25:32 | You make it, you drink it, nothing more to it. |
| 0.000 | content | decoder-ring__gandhi-never-said-be-the-change (t0) | 35:11 | And do you know why? |
| 0.010 | content | 99-invisible__perfectly-leveraged-for-the-squat (t0) | 0:00 | There's a difference between designing something to work and designing something to last. |
| 0.010 | content | decoder-ring__gandhi-never-said-be-the-change (t0) | 34:58 | that the good restaurants all have something in common. |
| 0.010 | content | decoder-ring__gandhi-never-said-be-the-change (t0) | 35:01 | They're busy, genuinely, chaotically busy. |
| 0.020 | content | 99-invisible__perfectly-leveraged-for-the-squat (t0) | 0:05 | Short term thinking asks, is it good enough? |
| 0.020 | content | 99-invisible__perfectly-leveraged-for-the-squat (t0) | 0:08 | Long term thinking asks, what more could it make possible? |
| 0.020 | transition | cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season (t0) | 14:11 | That's what you think you know about California. |

Some of these are native ad reads that sound like the show (dan-snow's 30 s TikTok read
opens "This episode is brought to you by TikTok" and then narrates a TikTok video), which
is a real model failure. Others sit in slots the diff padded or truncated: the CA tourism
slot in cautionary-tales covers 21 s of a 94 s ad, and the model finds the part the label
left out. The two failures are not distinguishable from the label alone.

### Gold content cues with the highest p_ad (false positives)

| p_ad | model label | episode (take) | at | cue |
| --- | --- | --- | --- | --- |
| 1.000 | advertisement | cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season (t0) | 0:05 | Premier hosts on Verbo deliver quality vacation rental stays with fast responses and clear |
| 1.000 | advertisement | cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season (t0) | 0:16 | Book a top rated stay with a premier host. |
| 1.000 | advertisement | cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season (t0) | 0:44 | Get started today at chatgpt.com. |
| 1.000 | advertisement | decoder-ring__gandhi-never-said-be-the-change (t0) | 4:16 | Just $30 a line per month for you or the whole family. |
| 1.000 | advertisement | decoder-ring__gandhi-never-said-be-the-change (t0) | 4:24 | For new customers, $30 per line includes |
| 1.000 | advertisement | decoder-ring__gandhi-never-said-be-the-change (t0) | 6:26 | We are offering 50% off for one year of a Decoder Ring Plus membership, but that's just until |
| 1.000 | advertisement | decoder-ring__presenting-the-sporkful-does-the-world-nee (t0) | 0:18 | Just $30 a line per month for you or the whole family. |
| 1.000 | advertisement | decoder-ring__presenting-the-sporkful-does-the-world-nee (t0) | 1:20 | plus someone who's actually read it. Find out more at nerdwalletwealthpartners.com |
| 1.000 | advertisement | decoder-ring__presenting-the-sporkful-does-the-world-nee (t0) | 24:06 | Download the Vintage app for free to start listing with no seller fees. |
| 0.900 | advertisement | dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with (t0) | 0:04 | you need to become a Dan Snow's History Hit subscriber. |

## Caveats

- Gold is take-specific and derived by diffing sibling takes, so a passage that is ad-like
  and present in every take (a baked ad) is not labelled at all. Precision is a lower
  bound wherever those exist.
- Cue boundaries are whisper's, not the audio's. A cue that straddles a pod edge can be
  scored ad while its midpoint is content, or the reverse; the midpoint rule picks the
  less noisy side, it does not remove the noise.
- Native and host-read ads that imitate the show are where the real recall goes. dan-snow's
  30 s TikTok read (25:27) opens "This episode is brought to you by TikTok", narrates a
  TikTok video and scores 0.00-0.02 until its closing tagline; 99-invisible's opening ad
  (0:00-0:12) is soft-sell copy that scores 0.01. The model needs the brand or a call to
  action before it fires.
- Some slots are truncated or padded by the take diff, which also moves both metrics. The
  cautionary-tales slot at 14:07 covers 21 s of a 94 s CA tourism ad, so 70 s of real ad
  is gold content; `odd-lots__what-nvidia-s-megaspeed-scandal-is-really` take 0 has a single
  1431 s slot (38% of the episode). Cue precision is a lower bound wherever this happens.
- The full 47-episode run did not finish: the free model returned HTTP 429
  `FreeUsageLimitError` after about 200 requests. Only 5 episodes are scored in this
  report, which is far too few for a corpus-level claim; the per-episode table shows how
  much the numbers move between shows.
- Sentences are scored inside a window of K cues, so a pod's first cue has no ad neighbour
  for context; the README records that context changes the answer.
- The model is nondeterministic (the same sentence measured 0.92 and 0.96 on two runs), so
  thresholds near a probability plateau move a few cues between runs.
- A pod inside a content stretch is not automatically an error: it can be a baked ad, a
  sponsor mention in editorial copy, or a promo for a sister show.
