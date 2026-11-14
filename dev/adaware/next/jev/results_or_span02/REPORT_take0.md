# JEV against the M0 labelled corpus

Generated 2026-11-13 23:38:59 by `next/jev/eval_corpus.py`.

## Run

- Model: `respan/span-02`, take `0`, K=20 cues per request, 4 workers
- Episodes scored: 46 (1 candidates skipped: no cues or no dynamic slots)
- Cues: 38639 total, 2597 gold ad (6.7%)
- Requests: not recorded in this run (cache-only report)
- Missing answers: 0 cues (0.00%)

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
| 0.2 | 3754 | 1658 | 2096 | 939 | 0.44 | 0.64 | 0.52 | 0.35 |
| 0.3 | 3436 | 1568 | 1868 | 1029 | 0.46 | 0.60 | 0.52 | 0.39 |
| 0.4 | 3243 | 1504 | 1739 | 1093 | 0.46 | 0.58 | 0.52 | 0.41 |
| 0.5 | 3063 | 1432 | 1631 | 1165 | 0.47 | 0.55 | 0.51 | 0.43 |
| 0.6 | 2895 | 1376 | 1519 | 1221 | 0.48 | 0.53 | 0.50 | 0.44 |
| 0.7 | 2669 | 1270 | 1399 | 1327 | 0.48 | 0.49 | 0.48 | 0.45 |

Gold is 2597 ad cues of 38639. Calling every cue content gives recall 0; calling every cue ad gives precision 0.07 and recall 1.00.

## Interval level

| threshold | pred pods | hits | gold slots | precision | recall |
| --- | --- | --- | --- | --- | --- |
| 0.2 | 340 | 32 | 142 | 0.09 | 0.23 |
| 0.3 | 338 | 33 | 142 | 0.10 | 0.23 |
| 0.4 | 356 | 26 | 142 | 0.07 | 0.18 |
| 0.5 | 356 | 23 | 142 | 0.06 | 0.16 |
| 0.6 | 358 | 20 | 142 | 0.06 | 0.14 |
| 0.7 | 360 | 13 | 142 | 0.04 | 0.09 |

A hit requires both edges within 5 s. A pod that covers an ad but starts 9 s early
counts as a miss and as a false positive, which is the project's acceptance criterion.

### Coverage diagnostic (not the acceptance criterion)

To separate "the ad was not found" from "the ad was found with loose edges": does any
predicted pod cover at least 80% of the gold slot? Deltas are the union span of the
overlapping pods minus the gold slot, so a split pod still measures the edges it missed.

| threshold | slots | found (>=80% covered) | split across pods | median abs start error | median abs end error | both edges within 5 s |
| --- | --- | --- | --- | --- | --- | --- |
| 0.2 | 142 | 76 | 20 | 4.8s | 0.3s | 33 |
| 0.3 | 142 | 69 | 24 | 4.1s | 0.3s | 33 |
| 0.4 | 142 | 59 | 27 | 4.2s | 0.3s | 27 |
| 0.5 | 142 | 55 | 27 | 4.1s | 0.3s | 26 |
| 0.6 | 142 | 48 | 21 | 4.3s | 0.3s | 23 |
| 0.7 | 142 | 37 | 15 | 4.5s | 0.3s | 17 |

### Per episode (pods / hits of gold slots)

