# Independent v2 label QA

Scope: bounded transcript/code sentinels, not exhaustive review of 20.9 hours. No audio listening occurred. Findings below establish semantic omissions; speech timestamps are not verified audio gold. Initial artifact inspected: ads_v2.json written 2026-11-12 21:59 local. Builder was being edited concurrently; final recheck pending.

## High-impact sentinels

| Episode/take | Expected semantic extent | Initial v2 result |
|---|---|---|
| Hard Fork 0 | Five pods: ~0–31.34, 153.32–247.55, 1236.95–1331.47, 2502.88–2595.09, 2677.34–2708.64 (existing Astra audit) | Four pods, still partial; closing Ramp absent |
| Freakonomics 0 | US Bank 3017.70–3043.74; Sylvania 3043.74–3086.89; Hotels.com 3092.86–~3120 | Only 3043.74–3054.15; >30s missed even within Sylvania creative |
| Freakonomics 0 | Closing Ozempic 4224.50–4249.22, Adyen 4255–4288.14, Mazda 4293.74–4325.06, Schwab 4329.20–4353.84 | Only 4247.94–4294.76 |
| Odd Lots 1 | Underdog ~2415.18–2460.80; ChatGPT 2461.06–2497.62; Comcast and Indeed continue to ~2611 | 2412.16–2445.94 and 2497.32–2611.55; omitted middle is demonstrably commercial |
| Odd Lots 2 | Full midroll commercial sequence should cover spaces between fragments near 2411–2648 (requires final targeted recheck) | Fragments 2410.95–2437.96, 2446.14–2469.12, 2534.02–2648.28 |

Freakonomics ordinary Madison quotation and Planet Money Yemeni-bodega interview must remain excluded. The Rest Is Politics has no matching sponsor/promo search hits in the inspected take, but identical downloads prove only no observed DAI variation, not ad-free status.

## Builder findings communicated during development

1. `exclusion` originally treated sparse phrase `and` as a substring, dropping genuine Freakonomics ads. Sparse fragment exclusions must be exact, ideally time-localized.
2. `refine` originally failed to initialize clusters, producing singleton fallback groups and rejecting consensus for all three-take episodes. This was seen fixed in the next source snapshot.
3. Boundary uncertainty originally compared endpoints to median starts, inflating uncertainty by pod duration. This was seen fixed in the next source snapshot.
4. `shared_match` returns one best occurrence. Repeated identical creatives elsewhere in the same episode, including closing Ramp, need enumeration or explicit audits; one shared match is not completeness evidence.
5. `explicit_candidates` requires each succeeding sentence to contain a brand/promo cue. Ordinary sentences inside ad copy fail that gate, causing incomplete creative labels. Sponsor seed detection and boundary inference must be separate.
6. Fixed +/-90s same-time search windows can miss corresponding content after accumulated ad-duration shifts. Such fallback must remain uncertain, not be called a verified boundary.
7. Merged pods inherit the first candidate's provenance/boundary basis while sub_creatives retain IDs. Per-boundary evidence should be read via provenance rather than interpreting the merged first-source field as evidence for both extremes.

## Evaluation interpretation

V2 can support exploratory comparison after sentinel fixes, but cannot establish independent +/-10s accuracy or 0.9/0.9 success. It contains transcript-derived pseudo-labels; semantic detectors consume the same ASR evidence. A future-review holdout is not independently audited gold. Agreement between sibling audio alignments is also not calibrated absolute boundary uncertainty.

The maximal-contiguous-pod definition needs an explicit policy for quiet gaps and transitions. A fixed three-second transcript merge threshold splits the Freakonomics closing sequence at 4–6s inter-creative gaps even though no editorial material intervenes. Decide whether such gaps remain inside a commercial break before interpreting interval precision/recall. This affects the number and boundaries of targets, not just annotation wording.

Report all-label results alongside an uncertainty-stratified view; do not drop hard/uncertain labels and present the remainder as corpus recall. Show-level/take-grouped splits prevent sibling leakage, but a lexicon tuned after inspecting all held-out episodes still makes this exploratory, not pristine unseen-show evidence. Frozen feature/label artifacts and a future independent set are needed for a credible final estimate.

