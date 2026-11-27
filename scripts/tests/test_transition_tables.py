"""Plan A.3 (JEPA transition log): the extractor reads decision_steps, cycle_outcomes and decision_percepts from a tiny
raw dump, and skips them cleanly when the dump was written with transitionLoggingEnabled off.

Run from the repo root:  PYTHONPATH=scripts python3 -m unittest scripts/tests/test_transition_tables.py
"""
import sys
import tempfile
import unittest
from pathlib import Path

import pyarrow as pa

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

from dl2l_data import extract  # noqa: E402
from dl2l_data.tables import TABLE_ORDER, TABLES  # noqa: E402

NEW_TABLES = ["decision_steps", "cycle_outcomes", "decision_percepts"]

SCHEMAS = {
    "decision_steps": pa.schema([
        ("creature_key", pa.int64()), ("cycle", pa.int64()), ("action", pa.string()),
        ("object_type", pa.string()), ("target_key", pa.int64()), ("target_sequential", pa.int64()),
        ("h_hunger", pa.float64()), ("h_sleep", pa.float64()), ("h_pain", pa.float64()), ("h_tedium", pa.float64()),
        ("focal_drive", pa.string()), ("id", pa.string()),
    ]),
    "cycle_outcomes": pa.schema([
        ("creature_key", pa.int64()), ("cycle", pa.int64()),
        ("d_hunger", pa.float64()), ("d_sleep", pa.float64()), ("d_pain", pa.float64()), ("d_tedium", pa.float64()),
        ("id", pa.string()),
    ]),
    "decision_percepts": pa.schema([
        ("creature_key", pa.int64()), ("cycle", pa.int64()), ("rank", pa.int32()),
        ("object_key", pa.int64()), ("object_sequential", pa.int64()), ("object_type", pa.string()),
        ("distance", pa.float64()), ("angle", pa.float64()), ("contact", pa.bool_()), ("id", pa.string()),
    ]),
}

ROWS = {
    "decision_steps": [
        {"creature_key": 7, "cycle": 2, "action": "EAT", "object_type": "RED_APPLE", "target_key": 42,
         "target_sequential": 43, "h_hunger": 3.0, "h_sleep": 0.0, "h_pain": 0.0, "h_tedium": 0.0,
         "focal_drive": "HUNGER", "id": "a"},
        {"creature_key": 7, "cycle": 1, "action": "WANDER", "object_type": None, "target_key": None,
         "target_sequential": None, "h_hunger": 2.5, "h_sleep": 0.0, "h_pain": 0.0, "h_tedium": 0.0,
         "focal_drive": None, "id": "b"},
    ],
    "cycle_outcomes": [
        {"creature_key": 7, "cycle": 2, "d_hunger": -1.5, "d_sleep": 0.0, "d_pain": 0.0, "d_tedium": 0.0, "id": "c"},
    ],
    "decision_percepts": [
        {"creature_key": 7, "cycle": 2, "rank": 0, "object_key": 42, "object_sequential": 43,
         "object_type": "RED_APPLE", "distance": 10.0, "angle": 0.0, "contact": True, "id": "d"},
        {"creature_key": 7, "cycle": 2, "rank": 1, "object_key": 44, "object_sequential": 45,
         "object_type": "RED_APPLE", "distance": 120.0, "angle": 0.5, "contact": False, "id": "e"},
    ],
}


def _write_stream(path: Path, schema: pa.Schema, rows: list) -> None:
    table = pa.Table.from_pylist(rows, schema=schema)
    with pa.OSFile(str(path), "wb") as sink:
        with pa.ipc.new_stream(sink, schema) as writer:
            writer.write_table(table)


class TransitionTablesExtractTest(unittest.TestCase):

    def test_new_tables_are_registered_and_ordered_by_creature_then_cycle(self):
        for name in NEW_TABLES:
            self.assertIn(name, TABLES)
            self.assertIn(name, TABLE_ORDER)
        self.assertIn("ORDER BY creature_key, cycle", TABLES["decision_steps"][0])
        self.assertIn("ORDER BY creature_key, cycle", TABLES["cycle_outcomes"][0])
        self.assertIn("ORDER BY creature_key, cycle, rank", TABLES["decision_percepts"][0])

    def test_tiny_dump_extracts_with_expected_columns_and_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            raw = Path(tmp)
            for name in NEW_TABLES:
                _write_stream(raw / f"{name}.arrow", SCHEMAS[name], ROWS[name])
            conn = extract._open_raw_views(raw)
            try:
                steps = conn.execute(TABLES["decision_steps"][0]).df()
                self.assertEqual(list(steps.columns), ["creature_key", "cycle", "action", "object_type", "target_key",
                                                       "target_sequential", "h_hunger", "h_sleep", "h_pain", "h_tedium",
                                                       "focal_drive"])
                self.assertEqual(list(steps["cycle"]), [1, 2])
                self.assertTrue(steps.loc[steps["cycle"] == 1, "target_key"].isna().iloc[0])

                outcomes = conn.execute(TABLES["cycle_outcomes"][0]).df()
                self.assertEqual(list(outcomes["d_hunger"]), [-1.5])

                percepts = conn.execute(TABLES["decision_percepts"][0]).df()
                self.assertEqual(list(percepts["rank"]), [0, 1])
                self.assertEqual(list(percepts["contact"]), [True, False])
                self.assertEqual(set(percepts["cycle"]), {2})
            finally:
                conn.close()

    def test_flag_off_dump_skips_the_new_tables_without_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            raw = Path(tmp)
            # A flag-off dump: no transition files at all, only a table the old code wrote.
            _write_stream(raw / "run_info.arrow", pa.schema([("creature_key", pa.int64())]),
                          [{"creature_key": 1}])
            conn = extract._open_raw_views(raw)
            try:
                for name in NEW_TABLES:
                    self.assertFalse(extract._has_view(conn, extract.OPTIONAL_RAW[name]),
                                     f"{name} must be absent from a flag-off dump")
            finally:
                conn.close()
            for name in NEW_TABLES:
                self.assertIn(name, extract.OPTIONAL_RAW)


if __name__ == "__main__":
    unittest.main()