| episode | gold slots | t=0.2 | t=0.3 | t=0.4 | t=0.5 | t=0.6 | t=0.7 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 99-invisible__perfectly-leveraged-for-the-squat | 3 | 5 / 0 of 3 | 5 / 0 of 3 | 6 / 0 of 3 | 6 / 0 of 3 | 6 / 0 of 3 | 6 / 0 of 3 |
| cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season | 4 | 8 / 0 of 4 | 8 / 0 of 4 | 8 / 0 of 4 | 8 / 0 of 4 | 8 / 0 of 4 | 8 / 0 of 4 |
| dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 4 | 8 / 0 of 4 | 7 / 0 of 4 | 7 / 0 of 4 | 9 / 0 of 4 | 9 / 0 of 4 | 11 / 0 of 4 |
| decoder-ring__gandhi-never-said-be-the-change | 5 | 11 / 0 of 5 | 11 / 0 of 5 | 13 / 0 of 5 | 13 / 0 of 5 | 13 / 0 of 5 | 15 / 0 of 5 |
| decoder-ring__presenting-the-sporkful-does-the-world-nee | 2 | 9 / 1 of 2 | 10 / 1 of 2 | 11 / 1 of 2 | 10 / 0 of 2 | 9 / 0 of 2 | 10 / 0 of 2 |
| decoder-ring__the-spooky-saga-of-the-icelandic-dracula | 3 | 8 / 1 of 3 | 9 / 2 of 3 | 9 / 1 of 3 | 9 / 1 of 3 | 9 / 1 of 3 | 9 / 1 of 3 |
| freakonomics-radio__694-does-democracy-really-need-elections | 4 | 9 / 1 of 4 | 8 / 1 of 4 | 8 / 1 of 4 | 8 / 1 of 4 | 9 / 0 of 4 | 10 / 0 of 4 |
| gone-medieval__investigating-viking-burials | 4 | 9 / 0 of 4 | 8 / 0 of 4 | 11 / 0 of 4 | 11 / 0 of 4 | 12 / 0 of 4 | 12 / 0 of 4 |
| grammar-girl__4-tiny-writing-errors-that-caused-huge-pro | 3 | 6 / 0 of 3 | 6 / 0 of 3 | 7 / 0 of 3 | 6 / 0 of 3 | 6 / 0 of 3 | 7 / 0 of 3 |
| grammar-girl__the-language-of-superstition-jinxes-the-sc | 4 | 6 / 1 of 4 | 6 / 0 of 4 | 7 / 1 of 4 | 8 / 1 of 4 | 9 / 1 of 4 | 9 / 1 of 4 |
| grammar-girl__why-english-loves-animals-from-the-bee-s-k | 3 | 6 / 1 of 3 | 7 / 2 of 3 | 8 / 2 of 3 | 8 / 2 of 3 | 8 / 1 of 3 | 10 / 0 of 3 |
| hard-fork__jensen-huang-is-out-at-nvidia | 5 | 6 / 3 of 5 | 7 / 2 of 5 | 7 / 0 of 5 | 7 / 0 of 5 | 8 / 0 of 5 | 8 / 0 of 5 |
| how-i-built-this-with-guy-raz__advice-line-with-tope-awotona-of-calendly | 3 | 9 / 1 of 3 | 7 / 1 of 3 | 7 / 1 of 3 | 6 / 1 of 3 | 6 / 1 of 3 | 6 / 1 of 3 |
| how-i-built-this-with-guy-raz__airbnb-ceo-brian-chesky-i-was-a-great-foun | 3 | 10 / 0 of 3 | 9 / 0 of 3 | 10 / 0 of 3 | 11 / 0 of 3 | 12 / 0 of 3 | 11 / 0 of 3 |
| how-i-built-this-with-guy-raz__dr-squatch-jack-haldrup-from-a-closet-full | 4 | 11 / 0 of 4 | 10 / 0 of 4 | 9 / 0 of 4 | 9 / 1 of 4 | 7 / 1 of 4 | 9 / 0 of 4 |
| odd-lots__james-bosworth-on-what-comes-after-lula-s | 3 | 12 / 1 of 3 | 11 / 1 of 3 | 12 / 1 of 3 | 13 / 1 of 3 | 13 / 1 of 3 | 14 / 1 of 3 |
| odd-lots__this-is-how-new-york-city-takes-out-its-tr | 5 | 10 / 0 of 5 | 10 / 0 of 5 | 11 / 1 of 5 | 13 / 0 of 5 | 13 / 0 of 5 | 12 / 0 of 5 |
| odd-lots__what-nvidia-s-megaspeed-scandal-is-reall | 4 | 9 / 1 of 4 | 10 / 1 of 4 | 11 / 0 of 4 | 12 / 0 of 4 | 14 / 0 of 4 | 12 / 0 of 4 |
| odd-lots__what-nvidia-s-megaspeed-scandal-is-really | 3 | 10 / 0 of 3 | 11 / 0 of 3 | 12 / 0 of 3 | 11 / 0 of 3 | 14 / 0 of 3 | 15 / 0 of 3 |
| planet-money__how-bodegas-led-to-the-yemeni-coffeehous | 5 | 6 / 2 of 5 | 7 / 1 of 5 | 7 / 1 of 5 | 8 / 1 of 5 | 7 / 1 of 5 | 8 / 1 of 5 |
| revisionist-history__from-the-big-dig-mitt-ted | 3 | 4 / 0 of 3 | 3 / 0 of 3 | 4 / 0 of 3 | 5 / 0 of 3 | 5 / 0 of 3 | 5 / 0 of 3 |
| shell-game__bonus-episode-shell-game-x-no-such-thing | 6 | 14 / 3 of 6 | 13 / 3 of 6 | 12 / 3 of 6 | 10 / 2 of 6 | 9 / 2 of 6 | 8 / 2 of 6 |
| state-of-the-world-from-npr__anthony-bourdain-s-legacy-in-russia | 2 | 2 / 0 of 2 | 2 / 0 of 2 | 2 / 0 of 2 | 1 / 0 of 2 | 1 / 0 of 2 | 1 / 0 of 2 |
| state-of-the-world-from-npr__breeding-beer-hops-for-a-hotter-planet | 1 | 2 / 1 of 1 | 2 / 1 of 1 | 2 / 1 of 1 | 2 / 1 of 1 | 2 / 1 of 1 | 2 / 0 of 1 |
| state-of-the-world-from-npr__gaza-struck-by-deadly-storm-israel-debates | 1 | 3 / 0 of 1 | 3 / 0 of 1 | 3 / 0 of 1 | 3 / 0 of 1 | 3 / 0 of 1 | 3 / 0 of 1 |
| state-of-the-world-from-npr__u-s-midterm-results-give-ukrainians-glimme | 1 | 3 / 1 of 1 | 3 / 1 of 1 | 3 / 0 of 1 | 3 / 0 of 1 | 3 / 0 of 1 | 3 / 0 of 1 |
| the-daily__introducing-metro-from-serial-productions | 4 | 15 / 1 of 4 | 12 / 1 of 4 | 11 / 1 of 4 | 10 / 0 of 4 | 8 / 0 of 4 | 7 / 0 of 4 |
| the-daily__keisha-lance-bottoms-on-what-democrats-hav | 3 | 5 / 3 of 3 | 5 / 3 of 3 | 6 / 1 of 3 | 6 / 1 of 3 | 6 / 1 of 3 | 4 / 0 of 3 |
| the-daily__what-jensen-huang-s-resignation-will-mea | 5 | 7 / 2 of 5 | 7 / 2 of 5 | 8 / 2 of 5 | 8 / 2 of 5 | 9 / 2 of 5 | 9 / 2 of 5 |
| the-daily__what-jensen-huang-s-resignation-will-mean | 2 | 6 / 1 of 2 | 7 / 1 of 2 | 7 / 0 of 2 | 7 / 0 of 2 | 7 / 0 of 2 | 7 / 0 of 2 |
| the-economics-show-with-soumaya-keynes__can-economics-fight-fascism-with-isabell | 2 | 5 / 0 of 2 | 5 / 0 of 2 | 5 / 0 of 2 | 5 / 0 of 2 | 5 / 0 of 2 | 4 / 0 of 2 |
| the-political-scene-the-new-yorker__democrats-might-take-back-the-house-then-w | 3 | 7 / 2 of 3 | 7 / 2 of 3 | 7 / 1 of 3 | 7 / 1 of 3 | 7 / 1 of 3 | 7 / 1 of 3 |
| the-political-scene-the-new-yorker__pete-buttigieg-on-the-democrats-sweep-of-c | 4 | 3 / 1 of 4 | 3 / 1 of 4 | 3 / 1 of 4 | 3 / 1 of 4 | 3 / 1 of 4 | 2 / 1 of 4 |
| the-political-scene-the-new-yorker__the-democrats-big-night-and-the-repudiatio | 5 | 7 / 0 of 5 | 5 / 0 of 5 | 5 / 0 of 5 | 3 / 0 of 5 | 3 / 0 of 5 | 1 / 0 of 5 |
| the-rest-is-classified__202-watergate-the-cia-conspiracy-ep-1 | 2 | 9 / 1 of 2 | 9 / 1 of 2 | 9 / 1 of 2 | 9 / 2 of 2 | 9 / 2 of 2 | 10 / 1 of 2 |
| the-rest-is-classified__203-watergate-the-spies-who-went-rogue-ep | 2 | 7 / 1 of 2 | 8 / 1 of 2 | 8 / 1 of 2 | 8 / 1 of 2 | 8 / 1 of 2 | 8 / 1 of 2 |
| the-rest-is-classified__intelligence-scoop-how-nvidia-s-ceo-fell-f | 1 | 9 / 0 of 1 | 10 / 0 of 1 | 10 / 0 of 1 | 10 / 0 of 1 | 10 / 0 of 1 | 9 / 0 of 1 |
| the-rest-is-history__718-the-suez-crisis-britain-s-shattered-em | 3 | 6 / 1 of 3 | 5 / 1 of 3 | 6 / 1 of 3 | 5 / 0 of 3 | 6 / 0 of 3 | 6 / 0 of 3 |
| the-rest-is-history__719-revolution-in-the-soviet-empire-the-hu | 1 | 4 / 1 of 1 | 4 / 1 of 1 | 4 / 1 of 1 | 4 / 1 of 1 | 3 / 1 of 1 | 3 / 0 of 1 |
| the-rest-is-history__721-the-charge-of-the-light-brigade-outbre | 2 | 6 / 0 of 2 | 7 / 0 of 2 | 7 / 0 of 2 | 8 / 0 of 2 | 7 / 0 of 2 | 8 / 0 of 2 |
| the-rest-is-history__722-the-charge-of-the-light-brigade-the-ts | 3 | 6 / 0 of 3 | 7 / 1 of 3 | 6 / 1 of 3 | 6 / 1 of 3 | 6 / 1 of 3 | 6 / 0 of 3 |
| the-rest-is-politics__586-can-the-democrats-capitalise-on-trump | 4 | 6 / 0 of 4 | 8 / 1 of 4 | 10 / 1 of 4 | 10 / 1 of 4 | 8 / 0 of 4 | 9 / 0 of 4 |
| the-weekly-show-with-jon-stewart__a-progressive-policy-prescription-with-abd | 2 | 8 / 0 of 2 | 7 / 0 of 2 | 8 / 0 of 2 | 7 / 0 of 2 | 7 / 0 of 2 | 7 / 0 of 2 |
| the-weekly-show-with-jon-stewart__a-working-class-economy-with-kathryn-anne | 2 | 11 / 0 of 2 | 12 / 0 of 2 | 11 / 0 of 2 | 11 / 0 of 2 | 10 / 0 of 2 | 9 / 0 of 2 |
| the-weekly-show-with-jon-stewart__bursting-the-ai-bubble-with-ed-zitron | 2 | 11 / 0 of 2 | 12 / 0 of 2 | 12 / 0 of 2 | 13 / 0 of 2 | 14 / 0 of 2 | 13 / 0 of 2 |
| the-weekly-show-with-jon-stewart__feeling-the-win-with-sen-bernie-sanders | 2 | 6 / 0 of 2 | 5 / 0 of 2 | 6 / 0 of 2 | 6 / 0 of 2 | 7 / 0 of 2 | 7 / 0 of 2 |

