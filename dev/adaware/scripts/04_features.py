"""Extract compact spectral/prosodic features for ad detection.

Everything here is deliberately cheap enough to port to Kotlin and run on a phone:
no ML at feature time, only an STFT, a few band sums, an autocorrelation pitch
estimate and running statistics.

Layout: 16 kHz mono, 25 ms frames, 10 ms hop -> ~40 vector features per 10 ms.
Those are pooled to 0.25 s (mean + std + range) and contextualised with rolling
statistics, giving a 0.25 s detection grid.

Output: data/features/<episode>/<take>.npz  with
    times (N,), feats (N,F), names (F,)
"""

from __future__ import annotations

import argparse
import subprocess
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np

from common import DATA, FEATURES, write_json

SR = 16000
WIN = 400  # 25 ms
HOP = 160  # 10 ms
NFFT = 512


def decode(path: Path) -> np.ndarray:
    cmd = [
        "ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(SR),
        "-f", "f32le", "-",
    ]
    out = subprocess.run(cmd, capture_output=True, check=True)
    return np.frombuffer(out.stdout, dtype=np.float32)


def frame_signal(x: np.ndarray) -> np.ndarray:
    if len(x) < WIN:
        x = np.pad(x, (0, WIN - len(x)))
    n = 1 + (len(x) - WIN) // HOP
    idx = np.arange(WIN)[None, :] + HOP * np.arange(n)[:, None]
    return x[idx]


def f0_autocorr(frames: np.ndarray, sr: int = SR) -> tuple[np.ndarray, np.ndarray]:
    """Pitch + voicing by normalised autocorrelation (cheap, phone friendly)."""
    win = frames * np.hanning(frames.shape[1])[None, :]
    spec = np.fft.rfft(win, 2 * frames.shape[1], axis=1)
    ac = np.fft.irfft(spec * np.conj(spec), axis=1)
    ac = ac / (ac[:, :1] + 1e-9)
    lo, hi = int(sr / 400), int(sr / 60)
    seg = ac[:, lo:hi]
    lag = seg.argmax(axis=1) + lo
    peak = seg.max(axis=1)
    f0 = np.where(peak > 0.3, sr / lag, 0.0)
    return f0, np.clip(peak, 0, 1)


BANDS = [(0, 200), (200, 500), (500, 1000), (1000, 2000), (2000, 4000), (4000, 6000), (6000, 8000)]
BAND_NAMES = [f"band_{a}_{b}" for a, b in BANDS]

N_MELS, N_MFCC = 26, 13


def mel_filterbank(sr: int = SR, nfft: int = NFFT) -> np.ndarray:
    """26 triangular mel filters; one small matrix, cheap on a phone."""
    def hz2mel(f):
        return 2595.0 * np.log10(1.0 + f / 700.0)

    def mel2hz(m):
        return 700.0 * (10.0 ** (m / 2595.0) - 1.0)

    freqs = np.fft.rfftfreq(nfft, 1 / sr)
    edges = mel2hz(np.linspace(hz2mel(100), hz2mel(7600), N_MELS + 2))
    fb = np.zeros((N_MELS, len(freqs)))
    for i in range(N_MELS):
        lo, mid, hi = edges[i], edges[i + 1], edges[i + 2]
        fb[i] = np.clip(np.minimum((freqs - lo) / (mid - lo + 1e-9), (hi - freqs) / (hi - mid + 1e-9)), 0, None)
    return fb


def dct_matrix(n_in: int = N_MELS, n_out: int = N_MFCC) -> np.ndarray:
    k = np.arange(n_out)[:, None]
    n = np.arange(n_in)[None, :]
    return np.cos(np.pi * k * (2 * n + 1) / (2 * n_in)) * np.sqrt(2.0 / n_in)



