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

## Extending the pipeline

These are code changes, not spec changes — you won't need a new experiment
spec field for either of them.

**Changing the Docker image** (new base image, added dependencies, etc.):
all three environments build from the same `docker/Dockerfile` —
`ansible/roles/image_local/tasks/main.yml`, `image_pi/tasks/main.yml`, and
`image_cefet/tasks/main.yml` all just run `mvn package` + `docker build -f
docker/Dockerfile` (or `buildx build --platform linux/arm64` for the Pi's
cross-build). Edit `docker/Dockerfile` directly; every environment picks it
up automatically next time `image.source: build` runs (the default — see
the schema above). If you need a genuinely different build invocation (e.g.
an extra `--build-arg`, or a second image variant), add it to the relevant
`image_*` role's `docker build`/`buildx build` task — there is currently no
per-experiment build-arg hook in the spec schema.

**Extracting a new kind of data**: `scripts/dl2l_data/tables.py` has a
`TABLES` dict, one entry per table: `name: (sql, post_process)`.
`dl2l_data.extract` iterates that dict generically, so adding a table means
adding one entry there (a SQL query against the `data` schema, plus an
optional post-processing function run on the raw rows — see
`_decode_object_type` for an example). No other file needs touching:
- `extract.tables: all` (the default) picks up the new table automatically.
- An experiment spec listing explicit `extract.tables` needs the new name
  added to that list too (`scripts/validate_experiment.py` will reject an
  unknown name either way, so a typo fails fast).

**Adding an analysis capability** (a new stat test, a new figure type): goes
in `analysis/dl2l_analysis/stats.py`, `figures.py`, or `loading.py` — whichever
fits — following the pattern of `cond_stats`/`kruskal_test`. Any
`analysis/experiments/<name>.py` module can then import and use it; nothing
in the runner (`analysis/dl2l_analysis/runner.py`) needs to change.

**Adding a new target environment** (a fourth cluster, a cloud VM, etc.): add
`ansible/inventories/<env>/` (with `hosts.yml` and `group_vars/all.yml`
setting `dl2l_env: <env>`), plus `ansible/roles/image_<env>/` and
`ansible/roles/trial_runner_<env>/`. `ansible/run-experiment.yml` resolves
both role names dynamically via `include_role: name: "image_{{ dl2l_env }}"`
/ `"trial_runner_{{ dl2l_env }}"` — it does not need editing. (Ansible's
static `roles:` play attribute can't do this kind of dynamic name
resolution from inventory vars, which is why `run-experiment.yml` uses
`include_role` tasks instead — keep that pattern for a new environment too.)

**Extending the experiment spec schema itself** (e.g. a new field like
`image.build_args` or `trial_timeout`): touches three places —
`scripts/validate_experiment.py` (validate the new field), the
default-application logic in `ansible/roles/experiment_spec/tasks/main.yml`
(`experiment_cfg` fact, if the field needs a default), and whichever
role/template actually consumes it (e.g. an `image_*` role, or
`common/templates/docker-compose.yml.j2`).

**A new extraction output format** (beyond parquet/csv): add a branch in
`scripts/dl2l_data/extract.py`'s `--format` handling (currently
`argparse.ArgumentParser`'s `choices=["parquet", "csv"]` plus the `save()`
call sites) and update the `format` field's allowed values in
`scripts/validate_experiment.py`.

**A new upload destination** (beyond HuggingFace): `scripts/dl2l_data/upload.py`
is HF-specific (built directly on `huggingface_hub.HfApi`) — there's no
pluggable-backend abstraction. A different destination means a new module
alongside it (e.g. `dl2l_data/upload_s3.py`) and a way for the spec's
`upload:` block or `ansible/roles/collect_upload/tasks/main.yml` to pick
which one to call; that indirection doesn't exist yet.

**Changing compose topology** (e.g. collapsing detector+holder onto one
container, as `holder_combines_detector` already does): the flag is read in
`ansible/roles/trial_runner_local/tasks/main.yml` and branched on in
`ansible/roles/common/templates/docker-compose.yml.j2`'s
`{% if holder_combines_detector %}` block. A similar experiment-level
boolean flag (set via `experiment_cfg.<flag> | default(false)`) is the
pattern to follow for other topology variants.