### Every gold slot at t=0.5

| episode | gold slot | predicted coverage | union start error | union end error | pods overlapping | hit (5 s, official) |
| --- | --- | --- | --- | --- | --- | --- |
| 99-invisible__perfectly-leveraged-for-the-squat | 0:00-1:03 | 75% | +11.7s | -4.2s | 1 | no |
| 99-invisible__perfectly-leveraged-for-the-squat | 24:57-27:08 | 66% | +18.3s | +0.0s | 3 | no |
| 99-invisible__perfectly-leveraged-for-the-squat | 38:48-40:42 | 83% | +7.2s | -0.5s | 2 | no |
| cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season | 14:07-14:27 | 1% | -25.2s | -20.0s | 1 | no |
| cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season | 27:51-28:21 | 62% | +11.4s | +15.4s | 1 | no |
| cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season | 28:36-28:56 | 1% | -33.4s | -19.9s | 1 | no |
| cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season | 43:21-45:06 | 77% | -34.8s | -23.7s | 1 | no |
| dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 0:29-2:59 | 48% | +20.4s | -27.9s | 3 | no |
| dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 25:27-27:35 | 47% | +57.4s | +0.0s | 2 | no |
| dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 37:57-38:59 | 60% | +24.7s | -0.3s | 1 | no |
| dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 50:56-52:45 | 35% | +35.2s | -31.2s | 2 | no |
| decoder-ring__gandhi-never-said-be-the-change | 4:38-5:17 | 74% | +10.2s | +32.4s | 1 | no |
| decoder-ring__gandhi-never-said-be-the-change | 19:28-20:38 | 69% | +21.8s | -0.3s | 1 | no |
| decoder-ring__gandhi-never-said-be-the-change | 22:07-23:07 | 59% | -52.5s | -0.3s | 2 | no |
| decoder-ring__gandhi-never-said-be-the-change | 33:35-35:36 | 63% | +6.2s | +14.8s | 2 | no |
| decoder-ring__gandhi-never-said-be-the-change | 1:08:09-1:09:07 | 76% | +14.2s | +0.0s | 1 | no |
| decoder-ring__presenting-the-sporkful-does-the-world-nee | 0:56-1:20 | 77% | +5.6s | +4.8s | 1 | no |
| decoder-ring__presenting-the-sporkful-does-the-world-nee | 36:40-37:09 | 72% | -16.1s | -0.3s | 2 | no |
| decoder-ring__the-spooky-saga-of-the-icelandic-dracula | 27:02-27:27 | 54% | +1.7s | -9.9s | 1 | no |
| decoder-ring__the-spooky-saga-of-the-icelandic-dracula | 40:12-41:27 | 31% | +50.7s | -0.3s | 1 | no |
| decoder-ring__the-spooky-saga-of-the-icelandic-dracula | 57:14-58:11 | 99% | +0.3s | +0.0s | 1 | yes |
| freakonomics-radio__694-does-democracy-really-need-elections | 0:01-1:31 | 64% | +2.8s | +0.0s | 3 | no |
| freakonomics-radio__694-does-democracy-really-need-elections | 23:16-25:34 | 93% | +3.6s | -0.3s | 2 | no |
| freakonomics-radio__694-does-democracy-really-need-elections | 50:18-52:00 | 95% | +4.2s | -0.6s | 1 | yes |
| freakonomics-radio__694-does-democracy-really-need-elections | 1:10:24-1:12:34 | 93% | +0.0s | +0.0s | 2 | no |
| gone-medieval__investigating-viking-burials | 1:49-2:32 | 33% | -32.7s | -29.1s | 1 | no |
| gone-medieval__investigating-viking-burials | 33:44-34:10 | 80% | -15.1s | -0.3s | 2 | no |
| gone-medieval__investigating-viking-burials | 48:34-49:18 | 75% | -14.7s | -0.3s | 2 | no |
| gone-medieval__investigating-viking-burials | 1:04:56-1:05:48 | 30% | +36.5s | +10.4s | 1 | no |
| grammar-girl__4-tiny-writing-errors-that-caused-huge-pro | 9:06-9:58 | 99% | -17.3s | -0.3s | 1 | no |
| grammar-girl__4-tiny-writing-errors-that-caused-huge-pro | 14:18-14:41 | 26% | +17.1s | +9.2s | 1 | no |
| grammar-girl__4-tiny-writing-errors-that-caused-huge-pro | 14:54-15:49 | 90% | -2.0s | +13.8s | 2 | no |
| grammar-girl__the-language-of-superstition-jinxes-the-sc | 0:33-0:57 | 55% | -9.8s | -10.8s | 1 | no |
| grammar-girl__the-language-of-superstition-jinxes-the-sc | 9:57-10:36 | 30% | +25.8s | -1.3s | 1 | no |
| grammar-girl__the-language-of-superstition-jinxes-the-sc | 11:08-11:45 | 55% | -23.5s | -0.3s | 2 | no |
| grammar-girl__the-language-of-superstition-jinxes-the-sc | 14:15-14:46 | 85% | +4.5s | -0.3s | 1 | yes |
| grammar-girl__why-english-loves-animals-from-the-bee-s-k | 0:33-1:03 | 92% | -25.3s | -0.3s | 2 | yes |
| grammar-girl__why-english-loves-animals-from-the-bee-s-k | 14:38-15:08 | 90% | +2.7s | -0.3s | 1 | yes |
| grammar-girl__why-english-loves-animals-from-the-bee-s-k | 33:28-33:54 | 37% | +14.1s | -2.2s | 1 | no |
| hard-fork__jensen-huang-is-out-at-nvidia | 0:00-0:31 | 63% | +11.7s | +0.0s | 1 | no |
| hard-fork__jensen-huang-is-out-at-nvidia | 2:33-4:08 | 71% | +11.8s | -6.8s | 2 | no |
| hard-fork__jensen-huang-is-out-at-nvidia | 20:37-22:11 | 68% | +0.0s | -30.2s | 1 | no |
| hard-fork__jensen-huang-is-out-at-nvidia | 41:43-43:15 | 55% | +0.0s | +0.0s | 2 | no |
| hard-fork__jensen-huang-is-out-at-nvidia | 44:37-45:09 | 83% | +5.3s | +0.0s | 1 | no |
| how-i-built-this-with-guy-raz__advice-line-with-tope-awotona-of-calendly | 1:34-2:18 | 27% | -22.0s | -0.3s | 2 | no |
| how-i-built-this-with-guy-raz__advice-line-with-tope-awotona-of-calendly | 45:59-46:57 | 66% | +19.2s | -0.3s | 1 | no |
| how-i-built-this-with-guy-raz__advice-line-with-tope-awotona-of-calendly | 59:29-59:56 | 100% | -3.2s | +0.0s | 1 | yes |
| how-i-built-this-with-guy-raz__airbnb-ceo-brian-chesky-i-was-a-great-foun | 33:51-34:12 | 56% | -23.7s | -0.3s | 2 | no |
| how-i-built-this-with-guy-raz__airbnb-ceo-brian-chesky-i-was-a-great-foun | 52:56-53:17 | 54% | -23.8s | -0.3s | 2 | no |
| how-i-built-this-with-guy-raz__airbnb-ceo-brian-chesky-i-was-a-great-foun | 1:16:29-1:17:27 | 94% | -7.8s | +0.0s | 2 | no |
| how-i-built-this-with-guy-raz__dr-squatch-jack-haldrup-from-a-closet-full | 2:04-2:40 | 99% | -3.5s | -0.3s | 1 | yes |
| how-i-built-this-with-guy-raz__dr-squatch-jack-haldrup-from-a-closet-full | 37:35-38:15 | 99% | -33.9s | -0.3s | 1 | no |
| how-i-built-this-with-guy-raz__dr-squatch-jack-haldrup-from-a-closet-full | 59:54-1:00:28 | 41% | +19.7s | +9.2s | 1 | no |
| how-i-built-this-with-guy-raz__dr-squatch-jack-haldrup-from-a-closet-full | 1:00:32-1:01:03 | 79% | -17.8s | +13.1s | 2 | no |
| odd-lots__james-bosworth-on-what-comes-after-lula-s | 0:16-0:31 | 96% | +0.3s | -0.3s | 1 | yes |
| odd-lots__james-bosworth-on-what-comes-after-lula-s | 26:46-27:09 | 100% | -17.0s | +27.1s | 1 | no |
| odd-lots__james-bosworth-on-what-comes-after-lula-s | 40:43-41:04 | 27% | +15.2s | +45.2s | 1 | no |
| odd-lots__this-is-how-new-york-city-takes-out-its-tr | 0:53-1:22 | 90% | -35.0s | -0.6s | 2 | no |
| odd-lots__this-is-how-new-york-city-takes-out-its-tr | 21:30-21:51 | 99% | +0.3s | +25.5s | 1 | no |
| odd-lots__this-is-how-new-york-city-takes-out-its-tr | 22:16-23:19 | 0% | -45.7s | -62.8s | 1 | no |
| odd-lots__this-is-how-new-york-city-takes-out-its-tr | 41:11-41:34 | 82% | -2.7s | +1.7s | 2 | no |
| odd-lots__this-is-how-new-york-city-takes-out-its-tr | 42:44-43:47 | 0% | -61.1s | -62.4s | 1 | no |
| odd-lots__what-nvidia-s-megaspeed-scandal-is-reall | 0:14-1:22 | 85% | +7.6s | -1.2s | 2 | no |
| odd-lots__what-nvidia-s-megaspeed-scandal-is-reall | 16:59-18:37 | 77% | +14.9s | +3.5s | 2 | no |
| odd-lots__what-nvidia-s-megaspeed-scandal-is-reall | 40:22-43:23 | 84% | +22.2s | -0.1s | 3 | no |
| odd-lots__what-nvidia-s-megaspeed-scandal-is-reall | 1:01:30-1:03:56 | 89% | +0.4s | -0.2s | 3 | no |
| odd-lots__what-nvidia-s-megaspeed-scandal-is-really | 0:45-1:15 | 99% | -14.1s | -0.3s | 1 | no |
| odd-lots__what-nvidia-s-megaspeed-scandal-is-really | 18:50-42:41 | 8% | -2.9s | +36.6s | 3 | no |
| odd-lots__what-nvidia-s-megaspeed-scandal-is-really | 1:02:05-1:02:46 | 0% | n/a | n/a | 0 | no |
| planet-money__how-bodegas-led-to-the-yemeni-coffeehous | 0:00-0:16 | 74% | +4.1s | +0.0s | 1 | yes |
| planet-money__how-bodegas-led-to-the-yemeni-coffeehous | 4:33-5:38 | 80% | +1.1s | -3.6s | 2 | no |
| planet-money__how-bodegas-led-to-the-yemeni-coffeehous | 18:20-19:49 | 86% | +1.2s | -0.3s | 3 | no |
| planet-money__how-bodegas-led-to-the-yemeni-coffeehous | 26:04-26:26 | 39% | +13.5s | +0.0s | 1 | no |
| planet-money__how-bodegas-led-to-the-yemeni-coffeehous | 27:01-27:36 | 79% | +7.7s | +0.0s | 1 | no |
| revisionist-history__from-the-big-dig-mitt-ted | 0:00-1:32 | 44% | +12.2s | -39.2s | 1 | no |
| revisionist-history__from-the-big-dig-mitt-ted | 14:42-16:04 | 30% | +13.0s | -43.9s | 1 | no |
| revisionist-history__from-the-big-dig-mitt-ted | 30:02-32:52 | 56% | +63.6s | +0.0s | 3 | no |
| shell-game__bonus-episode-shell-game-x-no-such-thing | 0:04-0:26 | 65% | +7.8s | +3.2s | 1 | no |
| shell-game__bonus-episode-shell-game-x-no-such-thing | 0:59-2:27 | 11% | +23.8s | -54.9s | 1 | no |
| shell-game__bonus-episode-shell-game-x-no-such-thing | 10:27-11:46 | 73% | +15.1s | -3.2s | 2 | no |
| shell-game__bonus-episode-shell-game-x-no-such-thing | 40:46-41:47 | 93% | +0.3s | -3.8s | 1 | yes |
| shell-game__bonus-episode-shell-game-x-no-such-thing | 42:13-42:41 | 60% | +11.2s | +22.7s | 1 | no |
| shell-game__bonus-episode-shell-game-x-no-such-thing | 56:02-57:04 | 99% | +0.3s | -0.3s | 1 | yes |
| state-of-the-world-from-npr__anthony-bourdain-s-legacy-in-russia | 0:07-0:34 | 0% | n/a | n/a | 0 | no |
| state-of-the-world-from-npr__anthony-bourdain-s-legacy-in-russia | 8:09-8:44 | 27% | +0.3s | -25.3s | 1 | no |
| state-of-the-world-from-npr__breeding-beer-hops-for-a-hotter-planet | 6:19-6:43 | 100% | -2.9s | +0.0s | 1 | yes |
| state-of-the-world-from-npr__gaza-struck-by-deadly-storm-israel-debates | 9:03-9:48 | 93% | +0.3s | +0.0s | 2 | no |
| state-of-the-world-from-npr__u-s-midterm-results-give-ukrainians-glimme | 9:54-10:25 | 64% | -29.0s | +0.0s | 2 | no |
| the-daily__introducing-metro-from-serial-productions | 0:00-0:58 | 15% | +13.2s | -36.1s | 1 | no |
| the-daily__introducing-metro-from-serial-productions | 8:15-9:42 | 67% | +3.7s | -0.3s | 3 | no |
| the-daily__introducing-metro-from-serial-productions | 22:46-24:19 | 81% | +12.6s | -0.3s | 2 | no |
| the-daily__introducing-metro-from-serial-productions | 45:32-45:48 | 61% | +5.9s | +0.0s | 1 | no |
| the-daily__keisha-lance-bottoms-on-what-democrats-hav | 0:00-0:46 | 60% | +17.9s | -0.3s | 1 | no |
| the-daily__keisha-lance-bottoms-on-what-democrats-hav | 16:00-16:55 | 88% | +4.1s | -2.4s | 1 | yes |
| the-daily__keisha-lance-bottoms-on-what-democrats-hav | 32:27-32:55 | 81% | +2.6s | +0.0s | 2 | no |
| the-daily__what-jensen-huang-s-resignation-will-mea | 0:00-0:30 | 89% | +0.0s | -3.4s | 1 | yes |
| the-daily__what-jensen-huang-s-resignation-will-mea | 11:55-13:32 | 72% | +0.0s | +0.0s | 3 | no |
| the-daily__what-jensen-huang-s-resignation-will-mea | 24:35-25:40 | 68% | +17.7s | -2.9s | 1 | no |
| the-daily__what-jensen-huang-s-resignation-will-mea | 26:02-26:22 | 56% | -19.7s | -3.7s | 2 | no |
| the-daily__what-jensen-huang-s-resignation-will-mea | 28:30-28:53 | 92% | +1.7s | +4.6s | 1 | yes |
| the-daily__what-jensen-huang-s-resignation-will-mean | 12:28-13:35 | 63% | +14.9s | -4.7s | 2 | no |
| the-daily__what-jensen-huang-s-resignation-will-mean | 24:45-26:03 | 88% | +4.4s | +16.0s | 2 | no |
| the-economics-show-with-soumaya-keynes__can-economics-fight-fascism-with-isabell | 19:28-19:59 | 30% | +19.3s | -2.2s | 1 | no |
| the-economics-show-with-soumaya-keynes__can-economics-fight-fascism-with-isabell | 35:46-37:26 | 47% | +32.2s | +0.0s | 3 | no |
| the-political-scene-the-new-yorker__democrats-might-take-back-the-house-then-w | 15:10-15:42 | 57% | +11.4s | -2.3s | 1 | no |
| the-political-scene-the-new-yorker__democrats-might-take-back-the-house-then-w | 15:45-16:15 | 86% | +4.1s | -0.3s | 1 | yes |
| the-political-scene-the-new-yorker__democrats-might-take-back-the-house-then-w | 36:24-37:15 | 70% | +14.2s | +16.9s | 2 | no |
| the-political-scene-the-new-yorker__pete-buttigieg-on-the-democrats-sweep-of-c | 13:35-14:05 | 98% | +0.3s | -0.3s | 1 | yes |
| the-political-scene-the-new-yorker__pete-buttigieg-on-the-democrats-sweep-of-c | 15:01-15:37 | 25% | +26.7s | -0.3s | 1 | no |
| the-political-scene-the-new-yorker__pete-buttigieg-on-the-democrats-sweep-of-c | 33:16-33:50 | 0% | n/a | n/a | 0 | no |
| the-political-scene-the-new-yorker__pete-buttigieg-on-the-democrats-sweep-of-c | 34:29-35:16 | 1% | -8.2s | -47.2s | 1 | no |
| the-political-scene-the-new-yorker__the-democrats-big-night-and-the-repudiatio | 12:32-13:27 | 0% | n/a | n/a | 0 | no |
| the-political-scene-the-new-yorker__the-democrats-big-night-and-the-repudiatio | 14:24-14:54 | 0% | n/a | n/a | 0 | no |
| the-political-scene-the-new-yorker__the-democrats-big-night-and-the-repudiatio | 22:01-22:52 | 41% | -7.8s | -0.3s | 2 | no |
| the-political-scene-the-new-yorker__the-democrats-big-night-and-the-repudiatio | 37:06-37:50 | 0% | n/a | n/a | 0 | no |
| the-political-scene-the-new-yorker__the-democrats-big-night-and-the-repudiatio | 37:59-39:17 | 10% | +31.3s | -38.7s | 1 | no |
| the-rest-is-classified__202-watergate-the-cia-conspiracy-ep-1 | 31:05-32:09 | 92% | -20.6s | -0.3s | 2 | yes |
| the-rest-is-classified__202-watergate-the-cia-conspiracy-ep-1 | 1:02:21-1:02:51 | 89% | -20.6s | -0.3s | 2 | yes |
| the-rest-is-classified__203-watergate-the-spies-who-went-rogue-ep | 39:10-39:45 | 91% | +2.7s | -0.3s | 1 | yes |
| the-rest-is-classified__203-watergate-the-spies-who-went-rogue-ep | 57:05-57:58 | 84% | +0.3s | +14.4s | 2 | no |
| the-rest-is-classified__intelligence-scoop-how-nvidia-s-ceo-fell-f | 52:54-54:03 | 68% | +0.3s | +7.1s | 2 | no |
| the-rest-is-history__718-the-suez-crisis-britain-s-shattered-em | 0:40-1:07 | 99% | -33.4s | -0.3s | 1 | no |
| the-rest-is-history__718-the-suez-crisis-britain-s-shattered-em | 44:31-45:02 | 84% | -33.3s | -0.3s | 3 | no |
| the-rest-is-history__718-the-suez-crisis-britain-s-shattered-em | 1:15:48-1:17:31 | 0% | n/a | n/a | 0 | no |
| the-rest-is-history__719-revolution-in-the-soviet-empire-the-hu | 1:26:49-1:28:10 | 100% | +0.3s | +0.0s | 1 | yes |
| the-rest-is-history__721-the-charge-of-the-light-brigade-outbre | 2:00-2:53 | 38% | -8.9s | -33.1s | 1 | no |
| the-rest-is-history__721-the-charge-of-the-light-brigade-outbre | 1:09:52-1:11:22 | 46% | +48.4s | +9.2s | 1 | no |
| the-rest-is-history__722-the-charge-of-the-light-brigade-the-ts | 1:56-2:56 | 73% | -4.6s | -16.0s | 1 | no |
| the-rest-is-history__722-the-charge-of-the-light-brigade-the-ts | 33:03-34:02 | 85% | -10.0s | -8.9s | 1 | no |
| the-rest-is-history__722-the-charge-of-the-light-brigade-the-ts | 1:03:13-1:04:14 | 98% | -7.1s | +0.0s | 2 | yes |
| the-rest-is-politics__586-can-the-democrats-capitalise-on-trump | 2:22-2:38 | 98% | -23.7s | -0.3s | 1 | no |
| the-rest-is-politics__586-can-the-democrats-capitalise-on-trump | 3:36-4:06 | 86% | -31.8s | -0.3s | 2 | yes |
| the-rest-is-politics__586-can-the-democrats-capitalise-on-trump | 36:44-38:16 | 94% | +0.9s | -0.3s | 3 | no |
| the-rest-is-politics__586-can-the-democrats-capitalise-on-trump | 1:13:20-1:14:59 | 79% | +11.6s | +0.0s | 2 | no |
| the-weekly-show-with-jon-stewart__a-progressive-policy-prescription-with-abd | 0:00-0:27 | 82% | +4.9s | +24.5s | 1 | no |
| the-weekly-show-with-jon-stewart__a-progressive-policy-prescription-with-abd | 0:29-1:06 | 61% | -24.5s | -14.2s | 1 | no |
| the-weekly-show-with-jon-stewart__a-working-class-economy-with-kathryn-anne | 0:12-1:38 | 19% | -5.1s | -69.5s | 1 | no |
| the-weekly-show-with-jon-stewart__a-working-class-economy-with-kathryn-anne | 1:29:02-1:31:05 | 52% | +5.2s | -25.4s | 2 | no |
| the-weekly-show-with-jon-stewart__bursting-the-ai-bubble-with-ed-zitron | 0:00-0:31 | 92% | +0.5s | +26.7s | 2 | no |
| the-weekly-show-with-jon-stewart__bursting-the-ai-bubble-with-ed-zitron | 1:38:10-1:38:44 | 87% | -8.6s | -1.4s | 3 | no |
| the-weekly-show-with-jon-stewart__feeling-the-win-with-sen-bernie-sanders | 0:00-0:27 | 82% | +4.9s | +17.7s | 1 | no |
| the-weekly-show-with-jon-stewart__feeling-the-win-with-sen-bernie-sanders | 1:20:20-1:21:01 | 27% | -14.6s | -30.3s | 1 | no |

