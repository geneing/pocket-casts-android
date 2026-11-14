# Independent boundary-head code QA

Reviewed boundary_head.py, its detector integration, target construction, nested producer graph, lambda selection and output schema. No semantic code edited.

## Findings requiring fixes before a completed run

1. **JSON serialization blocker.** fit_heads stores build_targets metadata excluding only indices; this retains NumPy arrays for labels and weights. detector.jsonable does not convert ndarrays. Confirmed with a small direct call: json.dumps(detector.jsonable({'weights': np.array([1.0])})) raises TypeError: Object of type ndarray is not JSON serializable. Omit raw arrays from reporting metadata (counts and producer identities suffice) or explicitly convert arrays. Without this, a long successful fitting run fails at final output.
2. **Report schema mismatch.** main stores baseline as the flat baseline_score dictionary (TP/FP/FN plus frame and overlap_diagnostics), while report_text accesses baseline['interval']. The boundary_head wrapper stores interval=boundary_score, with frame/overlap_diagnostics nested in that score, but report_text accesses head['frame'] and head['overlap_diagnostics']. These accesses will raise KeyError. Use one consistent normalized report view; test report generation on a realistic minimal payload without retraining.
3. **Partial-run table presentation.** report_text iterates protocol.episodes, filling unevaluated episodes with0/0/0 and0.000 P/R. The headline says incomplete, but the rows still resemble measured outcomes. Iterate evaluated_outer_episodes or mark unrun rows explicitly. This does not inflate the actual strict aggregate, which correctly restricts references and predictions to evaluated folds.

## Leakage and fairness checks

- Outer test episode is excluded from outer_train and all nested score/head producer sets.
- Each inner validation group V gets base scores from A only. Boundary heads use further3-fold base predictions generated strictly inside A; the crucial stacked-model validation leakage is avoided.
- Target construction and scalar scaler fitting see only head-training rows. Frozen embeddings remain label-free. All takes stay together.
- Final heads train on outer-training OOF scores, and the final base classifier trains on outer-training episodes only before predicting the outer test.
- Lambda is selected using pooled inner validation outcomes, with precision then lower-lambda tie breaks. It does not use the outer test score. All lambda test predictions are retained diagnostically; they must not be used later to choose a different per-test winner.
- Lambda0 uses the same recomputed base classifier and frozen baseline decoder parameters. Exact interval equality against the saved baseline is asserted per take. This is a strong and fair baseline check.
- The new grouped_episode_splits balances numbers of episode names, whereas detector.grouped_predictions originally balances segment-row counts. Thus inner grouping differs from the baseline's tuning grouping. This is not outer-test leakage and does not invalidate the exact lambda0 comparator, but record the difference; do not describe the inner folds as identical.
- The existing scorer still requires both edges within10s, one-to-one. All selected-fold references remain in the denominator, including uncertain ones. Strict totals are not inflated by partial folds.
- Boundary frame AP uses unchanged base segment probabilities, so identical AP is expected and is not a metric of improved boundary evidence. Frame P/R correctly uses the changed decoded masks.

## Additional checks and limits

Read-only corpus check found no nonmonotone segment end times or midpoints in the30 transcripts, so the searchsorted assumption in boundary target/context lookup holds on this corpus. Frozen v3 label hash is verified and compared to the baseline hash. Current transcript/code hashes are recorded; exact lambda0 assertions guard behavioral drift. No result-quality claim should precede successful full-fold execution.

Overall: no identified label leakage or unfair lambda0 comparison; fix the output/report blockers and clearly present partial-run rows. The method remains exploratory because v3 was prediction-triggered transcript-audited and the dataset has only10 independent episodes.
