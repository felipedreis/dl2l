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
ansible-playbook -i inventories/local train-model.yml   -e training=<name>     # then train (this Mac)
ansible-playbook -i inventories/ccad  train-model.yml   -e training=<name> -e ccad_user=<cpf>  # or on CCAD's GPUs
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

Same for local and CCAD — only the training step itself moves:

```
source_experiment's data_dir (already collected & synced to this Mac)
  → prepare_dataset.py (always local — pandas-only, no GPU needed) → prepared_dir
  → [local: train here]  OR  [ccad: sync prepared_dir + ml/ to the cluster, train there]
  → per variant: train_species.py → check_collapse.py (hard gate) → export_model.py
    (--out src/main/resources/models/<variant>/ — this IS "saved in the repo")
  → (ccad only) rsync exported <variant>/ dirs back to this Mac
  → upload_hf.py --ckpt src/main/resources/models --data prepared_dir
```

`check_collapse.py` is a hard gate (exit 1 = collapse detected) — if a variant
fails it, the run aborts there. Already-exported earlier variants keep their
files; fix hyperparameters and re-run with a narrowed `variants:` list.

## Training on CCAD

CEFET-MG's CCAD HPC cluster (https://www.ccad.cefetmg.br/guia/) — NOT the
same as `cluster.decom.cefetmg.br` (`inventories/cefet`, used only for
CPU-only simulation jobs) — is the actual GPU training target: 4x NVIDIA
L40S 48GB across nodes c1/c2, `--partition=gpu --qos=gpu_qos`, max 2 GPUs/user,
2-day time cap.

Login: `login.ccad.cefetmg.br`, authenticated with institutional credentials
(CPF, no punctuation, as username) — pass yours via `-e ccad_user=<cpf>`.
`$HOME` is a shared NFS filesystem visible from both the login node and every
compute node.

**One-time setup** (creates the `dl2l-jepa` conda env via `module load
miniforge3` + `mamba install torch numpy pandas pyarrow scikit-learn`,
mirroring `ml/requirements.txt` — only needs network access once, at
provisioning time, since the env lives on the shared NFS `$HOME`):

```bash
cd ansible && ansible-playbook -i inventories/ccad provision-ccad.yml -e ccad_user=<cpf>
```

Untested against the live cluster: if `provision-ccad.yml` or a training job
fails with `module: command not found`, `module load` is likely a shell
function only defined in login/interactive shells — see the caveat comment
at the top of `provision-ccad.yml` for the likely fix
(`source /etc/profile.d/modules.sh` or this cluster's equivalent).

**CCAD requires the CEFET VPN, which disconnects when idle** — a training
job can run for hours, far longer than the VPN tolerates a held-open SSH
session. So training on CCAD is submit-and-collect, not submit-and-wait:

```bash
# 1. Submit — syncs data, submits the SLURM job, and returns immediately.
#    The sbatch script trains/checks/exports every variant autonomously
#    under $HOME (shared NFS); no live connection is needed once submitted.
cd ansible && ansible-playbook -i inventories/ccad train-model.yml -e training=<name> -e ccad_user=<cpf>

# 2. Later, whenever you're back on the VPN — checks whether the job
#    finished (a DONE sentinel the job script writes on exit); if so, syncs
#    the exported models back and uploads them; if not, reports "still
#    running" and exits cleanly. Safe to run repeatedly.
ansible-playbook -i inventories/ccad train-model.yml -e training=<name> -e ccad_user=<cpf> -e rescue=true
```

If a job dies without the trap firing (OOM-killed, walltime exceeded, node
failure), the DONE sentinel never appears and `-e rescue=true` will keep
reporting "still running" — check `squeue -u <you>` on the login node or the
job's `slurm-<jobid>.out` log manually in that case.

Training on the Raspberry Pi cluster is not supported — there's no realistic
compute there for this workload.