## Baked-candidate confound

Baked-ad intervals are available for 4 of 46 episodes (17 intervals); the corpus labels contain none.

| threshold | content cues called ad | on a baked candidate | share | precision as scored | precision if those were ads |
| --- | --- | --- | --- | --- | --- |
| 0.2 | 21 | 1 | 4.8% | 0.44 | 0.44 |
| 0.3 | 13 | 1 | 7.7% | 0.46 | 0.46 |
| 0.4 | 11 | 1 | 9.1% | 0.46 | 0.46 |
| 0.5 | 11 | 1 | 9.1% | 0.47 | 0.47 |
| 0.6 | 10 | 1 | 10.0% | 0.48 | 0.48 |
| 0.7 | 10 | 1 | 10.0% | 0.48 | 0.48 |

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
| gold ad, called ad | 1432 | 306 | 0.21 |
| gold content, called ad | 1631 | 325 | 0.20 |
| gold ad, called content | 1165 | 22 | 0.02 |
| gold content, called content | 34411 | 272 | 0.01 |

Predicted pods that overlap no gold slot: 153, 72.1 minutes in total, median 22.7s, mean p_ad 0.82

| seconds | mean p_ad | episode | first cue |
| --- | --- | --- | --- |
| 142 | 0.86 | the-rest-is-history__719-revolution-in-the-soviet-empire-the-hu | And Revolut is founded on the same principle. |
| 89 | 0.83 | odd-lots__james-bosworth-on-what-comes-after-lula-s | Get started at chachipt.com. The world of business is constantly evolving. Every day, new challenges, |
| 77 | 0.83 | decoder-ring__the-spooky-saga-of-the-icelandic-dracula | partners. Are you on track for financial independence? And if you're on track the same way you're |
| 68 | 0.84 | the-rest-is-classified__intelligence-scoop-how-nvidia-s-ceo-fell-f | Every annual gift comes with a special edition t-shirt, merch! |
| 67 | 0.83 | odd-lots__what-nvidia-s-megaspeed-scandal-is-really | They bring together local support and a broad range of resources to more than 7 million |
| 64 | 0.86 | cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season | Running a small business takes everything you've got, but with Chase for business, |
| 62 | 0.87 | the-political-scene-the-new-yorker__democrats-might-take-back-the-house-then-w | And this season, I'm finding those gifts at Quints. |
| 62 | 0.83 | grammar-girl__why-english-loves-animals-from-the-bee-s-k | With Google Workspace with Gemini, |

