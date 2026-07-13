# Experiment specs

One YAML file per experiment. This is the single source of truth for both
deployment (`ansible/run-experiment.yml`) and analysis (`analysis/dl2l_analysis`).

Validate with:

```bash
python3 scripts/validate_experiment.py experiments/<name>.yml
```

## Schema

```yaml
name: <str>                    # required. Experiment identifier.
trials: <int>                  # required. Trials per condition, >= 1.

conditions:                    # required, non-empty list.
  - key: <str>                 #   required, unique. Matches simulations/*.conf
                                #   naming and the ml/data_<name>/<key>/ dir.
    simulation: <path>         #   required. Path to a simulations/*.conf,
                                #   relative to repo root. Must exist.
    label: <str>                #  required. Human-readable, used in figure
                                #   legends and report tables.
    color: <"#RRGGBB">          #  required. Hex color for plots.

image:                          # optional. Defaults: {source: build}
  source: build | registry      #   build: mvn package + docker build locally
                                 #   (or buildx on pi). registry: pull a
                                 #   pre-built tag from the per-env inventory
                                 #   group_vars instead of building.

data_dir: <path>                # required. Where extracted data is written,
                                 # relative to repo root, e.g. ml/data_<name>.

cond_dir:                       # optional. {condition_key: path, ...}
                                 # Per-condition override of data_dir, for
                                 # reading historical data that was split
                                 # across multiple ml/data_*/ roots (e.g. a
                                 # partial rerun). Only affects reads
                                 # (analysis); a new ansible run always
                                 # writes new data under data_dir.

extract:                        # optional. Defaults shown.
  format: parquet               #   parquet | csv
  backup: true                  #   also write a gzipped pg_dump per trial
  tables: all                   #   "all", or a list of table names from
                                 #   scripts/dl2l_data/tables.py:TABLES

upload:                         # optional. Defaults shown.
  enabled: true                 #   experiment data is never discarded by
                                 #   default — set false only for throwaway
                                 #   smoke/CI runs you don't want published.
  repo: felipedreis/dl2l-experiments   # HF dataset repo id
  prefix: <name>                #   HF path prefix; defaults to `name`

analysis:                       # optional. Omit if no analysis script exists
                                 # yet for this experiment.
  module: <str>                 #   matches analysis/experiments/<module>.py,
                                 #   which must define a `run(cfg)` function.
  tables: [<str>, ...]          #   optional hint: subset of tables the
                                 #   analysis module actually loads.
```

## Worked example

See `rotten_fruit_v1.yml` — 5 conditions, 5 trials, parquet extraction with
backups, all tables, uploaded to HuggingFace under `rotten_fruit_v1/`, and
analyzed by `analysis/experiments/rotten_fruit_v1.py`.

`20260709_memory_vs_wm_v1.yml` shows the `cond_dir` override in use, for an
experiment whose conditions 1-3 were rerun into a second data directory.

`smoke.yml` is the minimal case (1 condition, 2 trials, no `analysis` key) —
used to verify the deployment pipeline itself end-to-end.
