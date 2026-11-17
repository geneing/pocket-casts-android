import unittest

from score import score_corpus, score_take


def interval(start, end):
    return {"start": start, "end": end}


class ScoreTest(unittest.TestCase):
    def test_both_boundaries_required(self):
        result = score_take([interval(0, 30), interval(50, 91)], [interval(0, 30), interval(50, 80)])
        self.assertEqual((result["tp"], result["fp"], result["fn"]), (1, 1, 1))

    def test_maximum_matching_not_greedy(self):
        # Prediction 0 can match either gold interval; prediction 1 can match only gold 0.
        pred = [interval(4, 34), interval(0, 30)]
        gold = [interval(0, 30), interval(10, 40)]
        result = score_take(pred, gold, tolerance_seconds=6)
        self.assertEqual((result["tp"], result["fp"], result["fn"]), (2, 0, 0))

    def test_control_take_counts_false_positives(self):
        pred = {"episode": {"take0": [interval(4, 20)]}}
        gold = {"episode": {"take0": []}}
        result = score_corpus(pred, gold)
        self.assertEqual((result["tp"], result["fp"], result["fn"]), (0, 1, 0))


if __name__ == "__main__":
    unittest.main()
