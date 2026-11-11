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


def _updates(samples, q, se, sigma, obj="RED_APPLE", action="EAT", creature=1):
    return pd.DataFrame({"creature_key": creature, "seq": range(len(samples)), "object_type": obj, "action": action,
                         "sample": samples, "q": q, "se": se, "sigma": sigma})


class OperantCalibrationTest(unittest.TestCase):
    def test_first_update_is_scored_against_the_prior_and_the_next_against_the_previous_row(self):
        u = _updates([1.0, -1.0, 1.0], q=[0.5, 0.0, 0.2], se=[0.6, 0.5, 0.4], sigma=[1.0, 0.9, 0.8])
        z = al.operant_updates_z(u)["z"].tolist()
        self.assertAlmostEqual(z[0], 1.0 / math.sqrt(1.0 + 1.0))                      # prior (0, 1, 1)
        self.assertAlmostEqual(z[1], (-1.0 - 0.5) / math.sqrt(0.6 ** 2 + 1.0 ** 2))   # row 0 after its update
        self.assertAlmostEqual(z[2], (1.0 - 0.0) / math.sqrt(0.5 ** 2 + 0.9 ** 2))

    def test_cells_are_kept_apart_and_ordered_by_seq(self):
        a = _updates([1.0, 1.0], q=[0.5, 0.6], se=[0.5, 0.4], sigma=[1.0, 1.0], obj="RED_APPLE")
        b = _updates([-1.0, -1.0], q=[-0.5, -0.6], se=[0.5, 0.4], sigma=[1.0, 1.0], obj="GRAY_APPLE")
        z = al.operant_updates_z(pd.concat([b, a]).sample(frac=1.0, random_state=3))
        red = z[z["object_type"] == "RED_APPLE"].sort_values("seq")["z"].tolist()
        self.assertAlmostEqual(red[1], (1.0 - 0.5) / math.sqrt(0.5 ** 2 + 1.0))

    def test_statistic_leaves_out_constant_and_small_cells_and_weights_by_updates(self):
        rng = np.random.default_rng(0)
        varying = _updates(rng.choice([-1.0, 1.0], 60), q=0.0, se=0.5, sigma=1.0, obj="RED_APPLE")
        constant = _updates([-1.0] * 100, q=-0.9, se=0.1, sigma=0.5, obj="GRAY_APPLE")
        small = _updates(rng.choice([-1.0, 1.0], 10), q=0.0, se=0.5, sigma=1.0, obj="GREEN_APPLE")
        r = al.operant_calibration(pd.concat([varying, constant, small]))
        self.assertEqual(list(r["cells"].index), [("RED_APPLE", "EAT")])
        self.assertEqual(list(r["constant_cells"].index), [("GRAY_APPLE", "EAT")])
        self.assertAlmostEqual(r["statistic"], float(r["cells"]["variance"].iloc[0]))
        self.assertTrue(r["pooled_variance"] < r["statistic"], "constant cells drag the pooled variance down")

    def test_no_eligible_cell_gives_nan(self):
        r = al.operant_calibration(_updates([-1.0] * 40, q=-0.9, se=0.1, sigma=0.5))
        self.assertTrue(math.isnan(r["statistic"]))


class EpisodicCalibrationTest(unittest.TestCase):
    def _decisions(self):
        return pd.DataFrame({
            "condition": "a3", "trial": 1, "creature_key": [1] * 6,
            "deciding_controller": ["EPISODIC", "EPISODIC", "OPERANT", "EPISODIC", "EPISODIC", "EPISODIC"],
            "executed_source": ["CHOSEN", "OTHER_CANDIDATE", "CHOSEN", "VALUE_OF", "CHOSEN", "CHOSEN"],
            "episode_id": [1, 1, 1, 1, None, 2],
            "close_type": ["REGULATED", "TIMED_OUT", "REGULATED", "REGULATED", "REGULATED", "TIMED_OUT"],
            "realised_value": [0.3, 0.0, 9.0, 9.0, 9.0, 0.2],
            "executed_q": [0.1, 0.0, 0.0, 0.0, 0.0, 0.0],
            "executed_se": [0.1, 0.1, 1.0, 1.0, 1.0, 0.1],
            "executed_sigma": [0.1, 0.1, 1.0, 1.0, 1.0, 0.1],
        })

    def test_only_episodic_decisions_with_their_own_estimate_and_a_realised_value_count(self):
        z = al.episodic_z(self._decisions())
        self.assertEqual(len(z), 3)   # not OPERANT, not VALUE_OF, not the row without an episode
        self.assertAlmostEqual(z["z"].iloc[0], (0.3 - 0.1) / math.sqrt(0.02))

    def test_calibration_by_close_type(self):
        r = al.episodic_calibration(self._decisions())
        self.assertEqual(r["n"], 3)
        self.assertEqual(int(r["by_close_type"].loc["TIMED_OUT", "n"]), 2)
        self.assertAlmostEqual(r["mean"], np.mean([(0.3 - 0.1) / math.sqrt(0.02), 0.0, 0.2 / math.sqrt(0.02)]))

    def test_nothing_episodic_gives_an_empty_summary(self):
        d = self._decisions()
        d["deciding_controller"] = "OPERANT"
        r = al.episodic_calibration(d)
        self.assertEqual(r["n"], 0)
        self.assertTrue(math.isnan(r["mean"]))

    def test_controller_share_by_decile(self):
        d = pd.DataFrame({"condition": "a3", "trial": 1, "creature_key": 1,
                          "deciding_controller": ["OPERANT"] * 5 + ["EPISODIC"] * 5})
        share = al.controller_share_by_decile(d, "EPISODIC")
        self.assertEqual(share, [0.0] * 5 + [1.0] * 5)


class LadderArmsTest(unittest.TestCase):
    def test_a3_and_a5_are_on_the_ladder_with_their_contrasts(self):
        self.assertEqual(al.ARMS[-2:], ["a3", "a5"])
        self.assertIn(("a3", "a2"), al.PAIRS)
        self.assertIn(("a3", "a5"), al.PAIRS)
        self.assertIn(("a3", "a5"), al.SURVIVAL_PAIRS)

    def test_present_arms_keeps_ladder_order_and_drops_absent_ones(self):
        cr = pd.DataFrame({"condition": ["a5", "a3", "a3"]})
        self.assertEqual(al.present_arms(cr), ["a3", "a5"])


if __name__ == "__main__":
    unittest.main()
