# JEPA Model Training Pipeline (Ansible-Orchestrated)

## Context

The experiment deployment refactor (docs/plans/experiment-infra-ansible-refactor.md, PR #69, still open) unified data collection onto Ansible + per-experiment YAML specs. Training JEPA world-model variants from that collected data is still a manual, undocumented process: `cd ml && python -m scripts.prepare_dataset ...` → `train_species.py` per variant → `check_collapse.py` → `export_model.py` → `upload_hf.py`, run by hand, with no automation and no support for running on a remote cluster.

The user wants this wired into the same pipeline: declare a training run (which experiment's data, which variants, hyperparameters), run it via ansible either locally or on the CEFET SLURM cluster, and have the result land in two places automatically — the exported model bundled into `src/main/resources/models/{variant}/` (so the next `mvn package` picks it up) and uploaded to the `felipedreis/dl2l-jepa` HF model repo. Training should be runnable standalone (iterate on hyperparams without recollecting data) and optionally chained right after a data-collection experiment run.

**Decisions already made (with the user):**
- Training environments: **local Mac only, or CEFET** (Pi is explicitly out — no realistic compute there).
- Trigger model: **both** — a standalone `training/<name>.yml` spec + `train-model.yml` playbook, AND an optional chain from `run-experiment.yml` via `-e train=true`.
- Default variant set: **all 5 known variants**, including `unified_critic` — it's in active use, not experimental. `train_species.py`/`export_model.py` already fully support it; `check_collapse.py` and `upload_hf.py` don't yet (see next point).
- **Three real gaps found and to be fixed as part of this work**:
  1. `ml/scripts/prepare_dataset.py` currently only ever writes `train_dual.parquet`/`val_dual.parquet`, but `train_species.py --variant single` needs plain `train.parquet`/`val.parquet`, which nothing produces today — `single`-variant training is currently broken. Fix: also write the plain files (same rows, minus the 8 `INTERNAL_STATE_FEATURE_ORDER` columns) — confirmed safe via `jepa/dataset.py`'s `TrajectoryDataset(dual=False)` path, which never touches those columns.
  2. `ml/scripts/check_collapse.py`'s `--variant` choices and model-construction branch are missing `unified_critic` (only `single|dual|internal_critic|internal_predictor`). Fix is mechanical and low-risk — `export_model.py` already has the exact pattern to mirror: add `"unified_critic"` to `choices`, add it to the `dual_variant` tuple, import `InternalCriticUnifiedModel` (confirmed it takes the identical `dual_kwargs` shape as the other dual variants), add the `elif` branch.
  3. `ml/scripts/upload_hf.py`'s hardcoded `VARIANTS` list is missing `unified_critic` too — one-line fix (add it to the list), otherwise a trained+exported `unified_critic` would silently never get uploaded.
- **CEFET's Python/PyTorch environment is unknown** (grepped repo + full git history of the deleted CEFET scripts — no trace of a module/conda/venv convention). Modeled as an explicit open seam, exactly like `trial_runner_cefet/tasks/db_setup.yml`'s postgres gap: a `cefet_python_strategy: todo` var + a fail-fast task, blocking before any sync/submit happens.

## Design

### Data flow (same for both environments — only the training step itself moves)

```
source_experiment's data_dir (already collected & synced to this Mac)
  → prepare_dataset.py (ALWAYS LOCAL — pandas-only, no GPU needed) → prepared_dir (local)
  → [local: train here] OR [cefet: sync prepared_dir + ml/ to cluster, train there]
  → per variant: train_species.py → check_collapse.py (hard gate, exit 1 = abort) → export_model.py
  → (cefet only) rsync exported {variant}/ dirs back to this Mac
  → export_model.py's --out is ALWAYS src/main/resources/models/{variant}/ directly (no separate
    checkpoint-then-copy step) — this IS "saved in the repo"
  → upload_hf.py --ckpt src/main/resources/models --data prepared_dir  (same shape upload_hf.py
    already expects; no changes needed to that script)
```

Key simplification: `upload_hf.py` already expects `--ckpt <root>` containing `{variant}/*.pt + *.json` — exactly the shape `src/main/resources/models/` already has. So export writes directly there; no separate "training checkpoint dir" → "repo models dir" copy step is needed.

On collapse (check_collapse exits 1) or any script failure, the play aborts at that variant — no partial-success bookkeeping. Already-exported earlier variants keep their files; fix hyperparams and re-run with a narrowed `variants:` list.

### New experiment spec field (chaining)

`experiments/<name>.yml` gains one optional field: `training: <str>` (bare name of a `training/<name>.yml`). No sub-schema — the training spec is the single source of truth for hyperparams/variants.

### New files

```
training/
├── README.md                     # schema + worked example + CEFET seam note
└── <name>.yml                    # one per training run, e.g. p9_variants.yml

scripts/validate_training.py       # mirrors validate_experiment.py's validate()/main() shape

ansible/
├── train-model.yml                # entry point, 3 plays (mirrors run-experiment.yml)
└── roles/
    ├── training_spec/tasks/main.yml      # load+validate training/<name>.yml, resolve
    │                                     # source_experiment's data_dir, apply defaults
    ├── prepare_dataset/tasks/main.yml    # localhost-only: python -m scripts.prepare_dataset
    ├── train_local/tasks/main.yml        # loop variants: train_species → check_collapse → export_model,
    │                                     # --out src/main/resources/models/{variant} directly
    ├── train_cefet/
    │   ├── tasks/main.yml                # include python_setup.yml seam first; sync; template+submit
    │   │                                 # sbatch job; rsync exported dirs back
    │   ├── tasks/python_setup.yml        # THE SEAM — cefet_python_strategy: todo → fail
    │   └── templates/train_variants.sh.j2  # sbatch script looping variants sequentially
    ├── train_pi/tasks/main.yml            # one-task fail: "not supported, use local or cefet"
    └── upload_models/tasks/main.yml       # localhost-only: python -m scripts.upload_hf
```

### Modified files

- **`ml/scripts/prepare_dataset.py`** — after building `df_train`/`df_val`, also write `train.parquet`/`val.parquet` with `INTERNAL_STATE_FEATURE_ORDER` columns dropped (in addition to the existing `_dual` writes). Update docstring.
- **`ml/scripts/check_collapse.py`** — add `unified_critic` support: `choices` list, `dual_variant` tuple, import `InternalCriticUnifiedModel` from `jepa.model`, add the matching `elif` branch (mirrors `export_model.py`'s existing branch exactly).
- **`ml/scripts/upload_hf.py`** — add `"unified_critic"` to the module-level `VARIANTS` list.
- **`experiments/README.md`** — one line in the schema block for the new optional `training: <str>` field, pointing to `training/README.md`.
- **`scripts/validate_experiment.py`** — validate the optional `training` field (string; `training/{value}.yml` must exist).
- **`ansible/roles/experiment_spec/tasks/main.yml`** — add `training` to the defaults-merge (`_experiment_raw.training | default(None)`).
- **`ansible/roles/collect_upload/tasks/main.yml`** — add a chained-training task pair (mirrors the existing analysis-chaining pattern exactly): when `-e train=true` and `experiment_cfg.training` is set, shell out via `ansible.builtin.command` to `ansible-playbook -i inventories/{{ dl2l_env }} train-model.yml -e training={{ experiment_cfg.training }}` (this is the only way to conditionally run a whole separate playbook from inside a task — Ansible has no cleaner primitive); paired debug task for "requested but not configured."
- **`CLAUDE.md`** — replace the manual `cd ml && python3 -m scripts.upload_hf ...` instructions in "JEPA World Model" with the new `ansible-playbook train-model.yml` workflow; note the `prepare_dataset.py` fix; note CEFET training is blocked on the python-env seam.

### `training/<name>.yml` schema

```yaml
name: <str>                      # required
source_experiment: <str>         # required — must reference experiments/<name>.yml
prepared_dir: <path>             # optional, default ml/data_prepared_<name>
ckpt_dir: <path>                 # optional, default ml/checkpoints_<name> (train_cefet's remote staging;
                                  # train_local uses it only as train_species.py's --ckpt, export always
                                  # goes straight to src/main/resources/models/{variant})
variants: [single, dual, internal_critic, internal_predictor, unified_critic]   # optional, default = all 5
epochs: <int>                    # optional, default 100 (matches train_species.py's own default)
hyperparams: {batch, lr, sigreg, crit, ema, freeze_encoder}      # optional passthrough, all optional
device: cpu | cuda | mps         # optional; omit to let train_species.py auto-detect
upload:
  enabled: true                  # default true — never skip for a real run
  repo: felipedreis/dl2l-jepa
  data_repo: felipedreis/dl2l-experiments
  data_prefix: <str>             # default = source_experiment name
```

`scripts/validate_training.py` checks: required keys; `source_experiment` references an existing `experiments/*.yml` file (existence only — NOT whether its data_dir has been collected yet, that's a runtime precondition checked by `training_spec`'s ansible role, not a spec-authoring-time one); `variants` ⊆ the 5 allowed names; basic type checks on `epochs`/`hyperparams`/`device`/`upload`.

## Verification

1. `python3 -m py_compile scripts/validate_training.py`.
2. Run the fixed `prepare_dataset.py` against real existing data (`ml/data_rotten_fruit_v1/`) — confirm both `train.parquet`+`val.parquet` (plain, no internal-state columns) and `train_dual.parquet`+`val_dual.parquet` are produced with matching row counts.
3. `ansible-playbook --syntax-check -i ansible/inventories/local ansible/train-model.yml -e training=<name>`.
4. **Live local smoke run**: a throwaway `training/smoke.yml` (`source_experiment: rotten_fruit_v1`, `epochs: 2`, `variants: [single, unified_critic]` — deliberately covers both fixes: `single` proves the `prepare_dataset.py` plain-file fix, `unified_critic` proves the `check_collapse.py`/`upload_hf.py` fixes — **`upload.enabled: false`** — do not pollute the real `felipedreis/dl2l-jepa` repo with a junk 2-epoch model) run for real via `ansible-playbook -i inventories/local train-model.yml -e training=smoke`. Confirm: prepare_dataset produces both file pairs, training runs for both variants, check_collapse executes for both including the new `unified_critic` branch (pass/fail either is fine — this validates pipeline mechanics, not model quality), `src/main/resources/models/{single,unified_critic}/model_contract.json` appear and parse. Delete the smoke training spec + its checkpoint/prepared dirs afterward (or gitignore them) — don't leave clutter.
5. `--syntax-check` + `--check` for `-i ansible/inventories/cefet` — confirm the python-env seam fails fast (`unreachable=0`, same proof pattern as the postgres seam) before any sync/submit is attempted.
6. `--check` on `run-experiment.yml -e experiment=<spec-with-training-field> -e train=true` — confirm the chaining task's `when:` evaluates true and the assembled `ansible-playbook train-model.yml ...` command is correctly formed (check mode won't actually execute it).

## Addendum: CEFET training seam resolved → CCAD, submit/rescue model

The "unknown Python/PyTorch environment" seam above was resolved by reading CEFET's
CCAD HPC cluster user guide (https://www.ccad.cefetmg.br/guia/). Two corrections to
the original plan:

1. **Wrong cluster.** `cluster.decom.cefetmg.br` (`ansible/inventories/cefet`) turned
   out to be CPU-only and used solely for simulation jobs (`lsi-1`/`part6h`) — it was
   never the right target for GPU training. The actual GPU cluster is CCAD
   (`login.ccad.cefetmg.br`, `--partition=gpu --qos=gpu_qos`, 4x L40S 48GB), a
   *separate* cluster with its own inventory (`ansible/inventories/ccad/`) and role
   (`roles/train_ccad/`). `roles/train_cefet/` (and its now-moot python_setup seam)
   was deleted. Environment: `module load miniforge3` → conda env in `$HOME/.conda/envs/`
   (shared NFS, so provisioning — `provision-ccad.yml` — only needs network access
   once, at env-creation time; training jobs on compute nodes need no network).

2. **Submit/rescue, not submit-and-wait.** CCAD requires the CEFET VPN, which drops
   the connection when idle — incompatible with `sbatch --wait` + async/poll (the
   pattern used for the Pi cluster's SLURM jobs), since a training job can run for
   hours. `roles/train_ccad` instead: submits the job (plain `sbatch`, no `--wait`)
   and returns immediately (`submit.yml`); the sbatch script
   (`templates/train_variants.sh.j2`) trains/checks/exports every variant
   autonomously under `$HOME`, with a `trap ... EXIT` writing a `DONE` sentinel file
   (its own exit code) regardless of success or failure. A later, separate
   invocation with `-e rescue=true` (`rescue.yml`) checks for that sentinel; if
   present, syncs the exported models back and lets `train-model.yml`'s upload play
   proceed; if absent, reports "still running" and exits cleanly — safe to re-run
   whenever back on the VPN. `train-model.yml` gates its upload play on a
   `training_finished` fact (always `true` for local training, which completes
   synchronously within one invocation).

Known limitation: if a CCAD job dies without the trap firing (OOM-kill, walltime
exceeded, node failure), the DONE sentinel never appears and `-e rescue=true` will
report "still running" forever — check `squeue`/`sacct` or the job's
`slurm-<jobid>.out` manually in that case.

## Housekeeping note

PR #69 (the original ansible refactor) is still open on `feat/experiment-infra-ansible-refactor`. This work continues as additional commits on that same branch/PR unless told otherwise.