If these pods read like ads, the labels are incomplete rather than the model wrong: the
take-diff method only finds pods that differ between takes, so a sponsor read baked into
every take is labelled `content`. The marker rates above are the evidence either way.

## Midpoint vs overlap gold

0 of 38639 cues change gold between the midpoint rule and `overlap >= 0.5`.

| rule | gold ad cues | P/R @0.3 | P/R @0.5 | P/R @0.7 |
| --- | --- | --- | --- | --- |
| midpoint | 2597 | 0.46/0.60 | 0.47/0.55 | 0.48/0.49 |
| overlap>=0.5 | 2597 | 0.46/0.60 | 0.47/0.55 | 0.48/0.49 |

The interval tables do not depend on this rule: they use the gold slots directly.

## Examples

### Gold ad cues with the highest p_ad (found)

| p_ad | model label | episode (take) | at | cue |
| --- | --- | --- | --- | --- |
| 0.999 | advertisement | freakonomics-radio__694-does-democracy-really-need-elections (t0) | 23:53 | months or $180 for 12 months, plan required $15 per month equivalent. |
| 0.996 | advertisement | the-daily__what-jensen-huang-s-resignation-will-mean (t0) | 13:20 | with a qualifying deposit today. |
| 0.995 | advertisement | freakonomics-radio__694-does-democracy-really-need-elections (t0) | 24:06 | Capable device required. |
| 0.995 | advertisement | the-daily__what-jensen-huang-s-resignation-will-mean (t0) | 13:06 | with zero dollar commissions on stocks, |
| 0.995 | advertisement | freakonomics-radio__694-does-democracy-really-need-elections (t0) | 50:27 | You get better savings rates, fee waivers, and rewards from day one. |
| 0.993 | advertisement | the-daily__what-jensen-huang-s-resignation-will-mean (t0) | 13:10 | plus account types for every investor. |
| 0.993 | advertisement | the-rest-is-politics__586-can-the-democrats-capitalise-on-trump (t0) | 38:09 | 30 seconds response time requires monitoring plan with outdoor protection starting at $49.99 |
| 0.993 | advertisement | the-rest-is-history__722-the-charge-of-the-light-brigade-the-ts (t0) | 33:18 | includes up to 20 gigabytes of hotspot, capable device required. Availability, speed and coverage |
| 0.992 | advertisement | decoder-ring__the-spooky-saga-of-the-icelandic-dracula (t0) | 58:03 | Save 25% site-wide at Bollin'Branch.com with code BUTTERY. |
| 0.991 | advertisement | the-rest-is-history__722-the-charge-of-the-light-brigade-the-ts (t0) | 1:03:39 | with outdoor protection starting at 49.99 a month. |

