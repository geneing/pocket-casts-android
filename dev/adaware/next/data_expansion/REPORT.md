# AdAware data expansion assessment

## Decision

The current corpus does **not** support a credible show-held-out semantic ad detector. It is useful for testing the pipeline, but the current model is acoustic (650 handcrafted audio features per frame), not semantic. Its labels are inferred from transcript differences between takes, and each show contributes only one episode. Leave-one-episode-out therefore withholds a show, but yields only nine labelled show folds and no evidence that a result repeats across multiple episodes of the same show. The handoff reports interval precision/recall of **0.02 / 0.06** at the 5-second boundary tolerance, with transcript-derived starts often missing the preceding jingle or music.

## Proof sample obtained

From the already-listed public feed `NPR's Book of the Day`, the script fetched two requests for **“Journalist Emily Feng on how Uyghur militants took up arms in Syria's civil war”** (10m 54s). Both returned byte-identical audio: **12,449,780 bytes each**, SHA-256 prefix `0bc3ec7d5062`, with request times of **12.2 s** and **7.4 s**. The files live under `sample_audio/`; their signed feed URLs are neither written to the manifest nor printed.

Because these takes are identical, they cannot provide take-difference ad labels; they may still contain shared ads. They are retained only as a small acquisition proof, not represented as new labelled training data. The downloader is idempotent and uses only the feed and episode already present in `dev/adaware/data/feeds.json`.

## Transcription and transcript availability

The existing transcription path was not runnable for this sample: WSL returned `E_ACCESSDENIED`; the host lacked `faster-whisper` and PyAV, and the repository's `.venv` is Linux-only. A standalone FFmpeg binary was subsequently made available for short boundary clips, but no second speech stack was installed just to produce an unpaired transcript. A smaller CPU Whisper model is not available locally, so its install size/runtime and compatibility remain unmeasured.

The selected NPR episode's publisher transcript could not be verified because NPR's page is blocked to the available web reader. As a nearby source check, [The New Yorker's episode page](https://www.newyorker.com/podcast/the-new-yorker-radio-hour/what-does-a-blue-texas-mean-for-america) exposes a transcript download. The download returned speaker-labelled prose with sparse minute marks, but its content was for a different, earlier episode (Barton Gellman, rather than the linked “Blue Texas” episode). It is therefore not a usable transcript for this sample. Sparse minute marks also do not provide word timing or reliable 10-second ad boundaries.

## Follow-on selection

`selection.json` records four additional short episodes from feeds already in `data/feeds.json`, chosen to create five distinct show-level holdouts including the proof sample. With two takes each, the five-episode set is about **2.65 hours** total (about **151 MB** using the existing corpus's average audio rate). The selections span Simplecast, Dow Jones, Megaphone, public radio, and NPR, and add interview, international-reporting, media, science, and economics content.

The current host only auto-downloads the two-take proof sample. That is intentional: the proof requests were identical and this session cannot transcribe locally, so fetching the other four episodes would add storage without producing labels now. Once transcription is available, request two takes per candidate, confirm that the audio differs, then generate and manually spot-check transcript-difference labels before using them in a held-out evaluation.

## Files

- `download_sample.py` — repeatable, URL-redacting two-take downloader for the proof episode.
- `sample_downloads.json` — episode, take, byte count, elapsed fetch time, and hashes; no source URLs.
- `selection.json` — exact candidate episode titles, feeds, duration estimates, and reasons.
- `sample_audio/` — the two proof files, isolated from the original corpus.
