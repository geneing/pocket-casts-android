# Offline ad interval research: current result

## Decision

The requested precision and recall of 0.90, with both pod boundaries within
10 seconds, has **not** been demonstrated. The best completed single-take
detector is a transcript-context TF-IDF classifier with a duration-aware pod
decoder and separately trained start/end scorers. Against the versioned v3
reference set it reaches 0.406 precision and 0.426 recall on the strict
interval metric. V3 was repaired after
examining earlier predictions, so even this result is exploratory. The
reference boundaries are not independently audio-verified.

## What changed

- Rebuilt labels as maximal contiguous commercial or promotional pods, adding
  shared creatives, missing pre-rolls/post-rolls, whole multi-creative breaks,
  and dedicated own-show subscription promotions. An unseeded exact-byte scan
  of sibling MP3 downloads exposed omitted changed spans; transcript review
  extended those spans across byte-identical shared ads. The final v3 set has
  101 pods / 150.69 minutes in 30 takes from 10 episodes. The original set had
  103 often-fragmentary intervals / 102.8 minutes in only 27 take keys.
- Preserved v2 and v3 label files, builder scripts, audit reports, provenance,
  and SHA-256-bound evaluation snapshots. V3 marks 18 prediction-triggered
  corrections as unsuitable for independent accuracy claims.
- Built an uncertainty-first human review queue for all 67 v3 intervals whose
  estimated boundary uncertainty exceeds 10 seconds, plus short audio clips
  for the 10 widest cases. The clips have not been listened to here.
- Implemented a transcript-only single-episode detector. It takes timestamped
  Whisper-small-quality segments and never uses sibling takes at inference.
  It compares a small pretrained sentence encoder with a word/character
  TF-IDF baseline, using episode-grouped nested tuning and a semi-Markov pod
  decoder. Local low-energy audio edge snapping was tested on v2 and reduced
  strict accuracy.
- Tested a fold-safe contextual start/end scorer on top of TF-IDF. It uses
  left/right transcript context and TF-IDF scores, trains from inner-fold
  out-of-fold predictions, and tunes only a three-value edge weight inside
  each outer training set. The zero-weight output exactly reproduced the
  baseline in all ten folds.

## Measured results

A strict match requires one-to-one pairing with **both** start and end within
10 seconds. The 1-second frame metrics and overlap metrics are diagnostics,
not substitutes for the strict criterion.

| Labels | Model | Strict TP / FP / FN | Strict P / R | Frame P / R / AP | Any-overlap P / R |
|---|---|---:|---:|---:|---:|
| v2, 92 pods | Sentence encoder | 17 / 112 / 75 | 0.132 / 0.185 | 0.594 / 0.619 / 0.470 | See v2 report |
| v2, 92 pods | TF-IDF | 34 / 88 / 58 | 0.279 / 0.370 | 0.710 / 0.814 / 0.602 | See v2 report |
| v3, 101 pods | Sentence encoder | 28 / 91 / 73 | 0.235 / 0.277 | 0.717 / 0.678 / 0.655 | 0.723 / 0.851 |
| v3, 101 pods | TF-IDF | **39 / 57 / 62** | **0.406 / 0.386** | **0.915 / 0.760 / 0.798** | **0.938 / 0.891** |
| v3, 101 pods | TF-IDF + start/end scorers | **43 / 63 / 58** | **0.406 / 0.426** | **0.912 / 0.789 / 0.798** | **See boundary report** |

On v3, TF-IDF overlaps 90 of 101 reference pods but matches only 39 on both
boundaries. Fifty-one overlap-matched pairs miss at least one 10-second edge.
Nine reference pods have no overlapping prediction, five are fragmented by
multiple predictions, and two predictions span multiple pods. Median absolute
edge errors among overlap matches are 6.9 seconds at the start and 4.2 seconds
at the end; those medians hide the longer tail that breaks the strict score.
The start/end model adds four strict matches and six predictions. It raises
overlap recall from 0.891 to 0.911 but doubles fragmented gold pods from five
to ten. It took 65 base TF-IDF fits and about 956 seconds on this Windows host.

## Interpretation and limits

- V3 contains 67 intervals with declared boundary uncertainty over 10 seconds.
  Exact strict P/R against those intervals is a useful development signal,
  not a valid acceptance measurement at the requested tolerance.
