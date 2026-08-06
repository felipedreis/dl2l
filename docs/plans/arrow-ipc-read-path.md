# Arrow IPC read path — PR 2 of the write-path refactor

## Context

`docs/plans/arrow-ipc-write-path.md` (PR 1, merged in #82) replaced the direct-Parquet write
path with off-heap Arrow IPC (`ArrowIpcBackend`), and switched `scripts/dl2l_data/extract.py`
to read `.arrow` instead of `.parquet` for its raw input. `tables.py`'s 15 SQL queries ran
unchanged through both write-path swaps and are DuckDB-backed either way, so their *output*
dtypes were already correct end-to-end before PR 1 too - what changed is that the underlying
storage is now natively typed (Arrow) rather than a CSV-era format that round-tripped some
columns as strings. PR 1's own local + CCAD validation
(`docs/reports/20260804_arrow_ipc_write_path_validation_report.md`) already confirmed empirically that
every downstream Parquet output column (`time`, `lifetime_s`, `born_time`, `seq`, `dopamine`,
`serotonin`, `orexin`, etc.) comes back as a native `int64`/`float64`, not `object`/string -
this plan is the follow-up cleanup PR 1 explicitly deferred.

This is PR 2, entirely on the read/analysis side. No Java changes, no write-path changes,
`tables.py`'s queries stay verbatim (per PR 1's own constraint, still true here).

## Scope

### RP1. `analysis/dl2l_analysis/loading.py` - `num()` coercions: investigated, kept

`num(s)` (`pd.to_numeric(s, errors="coerce")`) exists because "object dtype survives the
parquet round-trip for some columns" (its own docstring) - a CSV/psql-era artifact.
**Attempted removal, reverted after testing against real data**: freshly Arrow-extracted data
(`ml/data_arrow_ipc_write_path_ccad_validation/`) confirmed every relevant column natively
typed, but `loading.py`'s own docstring cites `data_rotten_fruit_v1` as one of its two source
scripts, and that dataset's `actions.time` is still `str` (pre-DuckDB-extractor data).
`dl2l_analysis` has no way to know which era a given `ml/data_*/` directory was extracted in,
and experiment reports routinely mix old and new data, so this stays. Not dead code - a
still-necessary compatibility shim for as long as un-re-extracted legacy data is in active use.

### RP2. `ml/scripts/prepare_dataset.py` - `_cast_numeric`: investigated, kept; added `--emit-arrow`

Same finding as RP1, confirmed more concretely: removing `_cast_numeric` and re-running
`python3 -m scripts.prepare_dataset --data data_datacollect_v2` **crashed** -
`pandas.errors.MergeError: Incompatible merge dtype ... both sides must have numeric dtype` -
`data_datacollect_v2/actions.time` is `str`. Reverted; `_cast_numeric` stays for the same
reason as `num()` above.

Added `--emit-arrow` (default off, independent of the above): when set, also writes
`train.arrow`/`val.arrow`/`train_dual.arrow`/`val_dual.arrow` alongside the existing
`.parquet` outputs, via `pyarrow.feather.write_feather(df, path, version=2)` (Feather v2 IS
the Arrow IPC file format - zero-copy-mmappable, unlike the write path's stream format which
is sequential-only, appropriate here since this is a finished, static training set read many
times, not an append-only log). Parquet stays the default/only output when the flag is off -
no change to existing callers (`upload_hf.py` uploads Parquet, unchanged, per PR 1's own
note). Verified: `.parquet` and `.arrow` outputs are byte-identical
(`pd.read_parquet(...).equals(pd.read_feather(...))`) against real `data_datacollect_v2`
output.

### RP3. `ml/jepa/dataset.py` - optional `.arrow` zero-copy fast path

`TrajectoryDataset.__init__` currently always does `pd.read_parquet`. When a same-named
`.arrow` file exists next to the requested `.parquet` path (i.e. RP2's `--emit-arrow` was
used), prefer it: `pyarrow.ipc.open_file(path).read_all()` (the File/random-access variant,
matching Feather v2 - not `open_stream`, which is PR 1's write-path format for a different
reason: sequential logging vs. finished-dataset random access) → per-column
`.to_numpy(zero_copy_only=False)` → `torch.from_numpy`. Falls back to the existing
`pd.read_parquet` path when no `.arrow` sibling exists (the common case until RP2's flag is
used) - fully backward compatible, opt-in only via file presence, no new CLI flag needed here
since the dataset already receives an explicit path.

### RP4. `scripts/dl2l_data/extract.py` - dedup `creatures` birth/death rows

Deferred from #81 (direct-Parquet write path never had upsert semantics either - see
`ArrowIpcBackend`'s javadoc). `CreatureState` is written twice per creature (birth: `deadtime=0`;
death: real `deadtime`) with the same `creature_key`/`creature_sequential`, and
`TABLES["creatures"]`'s query (kept verbatim) returns both rows - `manifest.json`'s
`n_creatures` is 2x the real count (confirmed live: "Found 20 creatures" for a 10-creature
trial in PR 1's own validation). Fix in `extract.py`'s `main()`, immediately after
`creatures_df = conn.execute(creatures_sql).df()` and before `n_creatures = len(creatures_df)`/
`save(...)` - not in `tables.py`, which stays untouched:

```python
creatures_df = (
    creatures_df.sort_values(["creature_key", "dead_time"])
    .groupby(["creature_key", "creature_sequential"], as_index=False)
    .tail(1)
    .sort_values("creature_key")
    .reset_index(drop=True)
)
```

Sorting ascending by `dead_time` and taking the last row per `(creature_key,
creature_sequential)` group keeps the death row (real `deadtime`) when one exists, or the sole
birth row when a creature was still alive at trial end (`dead_time=0`, no second row to begin
with) - correct either way, no special-casing needed. Re-sorts by `creature_key` afterward to
preserve the original query's `ORDER BY creature_key`.

**Gate**: re-run against PR 1's own CCAD validation raw data (still need to check whether it
was retained - if `--skip-backup` already discarded it, re-derive from a fresh local run) -
`n_creatures` must now equal the actual born-creature count (10 for the canonical conf), not
20.

### Not in scope

- `docs/plans/parquet-write-path.md` postscript and `docs/plans/arrow-ipc-write-path.md`
  itself: both already exist/were written as part of PR 1.
- `extract.py`'s module docstring: already rewritten in PR 1 to describe the Arrow write path
  accurately (checked - no stale Postgres/psql claims remain, only accurate past-tense history).
- Any Java/write-path change - this PR touches only `analysis/`, `ml/scripts/`, `ml/jepa/`,
  and `scripts/dl2l_data/extract.py`.

## Verification

- `PYTHONPATH=scripts python3 -m dl2l_data.extract ...` against a real raw dir (reuse a local
  canonical run) - confirm `n_creatures` is correct post-RP4.
- RP1/RP2 gates as described above - aggregate diff must be a no-op.
- `ml/jepa/dataset.py`: a small script constructing a `TrajectoryDataset` from both a
  `.parquet`-only dir and a dir with a sibling `.arrow` file (from `--emit-arrow`), asserting
  identical tensors either way.
- No Java tests affected (no Java changes) - `mvn test` not required to be re-run, but doesn't
  hurt to confirm nothing else on `main` regressed.
