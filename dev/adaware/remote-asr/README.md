# Remote transcription (OpenRouter)

The alternative to on-device Parakeet: the same episode, recognised by
`nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b` through OpenRouter. It is off unless the
`remote_transcription` feature flag is on (Settings → Developer → Beta features in a debug build) and
the build carries an OpenRouter key; with the flag off, everything behaves exactly as before.

- Code: `modules/services/repositories/.../repositories/ads/text/remote/`
  - `RemoteSttClient` — the endpoint, its request and response shapes, timeouts.
  - `AacChunks` — the compressed chunk encoder, used only when the original will not do.
  - `RemoteCues` — words or segments to the sentence cues the rest of the app stores.
  - `RemoteEpisodeTranscriber` — the pass: original file when it fits, chunks otherwise.
- The choice between engines is `ads/text/SelectingEpisodeTranscriber.kt`; the cost line at the end
  of the transcript comes from `LocalTranscriptStore.appendCostSummary`.
- Probe: `openrouter_stt.py` in this directory. It answered every question below; rerun it with
  `OPENROUTER_API_KEY=... python3 openrouter_stt.py suite` (WSL, `requests` and `ffmpeg` needed).

## What the endpoint does, measured

Everything here was run against the real endpoint in November 2026 with this model. Costs are the
provider's, from `usage.cost` in each response.

| Question | Answer |
| --- | --- |
| wav by base64 JSON | Works. 11 s clip in 1.4 s. |
| mp3 by base64 JSON | Works, same text and same price as the wav. |
| multipart file upload | Works (25 MB file cap), not used: it needs the same bytes and gives nothing back. |
| `format: "pcm"` | **Refused**, 400 "Provider could not process the audio input (unsupported or malformed audio)". Raw 16 kHz mono s16le is not accepted. |
| `input_audio: {"url": ...}` (`source_url`) | **Refused**, 400 "The selected model does not support audio from a URL. Send the audio inline instead." Tried with raw.githubusercontent.com and with a real podcast URL (redirecting enclosure and direct CDN). |
| `stream: true` | Accepted and ignored: the reply is one ordinary JSON body. There is no SSE variant of this endpoint. |
| `verbose_json` | Returns `task`, `language`, `duration`, `segments[]` and `words[]`, **both with start and end times**. `timestamp_granularities` is honoured. This is what makes sentence cues and ad boundaries possible. |
| `language` omitted | Accepted; the reply echoes `language: "auto"`. Text differs only in the odd proper noun ("Apolo" vs "Apollo"). |
| Price | $0.00000333 per second of audio (DeepInfra). 60 s = $0.0001998, 10 min = $0.001998, 20 min = $0.003996. An hour is about $0.012. |
| Speed | ~25× real time: 60 s in 2–3 s, 5 min in 11 s, 10 min in 24 s, 20 min in 46 s. |
| Largest request body | **52,428,800 bytes (50 MiB)**, enforced with `413 Request body of … exceeds the maximum allowed size of 52428800 bytes`. A 20 min 16 kHz mono wav (51.2 MB of JSON) passes; 26.5 min (67.8 MB) is refused. |
| Containers accepted | wav, mp3, m4a/AAC, ogg/Opus. At 60 s: wav 1.92 MB, mp3 96k 721 kB, mp3 48k 361 kB, mp3 32k 241 kB, m4a 48k 402 kB, m4a 32k 253 kB, m4a 24k 192 kB, ogg 24k 177 kB — **identical words and text at every one of them**, so compression costs nothing in recognition. |

A hard cut costs speech: splitting a 10 min clip at 5 min made the first half end at
"…satisfying flavors you can." and the second start at "Just quality and peace of mind…", about 2.5 s
missing at the seam. That is why chunks overlap.

## How the pass uses that