- Ad identity in v3 is principally transcript-derived. A detector reading
  transcripts can agree with these labels for correlated reasons. V3 also
  incorporates a bounded review of apparent v2 false positives, so its model
  comparison is prediction-triggered. These 10 episodes are only 10
  independent show-level test units despite 30 takes.
- The sentence encoder's ONNX files occupy about 87 MiB and took 173 seconds
  to encode 17,591 segments on the Windows CPU here. Android runtime, memory,
  battery, and the phone's STT quality are unmeasured. TF-IDF currently works
  better and is a simpler deployment candidate, but neither model meets the
  interval requirement.
- A small additional NPR episode was downloaded twice as an acquisition test.
  Both files were byte-identical, so it did not add training labels or an
  independent evaluation case.

## Next evidence needed

Have reviewers listen to and time at least the uncertain v3 boundary queue,
then freeze an independent set of new episodes before selecting a model.
The contextual start/end experiment has been completed; its modest gain does
not close the strict interval gap. Further model tuning on these same ten
episodes would compound selection bias before the reference set is verified.

## Implementing this method on an Android phone

### Training answer and deployment status

**Do not train on the phone. Additional offline training is required before
this research code can run as a fixed app model.** The saved evaluations contain
one fitted model per held-out fold; they do not contain a single trained
TF-IDF vocabulary, base classifier, pair of boundary classifiers, or one
chosen decoder configuration for deployment. No Android model exporter or
transcription engine was built in this experiment. The Python detector and
boundary-head scripts are the reference implementation, not Android code.

There are two separate training needs:

1. **Technical deployment fit:** Once the data and parameters are frozen,
   fit one final base TF-IDF model on the selected training episodes; generate
   episode-grouped out-of-fold base scores within those training episodes;
   train the start and end heads on those scores; choose one global decoder
   setting and edge weight using training-only grouped validation; and export
   all inference parameters. This fit can use the current data to build a
   research prototype, but it has not been run or exported yet. Do not select
   a different fold model or decoder setting for each show at inference.
2. **Evidence needed for a usable detector:** Listen to and correct the 67
   v3 intervals with estimated boundary uncertainty above 10 seconds, collect
   more independently labeled episodes across shows and ad networks, and
   reserve new shows/episodes as an untouched acceptance set. Retrain and
   evaluate with the **actual phone STT output**. V3 was partly corrected by
   inspecting prior model predictions, so another score on v3 cannot certify
   0.90/0.90. If the phone STT changes segment text or timing, the model must
   be validated and likely retrained for that transcript distribution.

The frozen MiniLM text encoder used by the boundary heads is pretrained;
its weights were **not** trained here. The base TF-IDF logistic model and the
two small logistic heads are task-trained offline. On-device work is inference
only. The simpler TF-IDF-only option omits MiniLM and both boundary heads,
but its measured strict score is 0.406 precision / 0.386 recall, versus
0.406 / 0.426 with the heads.

For that final fit, reproduce `detector.build_labels`: a transcript segment
is a positive base-training row when its **midpoint** lies inside a labeled
pod. In each training-only group split, fit the TF-IDF vocabulary and base
classifier without the held-out episodes, then score those episodes to make
out-of-fold probabilities for boundary-head training. Fit the scalar scaler
and start/end heads only on these out-of-fold rows. Finally refit the base
vocabulary/classifier on all selected training episodes. This extra
cross-fitting is required for the heads; training them on the base model's
in-sample probabilities would produce a different, biased pipeline.

### 1. Provide a truly offline, timed STT input

Define an Android `OfflineEpisodeTranscriber` interface that accepts the
downloaded file and returns the entire episode as sorted
`(startMs, endMs, text)` segments in the **same audio timeline** used by the
player. The current evaluation used faster-whisper small segments. Similar
word recognition alone is insufficient: the classifier uses neighboring
segments, and the decoder uses their start/end timestamps as candidate pod
edges. A provider that returns only untimed text cannot reproduce this method.

- Verify the chosen phone STT engine can process an existing audio file with
  networking disabled, provide segment timestamps, and complete long episodes
  within acceptable memory, battery, thermal, and latency limits. Do not
  assume an OS speech service has these capabilities merely because it offers
  dictation. If it does not, supply a bundled offline STT engine or stop this
  pipeline cleanly.
- Decode the local file in bounded chunks and add each chunk's original file
  offset to its STT timestamps. Handle overlap between chunks without
  duplicating words. Preserve leading and trailing silence in the timeline;
  never compress time by removing silence. Sort segments, reject negative or
  reversed times, and verify they fit the decoded media duration.
