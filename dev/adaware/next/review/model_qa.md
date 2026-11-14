# Independent semantic-model QA

## Evaluation design

Code reviewed: next/semantic/detector.py and next/eval/score.py. No direct held-out-label leakage found in the implemented split/tuning path. Outer evaluation holds one episode and all its takes out; inner GroupKFold uses only remaining episodes. TF-IDF vocabulary/IDF fit only training samples. Frozen pretrained MiniLM embeddings can be cached for every transcript without label leakage because the encoder is not trained on this corpus. Context windows stay within a take.

Strict scoring requires both start and end within10s and uses maximum-cardinality one-to-one matching. Unmatched predictions remain false positives, including predictions in no-ad episodes. Reference provenance breakdown does not improperly assign precision to a filtered subset: unmatched predictions remain separate. No strict-metric inflation bug was found.

Important limitations:

- Identical creatives occur across different shows. Show-held-out splitting does not measure unseen-creative generalization; repeated advertising copy can make lexical generalization look stronger than it is.
- Reference labels and detector input share Whisper transcripts. ASR omissions and boundaries can be correlated. Byte corroboration strengthens some boundaries but does not independently establish ad identity or full-pod completeness.
- All episodes were inspected during target repair and model-design iteration. Even nested CV does not remove researcher/model-selection reuse of this small corpus. Comparing TF-IDF, semantic embeddings and optional audio refinement is exploratory model selection; a fresh independent set is needed after choosing.
- There are10 independent episodes, not30 independent takes or92 independent gold events. Report episode-clustered uncertainty, not binomial confidence based on take count.
- Segment-midpoint labels and whole-segment decoder boundaries cannot accurately represent mixed editorial/ad segments or long sparse ASR segments. Label uncertainty exceeding10s further limits interpretation of strict scores.
- The output protocol should include input-label SHA256 and code revision/hash. File name alone does not establish that evaluation used the final frozen92-pod version.

## Next experiment, pending results

First distinguish semantic coverage failure from segmentation/boundary failure using interval overlap, frame coverage, split/merge counts, and edge offsets. If frame coverage is high but strict recall low, the highest-value bounded next step is a learned boundary/sequence layer on frozen contextual embeddings: word-timestamp or short fixed speech units, separate ad-entry/ad-exit evidence, and a decoder that retains non-cue sentences within a pod. Train and tune only inside each outer training fold. Do not keep optimizing global merge gaps against outer-test labels.

If large complete pods are absent despite clear sponsor/offer text, improve semantic representation or train a contextual sequence classifier before acoustic edge snapping. An8s silence snap cannot restore a missing60s creative or a shared pod omitted by the semantic classifier. Existing label QA concretely found such multi-creative omissions, so completeness and boundary inference must be diagnosed separately.

Results review pending; no measured performance claim yet.

## Measured segment-edge representability

Against frozen92-pod v2,91/92 references have at least one existing ASR segment start and one segment end within10s of the corresponding gold edges. One exception is Revisionist History take0 reference946.25–963.50, whose closest segment end is12.10s away. This is only a necessary-condition oracle, not an achievable classifier score, but shows that segment granularity alone is unlikely to explain a large corpus-level strict-recall deficit. Prioritize semantic classification and pod grouping diagnostics over wholesale timing-unit redesign unless measured errors indicate otherwise.

## Frozen v2 results and prediction-triggered audit

MiniLM: strict P/R0.132/0.185 (17TP/112FP/75FN), frame P/R/AP0.594/0.619/0.470. TF-IDF: strict P/R0.279/0.370 (34TP/88FP/58FN), frame P/R/AP0.710/0.814/0.602. Quiet-window snapping worsened both: MiniLM0.116/0.163 and TF-IDF0.254/0.337.

The independent diagnostic script reports TF-IDF touches90/92 reference pods, covers at least80% of64 pods, splits8 pods, and has median worst-edge error21.375s among overlapping targets.25/122 predictions have zero v2 overlap. MiniLM touches80/92 pods, covers at least80% of36, and has41/129 zero-overlap predictions. These are reference-relative counts, not true-error rates.

A bounded review of the16 longest predictions having less than60% v2-ad overlap uncovered more shared ads missing from v2. In99% Invisible takes1/2, the supposed104.63s end overshoot includes genuine LLBean/Acura/Progressive spots. Entire99% Invisible and Freakonomics prerolls are missing. First midrolls in Freakonomics and Revisionist History remain partial. A v3 correction list was sent to the author:

-99% Invisible all takes preroll0.24–63.38; midroll take0 1496.54–1627.51, takes1/2 1496.56–1626.69.
- Freakonomics all takes preroll0.78–90.74; first midroll takes0/2 1395.85–1534.29, take1 1395.85–1517.41.
- Revisionist History first midroll take0 start882.30 with uncertain existing end963.50; take1 882.30–978.71; take2 start931.91 with uncertain existing end1035.35.
- Planet Money dedicated newsletter signup promos, under coordinator's consistent-promo policy: take0 1563.83–1586.15, take1 1537.62–1559.94, take2 1540.37–1562.69. Preceding coffee-business discussion remains editorial.

This bounded list is complete. Preserve v2/hash/results as an exploratory checkpoint. v3 must record prediction-triggered transcript review, not independent held-out verification. Complete changed-byte coverage cannot establish shared-ad completeness.

## Boundary hypothesis

Against v2 best-overlap predictions, TF-IDF starts are more than10s late in24 cases and more than10s early in22; ends are more than10s early in19 and more than10s late in10. This mixed sign pattern rules against a universal start/end offset. Counts will change after v3, because some apparent offsets are missing reference ads.

If the coverage-versus-strict gap persists after v3, retain the stronger TF-IDF classifier and test a train-fold-only contextual entry/exit classifier. Classify candidate segment edges as ad-entry, ad-exit or neither using adjacent text/context embeddings, classifier-score change and timestamp gap. Derive training boundaries only from outer-training labels, generate training score features via inner out-of-fold predictions, tune only inner folds, and feed learned entry/exit scores into the current semi-Markov decoder. This directly models transitions rather than assuming quiet points mark ads.

The experiment is bounded and does not require a full new ASR system. TF-IDF already touches90/92 v2 pods; only8 are split, so a larger fixed merge gap alone is unlikely to suffice. Audio snapping worsened scores. Word-timestamp candidates may help the few mixed ASR segments, but91/92 v2 pods already have representable segment edges within10s. This is a testable hypothesis, not a claim that0.9/0.9 is attainable. Independent labels and more shows remain necessary for acceptance.

## V3 bounded correction verification

Re-read generated ads_v3.json after the label author rebuilt it. All requested shared preroll/midroll additions are present. Planet Money newsletter intervals match exactly in all takes. Revisionist History sparse midroll tails retain20s uncertainty. Freakonomics takes1/2 preroll ends91.38 rather than90.74 due to a small merged-source extension; this0.64s difference is not blocking. No further additions from this bounded review. Use a frozen v3 rerun before attributing remaining strict failures to the model.
