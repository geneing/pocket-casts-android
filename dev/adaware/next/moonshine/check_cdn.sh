#!/usr/bin/env bash
# Does the CDN actually have an attention decoder for each language?
set -uo pipefail
base=https://download.moonshine.ai/model
for u in tiny-streaming-en/quantized_26_11_02 tiny-streaming-es/quantized_26_08_24; do
  printf '%-50s ' "$u/decoder_kv_with_attention.ort"
  curl -s -o /dev/null -w '%{http_code}\n' -I "$base/$u/decoder_kv_with_attention.ort"
done
