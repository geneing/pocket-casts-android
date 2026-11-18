#!/usr/bin/env bash
# Lists the signature keys and tensor names baked into the Parakeet Redux graphs, so a wrong name in
# ParakeetRecognizer shows up here instead of as a failed run on the phone.
set -u
cd "$(dirname "$0")/model" || exit 1
pattern='^(audio|n_valid|valid|features|mel_mask|frame_mask|enc|token|h|c|h_out|c_out|dec_out|enc_frame|logits|predict|joint|serving_default)$'
for f in parakeet_redux_mel_10s.tflite parakeet_redux_decoder_i8.tflite parakeet_redux_encoder_10s_i4.tflite; do
    echo "== $f"
    strings -n 3 "$f" | grep -E "$pattern" | sort | uniq -c
done