- Finish transcription before classification because each segment's features
  include the following segment. Cache transcripts locally only if needed,
  keyed by the exact downloaded file identity and STT version. Do not send
  audio or transcripts to a server in this offline path.

### 2. Train and export one immutable inference bundle off-device

Implement an exporter under `dev/adaware/next/semantic/` after the labels
and training policy are frozen. The exporter should use the feature definitions
in `detector.py` and `boundary_head.py`, and record a bundle version, training
label hash, STT/normalization version, and independent validation result. It
must export at least:

| Component | Parameters the phone must load |
|---|---|
| Base text features | Ordered word 1–2 gram and `char_wb` 3–5 gram vocabularies, each vocabulary's IDF values, tokenization/Unicode rules, sublinear TF and L2 normalization rules, feature offsets. Current caps are 40,000 word and 20,000 character features, with `min_df=2` at training. |
| Base classifier | Logistic coefficients, intercept, class ordering, and the positive-segment prevalence used for prior restoration. Current fit uses `C=1.0`, balanced classes, and `liblinear`; the phone needs only the fitted values. |
| Boundary context | The same frozen `all-MiniLM-L6-v2` ONNX encoder and tokenizer used by the experiment, or a newly trained/validated replacement. The heads consume the **current-text** 384-value embedding of each transcript segment, not the base semantic classifier's context embedding. |
| Start/end heads | Each head's 18-scalar `StandardScaler` means/scales, 786 logistic coefficients and intercept, feature ordering, and clipping rule. Current offline fit uses `C=0.1` with balanced classes. |
| Decoder | One globally selected threshold, boundary penalty, duration weight, merge gap, and edge weight `lambda`; the duration and hard-gap constants from `detector.py`. |

Export numeric arrays in a versioned, checksum-verified format suitable for
Kotlin or ONNX inference. Do not load Python pickle or scikit-learn on the
phone. Add a desktop round-trip test that reloads the export and reproduces
the Python probabilities, edge scores, and intervals before integrating it
into Android. The MiniLM ONNX files alone are about 87 MiB; benchmark the
complete bundle before deciding whether the boundary-head variant fits the
target devices. Quantization or a different encoder changes the model and
requires a new parity and accuracy evaluation.
For an always-offline feature, package the verified bundle in app assets (for
example a versioned `src/main/assets/ad_detector/` directory in the owning
Android module), or make it part of a preinstalled module. Fetching weights
on first use would make first-use analysis depend on network access. Load one
version atomically and reject a missing or checksum-mismatched bundle rather
than mixing tokenizer and classifier versions.

### 3. Reproduce preprocessing and inference exactly

Use the entire sorted transcript. For segment `i`, normalize text with the
same Unicode case-fold and whitespace-collapse behavior as
`detector.normalized_text`. Construct its base-classifier document exactly as
`current + current + previous + next`, with empty text at the episode ends.
In particular, Android `lowercase()` is not automatically equivalent to
Python `casefold()` for every Unicode string; either implement identical
normalization or retrain the exported model with a shared normalization rule.

For each document, generate the two independently normalized TF-IDF vectors
and concatenate them in the exported feature order. Compute the base logistic
probability and apply the training-prior adjustment used by
`detector.predict_model`:

```text
p_raw = sigmoid(base_weights · tfidf + base_intercept)
p = sigmoid(logit(clamp(p_raw, 1e-6, 1 - 1e-6))
            + log(positive_segment_prior / (1 - positive_segment_prior)))
```

For each candidate segment start and end, reproduce `boundary_head.py`:
collect segments with midpoints in the 15 seconds on each side; calculate
nearest, duration-weighted mean, minimum, and maximum base probability on
each side, two side differences, adjacent gap/durations, speech coverage,
missing-side flags, and embedding cosine similarity (18 scalars total).
Duration-weighted mean the current-text MiniLM embeddings on each side and
L2-normalize each 384-value mean. Apply the saved scalar scaler, then each
head's logistic **decision score**, clipped to `[-4, 4]`. Do not treat these
scores as calibrated probabilities.

Port `detector.decode_intervals` without changing its behavior: segment
emissions are `logit(p) - logit(threshold)` weighted by clipped segment
duration; candidate pods last 8–300 seconds; a transcript gap greater than
12 seconds is a hard boundary; the score includes the duration prior,
boundary penalty, and `lambda * (start_score + end_score)`. Use dynamic
programming to select nonoverlapping pods, then apply the exported merge gap.
Convert the chosen segment edges to media milliseconds, clamp to
`[0, episodeDuration]`, and reject zero-length or overlapping output. The
existing low-energy audio snap reduced accuracy in this experiment; leave it
disabled in the first Android port.