## Recheck of 22:02 artifact (intermediate)

The seven exclusions now correctly contain the three Madison/bodega false-positive pairs plus one sparse Revisionist fragment, not genuine Freakonomics ads. Hard Fork take0 now has all five expected intervals. Freakonomics take0 contains both audited full commercial sequences. Thirty takes are present. The builder corrections therefore materially improve the initial snapshot.

Remaining sentinels sent to the label author:

- Odd Lots take0: ChatGPT speech 2536.48–2573.20 bridges labels ending 2538 and starting 2567.75. Omitting this interior segment contradicts the whole-pod definition.
- Odd Lots take2: first midroll consists of Hills nutrition (~1007–1036.53), Underdog (1038.31–1106.31), ChatGPT (1106.63–~1143). Labels at 1006.97–1046.85 and 1055.07–1078.05 miss the final minute and fragment Underdog.
- Odd Lots take2: closing audio refinement starts 3734.50 instead of original 3703.16 with declared 2s uncertainty. ASR segment 3703.46–3739.36 has only a short opening ad phrase across 36 seconds, so ASR cannot settle actual onset. Preserve a disagreement flag/range rather than interpreting sibling alignment agreement as 2s absolute accuracy.

No conclusion of audio correctness follows from these changes. Hand-selected transcript corrections are suitable research targets, not an independent accuracy benchmark.

Additional direct word-timestamp sentinel: Hard Fork closing Ramp appears in **all** takes. Take1 first words `Most companies` start 2677.40, ending 2708.64; take2 start 2675.97, ending 2707.35. Both closing intervals were absent from the 22:02 snapshot. These are transcript-reviewed sibling times, not inferred copies of take0. Odd Lots take2 first-midroll ChatGPT ends 1143.27, immediately followed by editorial material. Corrections sent to author.

## Byte-gap extreme sentinels requested by coordinator

These checks inspect the actual transcript around large exact-byte gap extensions; they do not independently validate byte alignment or acoustic onset.

- Dan Snow take2: full commercial speech pod **1556.56–1725.04**. Explicit TikTok sponsorship starts 1556.56; TikTok copy leads into Progressive around 1600, ending 1626.13. Grow Therapy occupies 1626.47–1695.17, including offer and URL. Lowe's follows 1695.17–1725.04. Rasputin editorial discussion resumes 1732.80. Thus byte gap approximately1600–1726 is semantically valid commercial material and the ~1596–1622 seed is badly truncated. However the byte gap itself excludes the shared TikTok prefix, demonstrating why byte gaps cannot define full pods alone.
- The Daily take0: full commercial speech pod **714.91–812.01**. Capital One begins 714.91, USA for UNHCR begins within mixed segment743.19–748.33 and ends777.31, eTrade runs777.73–812.01. Editorial Nvidia discussion resumes814.52. The ~715–812 byte gap is corroborated; the ~717–740 seed covers only part of the first creative.

## Unseeded byte-scan candidate review

All five additionally requested uncovered regions contain advertising/promotion:

- Planet Money take0: explicit HubSpot sponsorship, 0.11–15.81; show intro follows.
- Planet Money take2: US Bank, 0–14.84; show intro starts15.93.
- The Daily takes1/2: Capital One, 0–24.50; editorial intro starts26.10.
- Odd Lots take1: full first midroll begins **956.78**, with a Bloomberg/Dubai podcast promo ending1016.28. Underdog1017.22–1047.56 and ChatGPT1047.80–1084.34 follow, then Indeed to approximately1145.32. Sponsor disclosure at1011.54 identifies the preceding virtual-assets material as a branded promo. The uncovered byte gap1020–1050 identifies only one portion of this longer commercial break. The complete pod should include the preceding promo under the chosen cross-podcast-promo-positive definition.

These findings show that seed-centered audio refinement alone leaves whole advertisements absent. An unseeded difference scan plus semantic review is a useful additional completeness check, but shared promos still require transcript inspection.