1. **Small enough original → upload it untouched.** A downloaded (or streamed) episode whose file is
   a compressed container and no larger than 24 MiB (24 MiB of audio is ~32 MiB of base64 JSON, clear
   of the 50 MiB limit and of a phone's heap) is sent as it was fetched, in one request. That covers
   about 25 minutes at 128 kbit/s. The provider decodes it itself, so re-encoding would add a
   generation of loss and CPU for bytes the listener already paid to fetch. The stream cache keeps
   the URL's extension for exactly this reason.
2. **Otherwise split the file it already has — copy, don't re-encode.** An mp3 is a chain of frames
   with no container: `Mp3Frames` indexes the 4-byte headers (238 MB, 4.4 h of audio, in 120 ms) and
   copies whole frames, which is a valid mp3 per chunk. An MP4/M4A is remuxed sample-for-sample with
   `MediaExtractor` + `MediaMuxer`. Both are 10 minutes per request at the episode's own bitrate, so
   the pass is network-bound instead of spending minutes decoding and encoding audio to save bytes the
   listener already downloaded. A frame boundary is not the requested millisecond, so the chunk
   reports the frame's *real* position and every cue of that chunk is placed by it.
3. **Re-encode only what cannot be copied.** A WAV over 24 MiB (it is 8× the bytes of the same
   speech), an unknown extension, a resumed pass, or a codec the muxer cannot write (Vorbis in ogg,
   WebM) goes through `AudioFeed` (16 kHz mono) and 32 kbit/s AAC in an m4a with `MediaCodec` +
   `MediaMuxer`, 10 minutes per request — 2.4 MB against 9.6 MB of the same window at 128 kbit/s. AAC
   is what the platform can encode; Android ships no MP3 encoder and an mp3 chunk would need a native
   LAME build. A WAV that fits whole under 24 MiB is uploaded as it is. The endpoint takes all of them
   at the same price and the same words.
4. **Overlap the seams.** Each chunk carries 3 s of the audio before and after its window. Only cues
   that *start* inside the window are kept, so the chunk that began earlier finishes the sentence
   across the seam and the next one drops the duplicate.
5. **Nothing is estimated.** Cue times come from the model's own word times, through the same
   sentence builder (`ParakeetCues`) the on-device pass uses, so the cue shape the classifier prompt
   and the gap logic were tuned on is identical whichever engine produced the text.
6. **A resumed pass splits even when the original would fit**, so audio already transcribed is not
   paid for a second time.
7. **The price is shown** at the end of the transcript as one final cue,
   `Remote transcription cost: $0.0042`, accumulated across chunks and passes in a `.cost` sidecar
   next to the WebVTT. The on-device engine records none and writes no line.

## What happens when the endpoint refuses

Every refusal is classified by `RemoteSttClient` from the provider's own message first and the HTTP
status second, because both lie on their own: a model with no provider left answers 404 "No endpoints
found", an empty account answers 402 "Insufficient credits" but has also answered 200 with an
`error` object, and a 400 that only says "does not support audio from a URL" is a request this model
will always refuse, not a model that has gone away.

| kind | the user is told | suspends remote on |
|---|---|---|
| `ModelUnavailable` | the speech model is no longer available | the first failure |
| `OutOfFunds` | the OpenRouter account is out of credit | the first failure |
| `Network` | OpenRouter could not be reached | the second failure in a row |
| `Api` | an API error, with its HTTP status | the second failure in a row |

A success resets the count. The two permanent kinds need no second sample because no retry can fix
them; the other two do, because one dropped request on a seven-chunk pass is not a broken service.
Suspending flips `SelectingEpisodeTranscriber` to Parakeet and raises a bar in the app (`Dismiss` and
`Retry remote`), which stays up — across launches — until it is dismissed. Dismissing it keeps the
on-device engine; only `Retry remote` brings the cloud back.

A pass that fails on the remote engine while the suspension trips finishes on the device in the same
worker run instead of waiting out the 15-minute retry. It resets the episode's analysis first: a
transcript may not mix two engines, and the engine that wrote the stored cues is recorded in an
`.engine` sidecar next to `.audio` so a later pass by another engine starts over rather than resuming
mid-word.

## What the library actually publishes

An audit of a 119-feed podcast list, counting the format of each feed's newest enclosures, found 115
feeds on `audio/mpeg`, one on `audio/x-m4a`, one mixing the two, and two feeds that could not be read.
Three shows also carried a WAV episode in that window. No Ogg/Vorbis, WebM, ADTS AAC or FLAC enclosure
appeared at all.

So the two splitters that matter are the MP3 frame copy and the MP4/M4A remux, and the WAV path is not
hypothetical — a full-length WAV episode still reaches `AacChunks` and is the one case that spends
minutes of CPU. Everything else in `AudioSplitter`'s fallback set is insurance against a feed the audit
has not seen, not a path to optimise.

## Why not FFmpeg or Media3 Transformer
FFmpeg on Android means ffmpeg-kit, which was retired (archived July 2026) and left no maintained
binaries; its continuation, FFmpegKitNext, is source only, so this app would build and ship 15-30 MB of
native libraries per ABI, with 16 KB page-size builds and LGPL attribution, for a job that is a byte
copy. Media3 Transformer does transmux audio, but its output container is MP4 only, its fast trim is an
experimental MP4 edit list whose pre-roll the server's decoder need not honour (and whose start position
it never reports, which is exactly the number a chunk's cue times need), and it would be a new
dependency on top of code that already has `MediaExtractor` and `MediaMuxer` in the platform. The
platform pair does the same copy with no artifact and no licence, and reports the first sample's own
timestamp.

## Measured on the device (Pixel 11)

A 70 minute episode, 66.9 MB mp3 at 128 kbit/s, in 7 chunks of 10 minutes:

| | mp3, frame copy | the same audio as m4a, remuxed |
|---|---|---|
| preparing the chunks | 148 ms, total | 37 s, total (5.5 s per chunk) |
| wall time of the pass | 160 s | 199 s |
| of which the network | 159 s | 161 s |
| uploaded | 67.5 MB | 68.1 MB |
| cues | 493 | 474 |
| cost | $0.014046 | $0.014045 |

The pass is the network now. The same episode took 19.7 minutes when every chunk was decoded and
re-encoded to 32 kbit/s AAC, 18.4 minutes of which was the codec. The m4a path is slower than the mp3
one only because the muxer rewrites a 10 MB container per chunk; nothing is decoded there either. A WAV
has no copy path: 11 s of WAV encoded to one chunk in 1.25 s (8.8x realtime, $0.00004), so the fallback
that used to dominate the pass is now minutes of CPU for a full-length WAV.

A frame-copied chunk carries no ID3 tag and no container, so the endpoint was checked on exactly that
output: a 30 s slice of a real episode transcribed to the same 34 words at the same times as the full
file's first 30 s, and a slice cut at 299.990 s placed its words on the full file's words for the same
speech (median difference 70 ms, which is this model's own variance between two requests, not an
offset — the first slice matched to the millisecond).

Two things the device run changed in the code: a cue is now clamped to the audio its chunk carried (the
model put the last words of one 606 s chunk 4.5 s past the end of what it was given, and that cue
overlapped the next chunk's first), and a window that carries less than it asked for — the 24 MiB cap,
the end of the file — advances the cursor to where it really ended instead of to the requested end.

## What this costs a listener in data

An hour of audio is 15 MB uploaded when re-encoded (32 kbit/s), or its own compressed size (about
57 MB at 128 kbit/s) when it is copied — never more than the episode already cost to download.
Recognition is about 25× real time, so a 60 minute episode is done in roughly three minutes, and the
split adds well under a second.

## Things that surprised the probe

- The model's *name* says streaming; the endpoint does not stream, and there is no chunk size to
  discover beyond the 50 MiB body limit, because inline audio is the only accepted input.
- `source_url` is documented as "only supported by some providers" and this model's only provider is
  one of the others. A URL that redirects (every podcast enclosure tracked by a prefix host) is not
  even reached: the refusal comes before any download.
- The 20 minute request is accepted, so the limit is the request body, not a duration. Duration only
  matters through bytes.
