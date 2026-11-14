# M0 TF-IDF ad detector

- corpus: 10 episodes from 10 shows (9 train / 1 test episodes)
- segments: 17,591 with 2,365 positive (13.4%)
- features: 40,000 word + 20,000 char n-grams
- decoder: {'threshold': 0.12, 'boundary_penalty': 8.0, 'duration_weight': 0.0, 'merge_gap': 0.0}

Strict = one-to-one interval match with both boundaries within 10 s.

## Train

- strict P 0.649 / R 0.678
- frame P 0.943 / R 0.943 / AP 0.926

## Held-out shows

- strict P 0.091 / R 0.091
- frame P 0.903 / R 0.772 / AP 0.830

| show | tp | fp | fn | P | R |
|---|---|---|---|---|---|
| odd-lots | 1 | 10 | 10 | 0.09 | 0.09 |
