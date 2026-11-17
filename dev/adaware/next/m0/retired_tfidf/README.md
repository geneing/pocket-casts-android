# Retired: M0 TF-IDF ad detector

These files were retired on branch `feature/adaware_jev`. The ad detector is now the `jev`
classifier served by the OpenCode System One API, so the TF-IDF training/export path is no
longer run. `next/m0/corpus` stays as the labelling and evaluation corpus for `jev`.

What was here:

- `train.py` — fitted the word 1-2 gram + `char_wb` 3-5 gram TF-IDF vectorizer and the
  balanced logistic classifier on the M0 corpus, tuned the semi-Markov pod decoder on
  episode-grouped inner folds, scored held-out shows, and exported `bundle/model.json`
  plus Kotlin parity fixtures (`bundle/fixtures.json`). Its evaluation output landed in
  `next/m0/eval/`.
- `publish.py` — retrained via `train.py` and copied `model.json` / `fixtures.json` into
  `modules/services/repositories` (assets + test resources) for the Android port.
- `finish.sh` — waited for a running collector, then ran `merge_legacy.py` and
  `publish.py` as one unattended pipeline.

The files are moved here intact and are not maintained. Relative paths inside them
(`HERE`, `HERE.parent`, `next/semantic`, `next/eval`) were correct at their old location,
so they do not run from this directory. **The runnable version is in git history** (last
commit that had them under `next/m0/`: `db361061d`).

Still live in `next/m0/`: `collect.py` (download + transcribe + label derivation),
`merge_legacy.py` (folds the older v3 corpus into `corpus/`), `manifest_stats.py` (corpus
stats), `eval/` (the last TF-IDF run's report, kept as a historical record), and
`corpus/`. Nothing outside this directory imports the three files above.
