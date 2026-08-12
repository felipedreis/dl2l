#!/usr/bin/env python3
"""Schema gates for the p84 pilot — docs/experiments/p84_behaviour_parity_recipe.md §4.2.

The gates existed as prose and were checked by eye. That was tolerable at seven; at ten,
three of which are invariants over the memory-decision columns, it is not. A failing gate
means the campaign must not be submitted, so it should be a non-zero exit code rather than
something to notice in a terminal scrollback.

    python3 scripts/check_experiment_gates.py ml/data_p84_pilot

Exits 1 if any gate fails. Gates that cannot be evaluated (the table is absent because the
arm legitimately does not produce it) are reported as SKIP, not PASS — silence is not
evidence.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pandas as pd

ACTION_TYPES = 6          # the operant table is snapshotted whole, one row per ActionType
MIN_EAT_PER_CREATURE = 10  # k = 1..10 must be measurable
# ArrowIpcBackend.DEFAULT_BATCH_ROWS — a trial cut off at its runtime cap loses up to this
# many rows per table, so cross-table counts cannot be expected to agree exactly.
ARROW_BATCH_ROWS = 4096
# A whole pilot completes in well under an hour, so trials written hours apart did not come
# from the same run. See G0.
MAX_MTIME_SPREAD_H = 6.0
# Arms are run one at a time so peak remote disk stays bounded, so a whole campaign
# legitimately spans many hours; only days indicate an arm left over from a previous build.
MAX_CAMPAIGN_SPAN_H = 48.0


class Gates:
    def __init__(self) -> None:
        self.rows: list[tuple[str, str, str]] = []   # (status, gate, detail)

    def check(self, gate: str, ok: bool, detail: str) -> None:
        self.rows.append(("PASS" if ok else "FAIL", gate, detail))

    def skip(self, gate: str, detail: str) -> None:
        self.rows.append(("SKIP", gate, detail))

    def report(self) -> int:
        width = max(len(g) for _, g, _ in self.rows)
        for status, gate, detail in self.rows:
            mark = {"PASS": "✓", "FAIL": "✗", "SKIP": "-"}[status]
            print(f"  {mark} {status:<4}  {gate:<{width}}  {detail}")
        failed = [g for s, g, _ in self.rows if s == "FAIL"]
        skipped = [g for s, g, _ in self.rows if s == "SKIP"]
        print()
        if failed:
            print(f"  {len(failed)} gate(s) FAILED: {', '.join(failed)}")
            print("  Do not submit the campaign until these pass.")
        else:
            print("  All evaluated gates pass.")
        if skipped:
            print(f"  {len(skipped)} skipped (table absent): {', '.join(skipped)}")
        return 1 if failed else 0


def per_trial(base: Path, arm: str, table: str) -> list[tuple[str, pd.DataFrame]]:
    """(trial_name, df) for every trial of one arm that has this table.

    Gates are evaluated per trial and an arm passes only if all of its trials do. Checking
    the concatenation instead lets one broken trial hide behind its healthy siblings —
    confirmed while testing this script: a trial whose memory decisions had collapsed to a
    single action passed G10 outright, because the other two trials supplied enough
    multi-action decisions to carry the arm's mean.
    """
    out = []
    for trial_dir in sorted((base / arm).glob("trial_*")):
        path = trial_dir / f"{table}.parquet"
        if path.exists():
            out.append((trial_dir.name, pd.read_parquet(path)))
    return out


def load(base: Path, arm: str, table: str) -> pd.DataFrame | None:
    """Concatenates a table across every trial of one arm; None if it exists nowhere.

    Only for gates that are genuinely per-arm questions. Anything that could be true of the
    arm but false of a trial must use per_trial() instead.
    """
    parts = per_trial(base, arm, table)
    if not parts:
        return None
    return pd.concat([df.assign(_trial=t) for t, df in parts], ignore_index=True)


def arms(base: Path) -> list[str]:
    return sorted(d.name for d in base.iterdir()
                  if d.is_dir() and list(d.glob("trial_*")))


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    base = Path(sys.argv[1])
    if not base.is_dir():
        print(f"no such data dir: {base}")
        return 2

    all_arms = arms(base)
    nomem = [a for a in all_arms if "nomem" in a]
    mem = [a for a in all_arms if "nomem" not in a]
    print(f"\n  {base}")
    print(f"  arms: {', '.join(all_arms)}\n")

    g = Gates()

    # G0 — every trial came from the SAME run. A data dir is written trial by trial and
    # never cleared first, so a re-run that dies partway leaves a directory holding some
    # fresh trials and some stale ones, and every gate below then silently averages across
    # two different builds of the simulator. Found the hard way: a checker run partway
    # through a pilot mixed one fresh trial with five from three days earlier, and reported
    # a confident G7 failure that was really the *previous* architecture's behaviour.
    # Scoped per arm, with a looser cross-arm bound. A campaign is now assembled by running
    # arms one at a time (scripts/ccad_run_arm.sh) so that peak remote disk stays bounded, which
    # means hours legitimately separate the first arm from the last — a data-dir-wide window
    # would fail every such campaign. Within an arm, though, trials still come from one array
    # job minutes apart, so a stale trial there is exactly the corruption G0 exists to catch:
    # a mid-run check once mixed one fresh trial with five written three days earlier and
    # reported a confident G7 failure that was really the previous architecture's behaviour.
    all_stamps = {}
    for arm in all_arms:
        stamps = {}
        for d in sorted((base / arm).glob("trial_*")):
            f = d / "creatures.parquet"
            if f.exists():
                stamps[d.name] = f.stat().st_mtime
        if not stamps:
            g.check("G0", False, f"{arm}: no extracted trials")
            continue
        all_stamps.update({f"{arm}/{k}": v for k, v in stamps.items()})
        spread_h = (max(stamps.values()) - min(stamps.values())) / 3600.0
        oldest = min(stamps, key=stamps.get)
        newest = max(stamps, key=stamps.get)
        g.check("G0", spread_h <= MAX_MTIME_SPREAD_H,
                f"{arm}: {len(stamps)} trials span {spread_h:.1f}h"
                + (f" — {oldest} is stale relative to {newest}; re-run the arm"
                   if spread_h > MAX_MTIME_SPREAD_H else ""))

    # Cross-arm: hours are expected, days are not — an arm left over from a previous campaign
    # would be a different build entirely.
    if all_stamps:
        span_h = (max(all_stamps.values()) - min(all_stamps.values())) / 3600.0
        g.check("G0-campaign", span_h <= MAX_CAMPAIGN_SPAN_H,
                f"all arms span {span_h:.1f}h"
                + (f" — {min(all_stamps, key=all_stamps.get)} predates "
                   f"{max(all_stamps, key=all_stamps.get)} by more than "
                   f"{MAX_CAMPAIGN_SPAN_H}h; verify they share an image"
                   if span_h > MAX_CAMPAIGN_SPAN_H else ""))

    # G1 — conditioning is written on the LEGACY valuation path too (expectancy is off
    # there, so ExpectancyState records nothing and this is the only evidence).
    for arm in nomem:
        cond = load(base, arm, "conditioning")
        if cond is None:
            g.check("G1", False, f"{arm}: conditioning.parquet missing entirely")
        else:
            g.check("G1", not cond.empty, f"{arm}: {len(cond)} conditioning rows")

    # G2 — the whole 6-action table is snapshotted per reinforcement event.
    for arm in all_arms:
        cond = load(base, arm, "conditioning")
        if cond is None or cond.empty:
            g.skip("G2", f"{arm}: no conditioning rows")
            continue
        per_event = cond.groupby(["_trial", "creature_key", "seq"]).size()
        bad = int((per_event != ACTION_TYPES).sum())
        g.check("G2", bad == 0,
                f"{arm}: {len(per_event)} events, {bad} not exactly {ACTION_TYPES} rows")

    # G3 — every EAT produces exactly one reinforcement, so reinforcements >= EAT always, with
    # any excess bounded by how much buffered data the trial lost when it ended.
    #
    # Strict equality holds ONLY for a trial that ends by all creatures dying: that shuts down
    # cleanly and flushes every table. A trial cut off at maxRuntimeMinutes loses each table's
    # buffered remainder (ArrowIpcBackend batches at 4096 rows), and the two tables lose
    # DIFFERENT amounts — conditioning writes 6 rows per event so it fills batches ~6x faster
    # than mouth_interactions and loses proportionally less. Measured on legacy_mem_simple,
    # which never dies: 103,588 reinforcements against 98,304 EAT rows, and 98,304 is exactly
    # 24 x 4096 — the truncation is visible in the number itself.
    #
    # This gate was briefly strict, generalised from the one arm that does terminate cleanly
    # (legacy_nomem: 126 vs 126). The bound below is mechanistic rather than a guessed
    # tolerance: at most 4096 rows can be in flight per table per trial.
    for arm in all_arms:
        cond = load(base, arm, "conditioning")
        mouth = load(base, arm, "mouth_interactions")
        if cond is None or mouth is None or cond.empty:
            g.skip("G3", f"{arm}: conditioning or mouth_interactions absent")
            continue
        n_events = cond.groupby(["_trial", "creature_key", "seq"]).ngroups
        n_eat = int((mouth["interaction_type"] == "EAT").sum())
        ratio = n_events / n_eat if n_eat else float("inf")
        n_trials = cond["_trial"].nunique()
        max_lost = ARROW_BATCH_ROWS * n_trials      # mouth_interactions rows possibly unflushed
        ok = n_events >= n_eat and (n_events - n_eat) <= max_lost
        g.check("G3", ok,
                f"{arm}: {n_events} reinforcements vs {n_eat} EAT (ratio {ratio:.3f}); "
                f"excess {n_events - n_eat} vs buffer bound {max_lost}")

    # G4 — the MEMORY filter really is in / out of the chain.
    for arm in nomem:
        md = load(base, arm, "memory_decisions")
        g.check("G4", md is None or md.empty,
                f"{arm}: expected no rows, got {0 if md is None else len(md)}")
    for arm in mem:
        md = load(base, arm, "memory_decisions")
        g.check("G4", md is not None and not md.empty,
                f"{arm}: {0 if md is None else len(md)} consultations")

    # G5 — engrams form in the no-memory arms too, which is what makes formation a
    # matched control and evocation the single difference within a pair.
    for arm in all_arms:
        eng = load(base, arm, "engrams")
        g.check("G5", eng is not None and not eng.empty,
                f"{arm}: {0 if eng is None else len(eng)} engrams")

    # G6 — birth+death rows collapsed to one per creature.
    for arm in all_arms:
        cr = load(base, arm, "creatures")
        if cr is None:
            g.check("G6", False, f"{arm}: creatures.parquet missing")
            continue
        dup = int(cr.groupby(["_trial", "creature_key"]).size().gt(1).sum())
        g.check("G6", dup == 0, f"{arm}: {len(cr)} rows, {dup} duplicated creatures")

    # G7 — k = 1..10 measurable for every creature.
    for arm in all_arms:
        mouth = load(base, arm, "mouth_interactions")
        if mouth is None:
            g.check("G7", False, f"{arm}: mouth_interactions.parquet missing")
            continue
        eats = (mouth[mouth["interaction_type"] == "EAT"]
                .groupby(["_trial", "creature_key"]).size())
        short = int((eats < MIN_EAT_PER_CREATURE).sum())
        g.check("G7", short == 0 and len(eats) > 0,
                f"{arm}: min {int(eats.min()) if len(eats) else 0} EAT/creature, "
                f"{short} below {MIN_EAT_PER_CREATURE}")

    # G8 — the censoring columns exist and cover survivors. Without them F5/P4 skips.
    # Per trial: a re-extracted trial sitting beside a stale one must not be averaged into
    # looking fine.
    for arm in all_arms:
        parts = per_trial(base, arm, "creatures")
        if not parts:
            g.check("G8", False, f"{arm}: creatures.parquet missing")
            continue
        stale = [t for t, df in parts if not {"died", "observed_s"} <= set(df.columns)]
        nulls = {t: int(df["observed_s"].isna().sum())
                 for t, df in parts if "observed_s" in df.columns}
        bad_nulls = {t: n for t, n in nulls.items() if n}
        deaths = sum(int(df["died"].sum()) for _, df in parts if "died" in df.columns)
        total = sum(len(df) for _, df in parts)
        if stale:
            g.check("G8", False,
                    f"{arm}: {len(stale)} trial(s) missing died/observed_s "
                    f"({', '.join(stale)}) — re-extract")
        else:
            g.check("G8", not bad_nulls,
                    f"{arm}: {deaths}/{total} died, "
                    + (f"null observed_s in {bad_nulls}" if bad_nulls
                       else "no null observed_s"))

    # G9 — the influence metric is well-formed. This is the only instrument for memory's
    # effect now that it seldom takes the selection credit.
    for arm in mem:
        parts = per_trial(base, arm, "memory_decisions")
        if not parts:
            g.check("G9", False, f"{arm}: no memory_decisions to validate")
            continue
        needed = {"object_type", "returned", "objects", "candidates", "scored", "decided"}
        problems = []
        for t, md in parts:
            missing = needed - set(md.columns)
            if missing:
                problems.append(f"{t}: missing {sorted(missing)}")
                continue
            if md.empty:
                problems.append(f"{t}: empty")
                continue
            counts = {
                "returned>candidates": int((md["returned"] > md["candidates"]).sum()),
                "decided!=narrowed": int((md["decided"]
                                          != (md["returned"] < md["candidates"])).sum()),
                "scored>objects": int((md["scored"] > md["objects"]).sum()),
                "named!=decided": int((md["decided"]
                                       != md["object_type"].notna()).sum()),
            }
            broken = {k: v for k, v in counts.items() if v}
            if broken:
                problems.append(f"{t}: {broken}")
        n_rows = sum(len(md) for _, md in parts)
        g.check("G9", not problems,
                f"{arm}: {n_rows} rows over {len(parts)} trials"
                + (f" — {'; '.join(problems)}" if problems else ", all invariants hold"))

    # G10 — memory both acts AND leaves the action choice open. returned == 1 everywhere
    # would mean it is still collapsing to a single action, i.e. the rework did not take.
    # Per trial, because an arm mean hides a single collapsed trial completely.
    for arm in mem:
        parts = per_trial(base, arm, "memory_decisions")
        if not parts:
            g.check("G10", False, f"{arm}: no usable memory_decisions")
            continue
        bad, summary = [], []
        for t, md in parts:
            if md.empty or "returned" not in md.columns:
                bad.append(f"{t}: unusable")
                continue
            influence = float(md["decided"].mean())
            multi = int((md["returned"] > 1).sum())
            summary.append(f"{t} {influence:.0%}/{multi}")
            if influence <= 0 or multi == 0:
                bad.append(f"{t}: influence {influence:.1%}, {multi} multi-action")
        g.check("G10", not bad,
                f"{arm}: influence/multi-action per trial [{', '.join(summary)}]"
                + (f" — FAILING {'; '.join(bad)}" if bad else ""))

    return g.report()


if __name__ == "__main__":
    sys.exit(main())