def frame_features(x: np.ndarray, block: int = 8192) -> tuple[np.ndarray, list[str]]:
    """Frame features, computed in blocks so long episodes keep memory flat."""
    frames = np.lib.stride_tricks.sliding_window_view(x, WIN)[::HOP]
    n = frames.shape[0]
    freqs = np.fft.rfftfreq(NFFT, 1 / SR)
    band_masks = [(freqs >= a) & (freqs < b) for a, b in BANDS]

    names = [
        "db", "peak_db", "crest", "zcr", "centroid_khz", "bandwidth_khz", "rolloff_khz",
        "flatness", "flux", "f0_hz", "voicing",
        *BAND_NAMES,
        "band_spread",
        *[f"mfcc_{i}" for i in range(N_MFCC)],
    ]
    out = np.empty((n, len(names)), dtype=np.float32)
    melfb, dct = mel_filterbank(), dct_matrix()
    prev_spec = None

    for start in range(0, n, block):
        fb = frames[start : start + block]
        spec = np.abs(np.fft.rfft(fb * np.hanning(WIN)[None, :], NFFT, axis=1)) ** 2
        power = spec.sum(axis=1) + 1e-12
        centroid = (spec * freqs[None, :]).sum(1) / power
        bandwidth = np.sqrt((spec * (freqs[None, :] - centroid[:, None]) ** 2).sum(1) / power)
        cum = np.cumsum(spec, axis=1) / power[:, None]
        rolloff = freqs[np.argmax(cum >= 0.85, axis=1)]
        flatness = np.exp(np.log(spec + 1e-12).mean(1)) / (spec.mean(1) + 1e-12)

        band_db = np.stack([10 * np.log10(spec[:, m].sum(1) + 1e-9) for m in band_masks], 1)
        rms = np.sqrt((fb**2).mean(1)) + 1e-9
        db = 20 * np.log10(rms)
        peak = 20 * np.log10(np.abs(fb).max(1) + 1e-9)
        zcr = (np.abs(np.diff(np.sign(fb), axis=1)) > 0).mean(1)
        f0, voicing = f0_autocorr(fb)

        spec_all = spec if prev_spec is None else np.vstack([prev_spec[None, :], spec])
        if prev_spec is None:
            spec_all = np.vstack([spec[:1], spec])
        flux = np.sqrt((np.diff(spec_all, axis=0) ** 2).sum(1)) / (power + 1e-12)
        prev_spec = spec[-1]

        out[start : start + block] = np.stack(
            [
                db, peak, peak - db, zcr, centroid / 1000, bandwidth / 1000, rolloff / 1000,
                flatness, flux, f0, voicing,
                *[band_db[:, k] - db for k in range(len(BANDS))],  # loudness-invariant timbre
                band_db.std(1),
                *[
                    (dct @ np.log(spec @ melfb.T + 1e-9).T)[i]
                    for i in range(N_MFCC)
                ],
            ],
            1,
        )
    return out, names


def pool_to_grid(feats: np.ndarray, names: list[str], hop_s: float = HOP / SR, grid: float = 0.25):
    """Mean/std/min/max pooling onto a fixed 0.25 s grid."""
    per = max(1, int(round(grid / hop_s)))
    n = feats.shape[0] // per
    f = feats[: n * per].reshape(n, per, -1)
    mean, std = f.mean(1), f.std(1)
    lo, hi = f.min(1), f.max(1)
    out = np.concatenate([mean, std, lo, hi], 1)
    out_names = [f"{nm}_mean" for nm in names] + [f"{nm}_std" for nm in names] + [
        f"{nm}_min" for nm in names
    ] + [f"{nm}_max" for nm in names]
    times = (np.arange(n) + 0.5) * grid
    return out, out_names, times, grid


def add_context(feats: np.ndarray, names: list[str], grid: float):
    """Rolling statistics: ads differ from the local baseline in level and dynamics."""
    from scipy.ndimage import uniform_filter1d

    def roll(arr, sec):
        w = max(1, int(round(sec / grid)))
        return uniform_filter1d(arr, size=w, axis=0, mode="nearest")

    short, long = roll(feats, 2.0), roll(feats, 10.0)
    var_short = np.maximum(0.0, roll(feats**2, 2.0) - short**2)
    var_long = np.maximum(0.0, roll(feats**2, 10.0) - long**2)

    mod, mod_names = modulation_features(feats, names, grid)

    extra, extra_names = boundary_features(feats, names, grid)

    out = np.concatenate(
        [
            feats,
            feats - short,
            feats - long,
            np.sqrt(var_short),
            np.sqrt(var_long),
            mod,
            extra,
        ],
        1,
    )
    out_names = (
        names
        + [f"{n}_rel2s" for n in names]
        + [f"{n}_rel10s" for n in names]
        + [f"{n}_std2s" for n in names]
        + [f"{n}_std10s" for n in names]
        + mod_names
        + extra_names
    )
    return out.astype(np.float32), out_names


