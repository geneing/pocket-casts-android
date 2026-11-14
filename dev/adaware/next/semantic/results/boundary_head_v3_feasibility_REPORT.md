# Fold-safe entry/exit boundary heads

Status: incomplete feasibility run; v3 is prediction-triggered and exploratory.  
V3 SHA256: `2c600b4e798c07b3afd8fb66bc64427953c3c9db3e7d537e59a626ab62a78870`  
Outer folds: 1/10; all take variants remain in their episode fold.

## Strict 10-second interval results

| Method | TP/FP/FN | Precision/Recall | Frame Precision/Recall/AP |
|---|---:|---:|---:|
| TF-IDF baseline | 4/6/5 | 0.400/0.444 | 0.978/0.675/0.829 |
| TF-IDF + boundary heads | 5/6/4 | 0.455/0.556 | 0.981/0.787/0.829 |

## Per-episode paired results

| Episode | Baseline TP/FP/FN, P/R | Boundary head TP/FP/FN, P/R | ΔTP |
|---|---:|---:|---:|
| 99-invisible__perfectly-leveraged-for-the-squat | 4/6/5, 0.400/0.444 | 5/6/4, 0.455/0.556 | +1 |

## Overlap and grouping diagnostics

- Baseline: any-overlap recall 0.889; IoU≥0.5 recall 0.667; overlap-matched edge misses 4; gold without overlap 1; split gold pods 2; merged predictions 0.
- Boundary head: any-overlap recall 1.000; IoU≥0.5 recall 0.667; overlap-matched edge misses 4; gold without overlap 0; split gold pods 2; merged predictions 0.

## Fold choices and compute

- 99-invisible__perfectly-leveraged-for-the-squat: λ=1; inner λ scores=[(0.0, 0.351, 0.293), (1.0, 0.357, 0.38), (2.0, 0.185, 0.272)]; λ=0 baseline intervals reproduced: True.
- Base model fits: 13 (cache hits 1); wall time 120.2s; contextual feature width 786 (18 standardized scalars plus 768 normalized MiniLM dimensions).

## Method and limits

Two C=0.1 logistic heads score candidate starts and ends from 15-second left/right context. The base TF-IDF probabilities and decoder settings match the frozen v3 baseline; only λ∈{0,1,2} was tuned on nested grouped validation. Boundary heads train only on inner-OOF base scores. Scalar scaling, edge targets, heads, and λ selection never see the outer held-out episode. The λ=0 check must exactly reproduce baseline intervals. Boundary-head inner folds balance episode counts; the frozen TF-IDF decoder tuning used the baseline's own grouped split protocol, which may balance segment counts differently.

V3 was created after reviewing predictions from an earlier detector. These results are prediction-triggered and exploratory, not independent audio-grounded acceptance metrics. No audio refinement was run.