## Final uncovered-gap sweep (before final rebuild)

I compared every directed-pair dynamic-scan interval with the current v2 union. All residuals over 10 seconds were the previously reported cases or the following; no additional 10–15 second residuals were present. This is a coverage-triggered sentinel sweep, not a review of every sub-10-second edge.

- Revisionist History take2 full preroll0–140.66: ChatGPT0–27.64, Progressive27.64–about51, Apple Card paid message about51–140.66. Editorial begins141.08. The intermediate label96.5–139.5 misses most of the pod.
- Dan Snow take0 full midroll1527.16–1654.73: shared TikTok prefix to1566.58, Verizon1570.97–1600.81, Progressive1601.17–1624.75, Lowe's1626.91–1654.73.
- Dan Snow take1 later pod2340.59–2439.31: Verizon followed by Grow Therapy; editorial resumes2446.69.
- Planet Money take1 full pods274.67–approximately337 (Schwab/eTrade/Odoo),1099.13–1161.99 (same),approximately1600–1649.49 (Hartford/Capella). Sponsor-announcement boundaries may begin several seconds earlier under the chosen transition policy.
- Dan Snow take2 changed bytes3146–3176 remain **unresolved**. ASR segment3124.98–3172.43 contains only sparse editorial-outro wording; Apple Card is definite3176.21–3225.60. Do not interpret a changed byte region without semantic evidence as verified advertisement.

Class-definition check: Dan Snow take2 has an own-service History Hit subscription advertisement3228.19–3256.39 directly after Apple Card. It promotes documentaries and asks listeners to subscribe. Under the stated commercial-or-promotional definition it belongs to the closing pod; if own-service promos are excluded, this exception must be explicit and applied consistently. Ordinary requests to follow/rate an episode can be distinguished from a substantive product subscription advertisement, but this must be a written rule. The unresolved missing-speech region before3176 is a separate issue.

## Final 93-pod build recheck

All directed whole-file changed-byte intervals are now covered to within 10s total residual, except the deliberately unresolved Dan Snow take2 changed outro region (~3146–3176; 28.9s uncovered). This coverage check cannot establish absence of shared ads.

Two final corrections were requested before freeze:

1. Revisionist History takes0/1 also have the same ChatGPT0–27.64 and Progressive27.64–about51 prefix confirmed in take2. Their51.36–91.8 preroll must begin0.
2. Daily take1 labels712.04–734.90 and741.20–793.51 must be merged. The6.3s gap contains Capital One wallet/terms copy through739.50, then Starbucks sponsorship beginning739.94; it is not editorial material.

Every earlier targeted sentinel is corrected in this build, including all three Hard Fork closing Ramp ads; the Odd Lots whole breaks; Dan Snow and Daily byte-extreme pods; and Planet Money/Daily missing ads. Annotation definition now explicitly includes cross-podcast and clear own-show subscription promos, excludes ordinary editorial/outros, and treats3s as a candidate merge heuristic rather than the semantic pod definition. Dan Snow closing labels include the History Hit subscription promo.

Verdict: suitable for exploratory 10s evaluation after the final two fixes, with visible uncertainty/provenance and the unresolved outro listed. Not suitable as independent audio-verified gold, and no 0.9/0.9 acceptance claim is warranted from this set. This remains a bounded targeted audit with no listening; shared ads outside inspected sentinels and sub-10s edges may remain.

## Final freeze confirmation

The final build contains **92 pods across 10 episodes/30 takes**. Independently re-read and confirmed Revisionist History takes0/1 begin0 and end91.8, and Daily take1 has one712.04–793.51 pod. Both final blockers are resolved. No additional blocking finding remains within this bounded audit.

Final checked ads_v2.json SHA-256: `d6a5279dbacea5d78e8735028943fb8940150486f27b125ad7144eb67ff1c2be`.

Use this frozen version for exploratory evaluation. Remaining limitations are the unresolved Dan Snow sparse outro, uncalibrated algorithmic uncertainty, possible uninspected shared ads, and absence of human audio verification. The historical sections above document intermediate findings and are superseded by this confirmation for fix status.
