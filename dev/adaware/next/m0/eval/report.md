# M0 TF-IDF ad detector

- corpus: 32 episodes from 21 shows (20 train / 12 test episodes)
- segments: 55,528 with 4,048 positive (7.3%)
- features: 40,000 word + 20,000 char n-grams
- decoder: {'threshold': 0.05, 'boundary_penalty': 8.0, 'duration_weight': 0.75, 'merge_gap': 0.0}

Strict = one-to-one interval match with both boundaries within 10 s.

## Train

- strict P 0.485 / R 0.503
- frame P 0.775 / R 0.932 / AP 0.888

## Held-out shows

- strict P 0.092 / R 0.092
- frame P 0.416 / R 0.540 / AP 0.316

| show | tp | fp | fn | P | R |
|---|---|---|---|---|---|
| Decoder Ring | 2 | 29 | 18 | 0.06 | 0.10 |
| Grammar Girl | 0 | 12 | 10 | 0.00 | 0.00 |
| State of the World from NPR | 3 | 2 | 3 | 0.60 | 0.50 |
| The Political Scene | The New Yorker | 1 | 8 | 21 | 0.11 | 0.05 |
| The Rest Is History | 0 | 8 | 7 | 0.00 | 0.00 |
