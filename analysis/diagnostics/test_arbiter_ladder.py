"""Pure-function checks for analysis/experiments/arbiter_ladder.py.  python3 -m unittest analysis.diagnostics.test_arbiter_ladder"""
import math
import unittest

import numpy as np
import pandas as pd

from analysis.experiments import arbiter_ladder as al


class DeficitTest(unittest.TestCase):
    def test_zero_at_or_below_the_set_point(self):
        self.assertEqual(al.deficit(np.array([[0.18, 0.0, 0.1, 0.18]]))[0], 0.0)

    def test_euclidean_norm_of_positive_deviations(self):
        # deviations 3, 4, 0, 0 (levels set point + 3, + 4) -> 5
        self.assertAlmostEqual(al.deficit(np.array([[3.18, 4.18, 0.0, 0.1]]))[0], 5.0)


class LabelsAndSwitchesTest(unittest.TestCase):
    def test_argmax_and_none(self):
        lev = np.array([[1.0, 0.5, 0.0, 0.0], [0.2, 0.9, 0.0, 0.0], [0.1, 0.1, 0.1, 0.1]])
        self.assertEqual(al.argmax_labels(lev).tolist(), [0, 1, -1])

    def test_transfers_and_opens_are_counted_separately(self):
        # none, H, H, S (transfer), none, S (open), H (transfer)
        lab = np.array([-1, 0, 0, 1, -1, 1, 0])
        self.assertEqual(al.switch_counts(lab), (2, 2))

    def test_short_series(self):
        self.assertEqual(al.switch_counts(np.array([0])), (0, 0))


class PairedTest(unittest.TestCase):
    def test_difference_sd_and_upper_bound(self):
        tm = pd.DataFrame({"a0": [1.0, 2.0, 3.0], "a1": [2.0, 4.0, 3.0]}, index=[1, 2, 3])
        r = al.paired_differences(tm, "a1", "a0")
        self.assertEqual(r["diffs"], [1.0, 2.0, 0.0])
        self.assertAlmostEqual(r["mean"], 1.0)
        self.assertAlmostEqual(r["sd"], 1.0)
        self.assertAlmostEqual(r["upper80_sd"], al.UPPER_FACTOR)

    def test_missing_arm(self):
        self.assertIsNone(al.paired_differences(pd.DataFrame({"a0": [1.0]}), "a1", "a0"))


class CalibrationTest(unittest.TestCase):
    def test_z_by_close_type_ignores_rows_without_an_episode(self):
        df = pd.DataFrame({
            "episode_id": [1, 1, None],
            "realised_value": [3.0, 1.0, 9.0],
            "executed_q": [0.0, 0.0, 0.0],
            "executed_se": [3.0, 1.0, 1.0],
            "executed_sigma": [4.0, 0.0, 0.0],
            "close_type": ["REGULATED", "REGULATED", "REGULATED"],
        })
        z = al.calibration_z(df)
        self.assertEqual(int(z.loc["REGULATED", "n"]), 2)
        # z = 3 / sqrt(9 + 16) = 0.6 and 1 / 1 = 1
        self.assertAlmostEqual(z.loc["REGULATED", "mean"], 0.8)
        self.assertFalse(math.isnan(z.loc["REGULATED", "rms"]))


if __name__ == "__main__":
    unittest.main()
