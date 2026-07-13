# Training specs

One YAML file per training run. A training spec describes *how* to train JEPA
world-model variants from an experiment's already-collected data — it does
not collect data itself (that's an `experiments/<name>.yml` + `run-experiment.yml`
concern; see `experiments/README.md`).

Validate with:

```bash
python3 scripts/validate_training.py training/<name>.yml
```

Run with:

```bash
cd ansible
ansible-playbook -i inventories/local run-experiment.yml -e experiment=<name>   # collect data first, if not done yet
ansible-playbook -i inventories/local train-model.yml   -e training=<name>     # then train
```

Training can also be chained directly onto a data-collection run: set the
experiment spec's optional `training: <name>` field (see `experiments/README.md`),
then pass `-e train=true` to `run-experiment.yml`.

## Schema

```yaml
name: <str>                      # required. Training run identifier.
source_experiment: <str>         # required. Name of an experiments/<name>.yml
                                  # whose data_dir supplies the raw parquet
                                  # (already-collected — see experiments/README.md).

prepared_dir: <path>              # optional. Where prepare_dataset.py writes its
                                   # output, relative to repo root.
                                   # Default: ml/data_prepared_<name>

ckpt_dir: <path>                  # optional. Where train_species.py writes checkpoints
                                   # (best.pt, final.pt, stats.json) per variant,
                                   # relative to repo root. Default: ml/checkpoints_<name>
                                   # Exported models always go to
                                   # src/main/resources/models/<variant>/ regardless of
                                   # this setting — that's the "save in the repo" step.

variants:                         # optional. Default: all 5 known variants.
  - single                        #   world-only
  - dual                          #   world + internal state
  - internal_critic                #   dual + internal-aware critic
  - internal_predictor              #   dual + internal-aware predictor
  - unified_critic                   #   internal_critic with merged predictor+critic

epochs: <int>                      # optional. Default: 100 (train_species.py's own default).

hyperparams:                       # optional. Passthrough to train_species.py; all optional.
  batch: <int>                     #   default 256
  lr: <float>                      #   default 1e-3
  sigreg: <float>                  #   default 0.1
  crit: <float>                    #   default 1.0
  ema: <float>                     #   default 0.996 (0 disables EMA)
  freeze_encoder: <bool>           #   default false

device: cpu | cuda | mps           # optional. Omit to let train_species.py auto-detect
                                    # (cuda > mps > cpu). This Mac has mps available.

upload:                             # optional. Defaults shown.
  enabled: true                    #   never discard a real trained model — set false
                                    #   only for throwaway smoke-test runs.
  repo: felipedreis/dl2l-jepa        #   HF model repo id
  data_repo: felipedreis/dl2l-experiments  # HF dataset repo id
  data_prefix: <name>                #   HF dataset path prefix; defaults to `name`
```

## Pipeline

Same for local and CEFET — only the training step itself moves:

```
source_experiment's data_dir (already collected & synced to this Mac)
  → prepare_dataset.py (always local — pandas-only, no GPU needed) → prepared_dir
  → [local: train here]  OR  [cefet: sync prepared_dir + ml/ to cluster, train there]
  → per variant: train_species.py → check_collapse.py (hard gate) → export_model.py
    (--out src/main/resources/models/<variant>/ — this IS "saved in the repo")
  → (cefet only) rsync exported <variant>/ dirs back to this Mac
  → upload_hf.py --ckpt src/main/resources/models --data prepared_dir
```

`check_collapse.py` is a hard gate (exit 1 = collapse detected) — if a variant
fails it, the run aborts there. Already-exported earlier variants keep their
files; fix hyperparameters and re-run with a narrowed `variants:` list.

## Known gap: CEFET's Python/PyTorch environment

Nothing in this repo (or its git history) indicates what Python/conda/PyTorch
setup is available on the CEFET cluster. Training there is blocked behind an
explicit seam — `ansible/roles/train_cefet/tasks/python_setup.yml` fails fast
with `cefet_python_strategy: todo` (set in
`ansible/inventories/cefet/group_vars/all.yml`) before any sync or submit
happens, exactly like `trial_runner_cefet`'s postgres seam. Resolving this
(confirming what `module avail`/conda/venv setup exists, or provisioning one)
is a follow-up task.

Training on the Raspberry Pi cluster is not supported — there's no realistic
compute there for this workload.