### Gold ad cues with the lowest p_ad (missed)

| p_ad | model label | episode (take) | at | cue |
| --- | --- | --- | --- | --- |
| 0.000 | content | odd-lots__what-nvidia-s-megaspeed-scandal-is-really (t0) | 22:53 | I don't know the reason that they were dropped, |
| 0.000 | content | odd-lots__what-nvidia-s-megaspeed-scandal-is-really (t0) | 23:10 | Two of the others directly contradicted to us on the record, |
| 0.000 | content | odd-lots__what-nvidia-s-megaspeed-scandal-is-really (t0) | 34:04 | They restricted it at the end of the Biden administration. |
| 0.001 | content | the-political-scene-the-new-yorker__pete-buttigieg-on-the-democrats-sweep-of-c (t0) | 33:29 | But those surrogates had been deceived. |
| 0.001 | content | the-political-scene-the-new-yorker__pete-buttigieg-on-the-democrats-sweep-of-c (t0) | 33:33 | I started crying. |
| 0.001 | content | the-political-scene-the-new-yorker__pete-buttigieg-on-the-democrats-sweep-of-c (t0) | 33:34 | I was like, what the heck did I just get myself into? |
| 0.001 | content | odd-lots__this-is-how-new-york-city-takes-out-its-tr (t0) | 43:05 | to overhaul the EV industry's potentially harmful design choices. |
| 0.001 | content | odd-lots__this-is-how-new-york-city-takes-out-its-tr (t0) | 42:56 | Now they're going back to design basics, |
| 0.001 | content | odd-lots__this-is-how-new-york-city-takes-out-its-tr (t0) | 23:02 | they lose power or break. |
| 0.002 | content | the-political-scene-the-new-yorker__the-democrats-big-night-and-the-repudiatio (t0) | 38:38 | Hi, my name is Zach Barron. |