Before device testing, create fixed transcript fixtures covering normal
speech, long silence, a mixed editorial/ad segment, pre-roll, post-roll, and
an episode without ads. Compare Kotlin intermediate TF-IDF values, base
probabilities, edge scores, and final intervals against the exported Python
bundle. Specify a numerical tolerance and require the same interval list;
test a downloaded MP3 end-to-end with airplane mode enabled.

### 4. Connect to the current Pocket Casts checkout

The current checkout already has an ad-segment path. In
`modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/ads/AdSegmentManagerImpl.kt`,
`analyze()` invokes `AudioAdAnalyzer`; `PlaybackManager.observeAdSegments()`
in `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/PlaybackManager.kt`
starts analysis when an episode is selected and observes stored segments.
`EpisodeLocation.Downloaded` uses the local downloaded file path. Add a
`TranscriptAdAnalyzer` implementation in the repositories service and route
**downloaded** episodes to it behind the existing `Feature.AD_SKIPPING` gate
or a separate research flag. Keep streaming analysis separate; this method
was evaluated on a complete downloaded file, not a partial stream.

For pre-playback analysis, enqueue unique WorkManager work after a successful
`DownloadEpisodeWorker` result in
`modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/download/DownloadEpisodeWorker.kt`.
Run one episode at a time on a background dispatcher, with cancellation and a device-resource policy suitable for the
measured STT cost. The playback-triggered path can request the same unique
work when no valid result exists. Do not transcribe on the playback thread.
Guard against two analyzers writing the same episode concurrently. On a file
change, cancel stale work and invalidate its transcript and predictions.

Write each final pod as a `DbAdSegment` through `AdSegmentDao.replaceAll()`:
use the episode UUID and ordered segment index, start/end milliseconds,
`AdSegmentKind.Ad` (the binary model does not distinguish sponsor versus
self-promo), and `AdSegmentOrigin.Transcript`. The current Room path maps
these records back to player-visible `AdSegment`s. Preserve a listener's
`deselected` choice when replacing a corresponding pod if the product expects
that behavior. The current `AdAnalysisState` tracks progress/completion but
not model or file identity; extend it (with a Room migration) or add a
separate record for file hash/size, STT version, model version, failure state,
and completed-at time. Never reuse intervals for a newly downloaded ad
stitch of the same episode UUID.

**Keep this research model suggestion-only.** Current `AdSegment` defaults
to confidence `1.0`, and `isAutoSkippable` becomes true at confidence `0.6`
when selected; `AdSkipper` then permits an automatic seek. The present
precision is far below the requested gate, and the logistic outputs are not
calibrated pod confidence. Set a value below the auto-skip threshold or add
an explicit non-auto-skippable origin/policy until an independently verified
test meets the product requirement. Do not silently map the model's segment
probability to `DbAdSegment.confidence`.

### 5. Validate on the phone before enabling it

Measure transcript coverage/timestamp drift, model load time, peak memory,
CPU time, battery/thermal impact, APK or downloaded-model size, and latency
per hour of audio on representative low- and high-end devices. Repeat with
network disabled. Compare the phone's interval output to the desktop export
on the **same phone-generated transcript** first; then compare to an
independently listened, boundary-timed episode set. Keep all takes of one
episode together, hold out shows/episodes that were not used to pick model
settings, and score one-to-one pod matches only when both boundaries are
within 10 seconds. Report precision and recall by show and in aggregate,
including ad-free episodes and failures to transcribe. Enable automatic
skipping only if that independent test supports the required 0.90/0.90 and
the device cost is acceptable.

## Reproduction pointers

- `labels/ads_v3.json`, `labels/audit_report_v3.md`,
  `labels/review_queue_v3.md` and `review/label_qa.md` document the latest
  references and their limitations.
- `semantic/detector.py`, `semantic/results/REPORT_v3.md`, and
  `semantic/results/evaluation_v3.json` contain the detector and fold results.
- `semantic/boundary_head.py` and
  `semantic/results/boundary_head_v3_REPORT.md` contain the follow-up method,
  paired episode outcomes, and compute cost.
- `audio_review/README.md` explains exact-byte sibling comparison and why
  it is label evidence only, never a single-take inference input.
