# One bounded method: contextual entry/exit scores for the existing decoder

## Decision and evidence

Keep the existing TF-IDF segment classifier and semi-Markov pod decoder. Add two small regularized logistic heads that score whether a candidate edge is an ad start or an ad end. Add their scores to the decoder's interval score. Do not learn a universal timestamp offset and do not add another silence snap.

V2 diagnostics motivate this hypothesis: TF-IDF touched90/92 labeled pods, but only34 passed both-edge10s matching;24 best-overlap starts were over10s late and22 over10s early;19 ends were over10s early and10 over10s late. Only8 pods were split. These mixed directions argue for contextual edge selection, not a constant shift or a larger fixed merge gap. Some errors were reference defects now corrected in v3, so the implementation decision should use the frozen v3 result.91/92 v2 pods had representable raw segment edges within10s; finer timing units are not the first experiment.

## Candidate geometry and features

Use exactly the current decoder's segment starts and ends. No gold-centered candidate windows at inference. START at segment i uses the split immediately before i; END at segment i uses the split immediately after i. Never cross take boundaries when constructing context.

For each candidate, form left/right windows comprising segments whose midpoints lie in the preceding/following15 seconds. Empty sides use zeros plus an explicit missing-side flag. Features:

1. Prior-restored TF-IDF probability summaries on each side: nearest probability, duration-weighted mean, minimum, maximum; right-minus-left mean and nearest-probability differences. Clip logits to[-6,6] if logits replace probabilities; choose probabilities for the first implementation.
2. Local timing: gap between immediately adjacent segments clipped to[0,30] seconds; adjacent segment durations clipped to[0,30]; left/right observed speech duration within the15s windows; missing-side flags.
3. Context meaning: mean frozen MiniLM **current-text** embedding on each side (384+384 dimensions), plus cosine similarity between the two nonempty means. Use the existing embedding cache's current-text half; do not use labels or neighbor-take information to build embeddings.

Standardize only the scalar features with statistics fit on the boundary-head training rows. L2-normalize each side's embedding mean; keep zero if empty. Do not add advertiser names, episode/show IDs, gold distances, byte-gap matches, or sibling-take information as features. Fixed contextual encoder is label-free; any future fine-tuning would need its own fold isolation.

## Boundary targets and heads

Build targets only from the selected training episodes' frozen v3 pods. For each gold start, mark the nearest candidate segment start positive if its distance is at most10s. For each gold end, do the same with segment ends. Break ties by earlier candidate time. Other candidates within10s of any gold edge of that head are ignored rather than labeled negative. All remaining candidates are negative. This permits plausible nearby alternatives without teaching them as definite errors. If an edge has no candidate within10s, omit that positive and record it; all evaluation references still remain in evaluation.

Train START and END separately with LogisticRegression(C=0.1, penalty='l2', solver='liblinear', class_weight='balanced', max_iter=250, fixed random seed). Fix C for this single bounded experiment. Downweight each positive by1/(1+uncertainty_seconds/10); this is a heuristic training weight, not a calibrated confidence probability. Negatives use weight1. Keep all negatives initially: corpus size is small enough and negative subsampling introduces another design choice. Group all takes of an episode in every split. Normalize episode total row weights to equal totals before applying class balancing, preventing a long show or extra takes from dominating.

Use the balanced head's decision_function as evidence, clipped to[-4,4]. It is not a calibrated posterior. Do not apply the segment model's prior-restoration formula to these evidence scores.

## Exact decoder integration

The existing candidate interval score is:

    best[start_i] + sum(segment_emissions[start_i:end_i])
    - boundary_penalty - duration_weight * 0.5 * log(duration / 45)^2

Add:

    lambda_edge * (start_evidence[start_i] + end_evidence[end_i - 1])

Keep current8–300s duration limits,12s hard transcript-gap rule, emission weighting and post-merge behavior unchanged for this experiment. This isolates the effect of contextual edges. A hard gap can still prevent a true pod; report such cases instead of silently relaxing the rule after seeing test outputs. Candidate edge scores can change both selected boundaries and pod count, unlike a post-hoc timestamp shift.

Use lambda_edge in {0,1,2}. Lambda0 is the exact baseline fallback. Retain the baseline decoder parameters selected from its existing inner-fold grid; then choose only lambda_edge from inner validation predictions using the same strict both-edge10s F1, precision tie-break, and lower-lambda final tie-break. Do not enlarge the original decoder grid or jointly search new durations/gaps in this experiment.

## Split recipe: avoid stacked-model leakage

For each outer held-out episode T, let O be the other episodes. The outer test labels must not participate anywhere below.

For each existing inner validation group V within O:

1. Let A=O minus V. Fit the base TF-IDF model on A and predict V. Vocabulary, IDF and observed prevalence come only from A.
2. To train the boundary heads on A, generate base score features for A with a further grouped3-fold cross-fit **inside A only**. Fit each submodel on A minus its subvalidation group and predict that group. Cache by exact training-episode set, label hash and base-model configuration.
3. Fit scalar scaling and the two boundary heads on A's cross-fitted features and A's boundary targets. Predict boundary evidence on V using V's base scores from step1 and its own transcript context.
4. Store V base and edge scores. Pool the disjoint V predictions to select the original decoder parameters and lambda_edge, using only O labels.

For final outer prediction, generate grouped3-fold OOF base scores within O, fit the boundary heads/scaler on those scores and O targets, then fit the base model on all O and predict T. Apply the frozen heads and chosen decoder parameters to T once.

Do NOT train the boundary heads on in-sample base scores. Do NOT reuse an OOF base score for an inner-training row if that base model was trained on V; that leaks validation labels through the stack. Global final-OOF score caches are not automatically valid for inner training. Likewise, no scalar scaling, calibration, target construction or threshold selection can see T labels.

With10 episodes and3 inner folds, this is bounded at approximately130 base fits without cache reuse: per outer fold3*(1 validation model+3 sub-cross-fit models)+1 final model. The final boundary-head training OOF predictions can reuse the3 validation-model outputs. Boundary logistic fits and lambda sweep are small. Exact train-set caching can reduce repeated work. If runtime is excessive, report the budget issue rather than weakening isolation silently.

## Verification and reporting

- Lambda0 must reproduce the baseline intervals exactly for identical base scores/parameters.
- Assert train/validation/test episode sets are disjoint for every cached score producer; persist producer episode IDs.
- Record label, transcript and code hashes plus fold memberships and chosen lambda.
- Report raw strict TP/FP/FN and P/R on **all** references, frame coverage, best-overlap edge offsets, split/merge counts and each held-out episode. Uncertain references stay in the strict denominator.
- Compare paired episode outcomes against the same frozen-v3 baseline. An improvement here remains exploratory because v3 was prediction-triggered transcript-audited; it does not establish independent audio gold or general0.9/0.9 performance.
- Freeze after this one experiment. No test-specific offsets, advertiser additions, silence rules, gap changes or post-hoc choice of best per-show variant.