Some of these are native ad reads that sound like the show (dan-snow's 30 s TikTok read
opens "This episode is brought to you by TikTok" and then narrates a TikTok video), which
is a real model failure. Others sit in slots the diff padded or truncated: the CA tourism
slot in cautionary-tales covers 21 s of a 94 s ad, and the model finds the part the label
left out. The two failures are not distinguishable from the label alone.

### Gold content cues with the highest p_ad (false positives)

| p_ad | model label | episode (take) | at | cue |
| --- | --- | --- | --- | --- |
| 0.997 | advertisement | odd-lots__james-bosworth-on-what-comes-after-lula-s (t0) | 27:26 | Get five free Rips Packs when you deposit $5. |
| 0.996 | advertisement | the-weekly-show-with-jon-stewart__feeling-the-win-with-sen-bernie-sanders (t0) | 31:46 | or $180 for 12 months, plan required, |
| 0.995 | advertisement | the-rest-is-history__718-the-suez-crisis-britain-s-shattered-em (t0) | 0:25 | Taxes and fees, extra, initial plan term only. |
| 0.995 | advertisement | the-political-scene-the-new-yorker__democrats-might-take-back-the-house-then-w (t0) | 0:54 | Get free shipping on your order and 365 day returns. |
| 0.994 | advertisement | the-weekly-show-with-jon-stewart__a-progressive-policy-prescription-with-abd (t0) | 40:27 | when using the code TWS to checkout. |
| 0.994 | advertisement | the-daily__what-jensen-huang-s-resignation-will-mea (t0) | 25:51 | Or unlimited cash back on tickets to concerts and games. |
| 0.994 | advertisement | the-weekly-show-with-jon-stewart__a-progressive-policy-prescription-with-abd (t0) | 1:13:01 | plan required, $15 per month equivalent. Taxes and fees extra, initial plan term only, greater |
| 0.993 | advertisement | the-weekly-show-with-jon-stewart__a-progressive-policy-prescription-with-abd (t0) | 58:45 | for 20% off your first purchase. |
| 0.993 | advertisement | cautionary-tales-with-tim-harford__why-miners-see-ghosts-halloween-season (t0) | 29:57 | Eligibility and qualification requirements must be met. |
| 0.993 | advertisement | the-political-scene-the-new-yorker__democrats-might-take-back-the-house-then-w (t0) | 0:51 | Download the Quince app for app exclusive offers |

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
- The scored set is whatever had a complete cache when the report was built; anything
  not scored is listed above with the reason, and a run interrupted by a quota or a
  network failure resumes rather than restarts.
- Sentences are scored inside a window of K cues, so a pod's first cue has no ad neighbour
  for context; the README records that context changes the answer.
- The model is nondeterministic (the same sentence measured 0.92 and 0.96 on two runs), so
  thresholds near a probability plateau move a few cues between runs.
- A pod inside a content stretch is not automatically an error: it can be a baked ad, a
  sponsor mention in editorial copy, or a promo for a sister show.