def boundary_features(feats: np.ndarray, names: list[str], grid: float):
    """Silence neighbourhood and episode position: ad pods start and end at breaks."""
    from scipy.ndimage import uniform_filter1d

    db = feats[:, names.index("db_mean")]
    local = uniform_filter1d(db, size=max(3, int(round(10.0 / grid))), mode="nearest")
    quiet = db < (local - 12.0)
    idx = np.flatnonzero(quiet)
    if idx.size:
        pos = np.searchsorted(idx, np.arange(len(db)))
        left = np.where(pos > 0, np.arange(len(db)) - idx[np.maximum(pos - 1, 0)], 1e6)
        right = np.where(pos < idx.size, idx[np.minimum(pos, idx.size - 1)] - np.arange(len(db)), 1e6)
        dist = np.minimum(np.abs(left), np.abs(right)) * grid
    else:
        dist = np.full(len(db), 60.0)
    frac = uniform_filter1d(quiet.astype(np.float32), size=max(3, int(round(5.0 / grid))), mode="nearest")

    t = np.arange(len(db)) * grid
    dur = max(t[-1], 1.0)
    head = np.clip(t / 600.0, 0, 1)
    tail = np.clip((dur - t) / 600.0, 0, 1)
    out = np.stack([np.clip(dist, 0, 60), frac, head, tail], 1)
    return out.astype(np.float32), ["silence_dist", "silence_frac5s", "pos_from_start", "pos_to_end"]


def modulation_features(feats: np.ndarray, names: list[str], grid: float, win: float = 4.0):
    """Envelope modulation: music beds are steady, speech has 2-8 Hz syllable rhythm."""
    w = max(4, int(round(win / grid)))
    pad = w // 2
    n = feats.shape[0]
    out, out_names = [], []
    sources = ["db", "centroid_khz_mean", "band_2000_4000_mean"]
    for src in sources:
        if src not in names:
            continue
        col = feats[:, names.index(src)]
        padded = np.pad(col, (pad, pad), mode="edge")
        frames = np.lib.stride_tricks.sliding_window_view(padded, w)[:n]
        frames = frames - frames.mean(axis=1, keepdims=True)
        spec = np.abs(np.fft.rfft(frames, axis=1))
        freqs = np.fft.rfftfreq(w, grid)
        tot = spec.sum(1) + 1e-9
        for lo, hi in ((0.5, 2.0), (2.0, 6.0), (6.0, 12.0)):
            m = (freqs >= lo) & (freqs < hi)
            out.append(spec[:, m].sum(1) / tot)
            out_names.append(f"mod_{src}_{lo:g}_{hi:g}")
    return np.stack(out, 1) if out else np.zeros((n, 0), np.float32), out_names


def process_file(path: Path) -> dict:
    out_dir = FEATURES / path.parent.name
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"{path.stem}.npz"
    if out.exists():
        return {"file": str(path), "cached": True}
    x = decode(path)
    raw, names = frame_features(x)
    grid, grid_names, times, step = pool_to_grid(raw, names)
    ctx, ctx_names = add_context(grid, grid_names, step)
    np.savez_compressed(out, times=times, feats=ctx, names=np.array(ctx_names))
    return {
        "file": str(path),
        "duration": round(len(x) / SR, 1),
        "shape": list(ctx.shape),
        "cached": False,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args()
    files = [p for p in sorted((DATA / "audio").glob("*/*.mp3")) if args.only in str(p)]
    print(f"{len(files)} files")
    with ProcessPoolExecutor(max_workers=args.workers) as ex:
        for i, res in enumerate(ex.map(process_file, files), 1):
            print(f"[{i}/{len(files)}] {Path(res['file']).parent.name}/{Path(res['file']).name} {res.get('shape', 'cached')}")
    write_json(FEATURES / "names.json", {"note": "names are stored inside each npz"})


if __name__ == "__main__":
    main()
