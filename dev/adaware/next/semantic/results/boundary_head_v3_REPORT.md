# Fold-safe entry/exit boundary heads

Status: complete 10-episode LOEO; v3 is prediction-triggered and exploratory.  
V3 SHA256: `2c600b4e798c07b3afd8fb66bc64427953c3c9db3e7d537e59a626ab62a78870`  
Outer folds: 10/10; all take variants remain in their episode fold.

## Strict 10-second interval results

| Method | TP/FP/FN | Precision/Recall | Frame Precision/Recall/AP |
|---|---:|---:|---:|
| TF-IDF baseline | 39/57/62 | 0.406/0.386 | 0.915/0.760/0.798 |
| TF-IDF + boundary heads | 43/63/58 | 0.406/0.426 | 0.912/0.789/0.798 |

## Per-episode paired results

| Episode | Baseline TP/FP/FN, P/R | Boundary head TP/FP/FN, P/R | ΔTP |
|---|---:|---:|---:|
| 99-invisible__perfectly-leveraged-for-the-squat | 4/6/5, 0.400/0.444 | 5/6/4, 0.455/0.556 | +1 |
| dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 1/11/11, 0.083/0.083 | 1/11/11, 0.083/0.083 | +0 |
| freakonomics-radio__694-does-democracy-really-need-elections | 9/3/3, 0.750/0.750 | 10/5/2, 0.667/0.833 | +1 |
| hard-fork__jensen-huang-is-out-at-nvidia | 6/8/9, 0.429/0.400 | 5/10/10, 0.333/0.333 | -1 |
| odd-lots__what-nvidia-s-megaspeed-scandal-is-reall | 1/10/10, 0.091/0.091 | 2/10/9, 0.167/0.182 | +1 |
| planet-money__how-bodegas-led-to-the-yemeni-coffeehous | 9/2/6, 0.818/0.600 | 10/2/5, 0.833/0.667 | +1 |
| revisionist-history__from-the-big-dig-mitt-ted | 2/8/7, 0.200/0.222 | 3/10/6, 0.231/0.333 | +1 |
| the-daily__what-jensen-huang-s-resignation-will-mea | 7/6/5, 0.538/0.583 | 7/6/5, 0.538/0.583 | +0 |
| the-economics-show-with-soumaya-keynes__can-economics-fight-fascism-with-isabell | 0/3/6, 0.000/0.000 | 0/3/6, 0.000/0.000 | +0 |
| the-rest-is-politics__what-living-under-isis-taught-me-about-e | 0/0/0, 1.000/1.000 | 0/0/0, 1.000/1.000 | +0 |

## Overlap and grouping diagnostics

- Baseline: any-overlap recall 0.891; IoU≥0.5 recall 0.713; overlap-matched edge misses 51; gold without overlap 9; split gold pods 5; merged predictions 2.
- Boundary head: any-overlap recall 0.911; IoU≥0.5 recall 0.713; overlap-matched edge misses 49; gold without overlap 7; split gold pods 10; merged predictions 2.

## Fold choices and compute

- 99-invisible__perfectly-leveraged-for-the-squat: λ=1; inner λ scores=[(0.0, 0.351, 0.293), (1.0, 0.357, 0.38), (2.0, 0.185, 0.272)]; λ=0 baseline intervals reproduced: True.
- dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with: λ=0; inner λ scores=[(0.0, 0.494, 0.427), (1.0, 0.408, 0.449), (2.0, 0.226, 0.315)]; λ=0 baseline intervals reproduced: True.
- freakonomics-radio__694-does-democracy-really-need-elections: λ=1; inner λ scores=[(0.0, 0.378, 0.315), (1.0, 0.355, 0.371), (2.0, 0.242, 0.36)]; λ=0 baseline intervals reproduced: True.
- hard-fork__jensen-huang-is-out-at-nvidia: λ=1; inner λ scores=[(0.0, 0.421, 0.372), (1.0, 0.388, 0.442), (2.0, 0.273, 0.384)]; λ=0 baseline intervals reproduced: True.
- odd-lots__what-nvidia-s-megaspeed-scandal-is-reall: λ=1; inner λ scores=[(0.0, 0.427, 0.356), (1.0, 0.394, 0.433), (2.0, 0.292, 0.422)]; λ=0 baseline intervals reproduced: True.
- planet-money__how-bodegas-led-to-the-yemeni-coffeehous: λ=1; inner λ scores=[(0.0, 0.325, 0.314), (1.0, 0.309, 0.337), (2.0, 0.14, 0.221)]; λ=0 baseline intervals reproduced: True.
- revisionist-history__from-the-big-dig-mitt-ted: λ=1; inner λ scores=[(0.0, 0.372, 0.315), (1.0, 0.358, 0.37), (2.0, 0.233, 0.326)]; λ=0 baseline intervals reproduced: True.
- the-daily__what-jensen-huang-s-resignation-will-mea: λ=0; inner λ scores=[(0.0, 0.316, 0.27), (1.0, 0.268, 0.292), (2.0, 0.176, 0.247)]; λ=0 baseline intervals reproduced: True.
- the-economics-show-with-soumaya-keynes__can-economics-fight-fascism-with-isabell: λ=0; inner λ scores=[(0.0, 0.378, 0.358), (1.0, 0.333, 0.379), (2.0, 0.229, 0.337)]; λ=0 baseline intervals reproduced: True.
- the-rest-is-politics__what-living-under-isis-taught-me-about-e: λ=1; inner λ scores=[(0.0, 0.326, 0.287), (1.0, 0.327, 0.356), (2.0, 0.233, 0.337)]; λ=0 baseline intervals reproduced: True.
- Base model fits: 65 (cache hits 40); wall time 955.8s; contextual feature width 786 (18 standardized scalars plus 768 normalized MiniLM dimensions).

## Method and limits

Two C=0.1 logistic heads score candidate starts and ends from 15-second left/right context. The base TF-IDF probabilities and decoder settings match the frozen v3 baseline; only λ∈{0,1,2} was tuned on nested grouped validation. Boundary heads train only on inner-OOF base scores. Scalar scaling, edge targets, heads, and λ selection never see the outer held-out episode. The λ=0 check must exactly reproduce baseline intervals. Boundary-head inner folds balance episode counts; the frozen TF-IDF decoder tuning used the baseline's own grouped split protocol, which may balance segment counts differently.

V3 was created after reviewing predictions from an earlier detector. These results are prediction-triggered and exploratory, not independent audio-grounded acceptance metrics. No audio refinement was run.

## Verification

Final local test run: `pytest -q dev/adaware/next/semantic/tests` — 11 passed in 0.91s. The JSON was round-trip checked after compaction; all 10 folds, aggregate scores, paired episode outcomes, selected predictions, and λ=0 baseline equality checks were retained.
