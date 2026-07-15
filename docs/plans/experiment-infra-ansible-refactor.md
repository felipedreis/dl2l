# Experiment Infrastructure Refactor: Ansible-Based Unified Deployment

## Context

Experiment setup has accreted into: **46 docker-compose files** in `docker/` (40+ differ *only* in the `SIMULATION` env var repeated in 3 services), **~10 `run_exp_*.sh` scripts** duplicating the same ~40-line trial loop (compose up → `docker wait` holder → extract → down), **three divergent cluster paths** (legacy CEFET sbatch scripts; a Pi SLURM-array path whose job script `dl2l_trial.sh` lives only on the cluster, not in the repo; an ad-hoc SSH fan-out script `run_exp_p7_pi.sh`), **two extractors** (`exp_extract.py`, `pg_extract.py`) with duplicated psql helpers, and **two big analysis scripts** sharing a ~120-line verbatim scaffold.

Goal: one experiment = one YAML spec; one `ansible-playbook` command runs it on any of three environments (local macbook / Pi cluster with SLURM+docker / CEFET cluster with SLURM only); extraction and analysis become reusable Python packages. Modeled on the user's d-optimas ansible setup (two-role provision/configure split, shared jinja2 templates, few-vars-per-experiment), but data-driven (spec files) instead of one-playbook-per-experiment.

**User decisions:** delete legacy files (git history preserves them); ansible for all three envs including local; analysis lib included in scope; one YAML spec per experiment. **CEFET postgres is an open seam** — design the interface, don't solve it.

## New layout

```
ansible/
├── ansible.cfg  requirements.yml          # community.docker, ansible.posix
├── run-experiment.yml                     # THE entry point (3 plays)
├── build-image.yml  provision-pi.yml      # standalone helpers
├── inventories/{local,pi,cefet}/
│   ├── hosts.yml                          # local: localhost(local conn); pi: 192.168.1.200 controller + .201-203 workers; cefet: cluster.decom.cefetmg.br:2200
│   └── group_vars/all.yml                 # dl2l_env, dl2l_image, dl2l_config_dir, publish_ui (true only local), registry/shared_fs (pi), slurm partition/qos + cefet_db_strategy: todo (cefet)
└── roles/
    ├── experiment_spec/                   # include_vars experiments/<name>.yml + run validator
    ├── image_local/  image_pi/            # mvn+docker build | arm64 buildx push to registry + worker pull
    ├── trial_runner_local/                # matrix loop → one_trial.yml (up/wait/extract/down)
    ├── trial_runner_pi/                   # deploy to /mnt/dl2l-shared, sbatch --array per condition, sync data back
    │   └── templates/dl2l_trial.sh.j2     # the missing Pi job script, now in-repo
    ├── trial_runner_cefet/                # jar+conf sync, templated sbatch chain; db_setup.yml = fail-with-message seam
    │   └── templates/{l2l_job.sh.j2, submit_experiment.sh.j2}
    ├── collect_upload/                    # HF upload + optional analysis trigger (localhost)
    └── common/templates/docker-compose.yml.j2   # replaces all 46 compose files
experiments/
├── README.md                              # spec schema docs
├── smoke.yml                              # 1 cond × 2 trials, exp_p58_smoke.conf — verification workhorse
├── rotten_fruit_v1.yml  20260709_memory_vs_wm_v1.yml
scripts/dl2l_data/                         # unified extraction package
├── db.py  tables.py  extract.py  upload.py  manifest.py
scripts/validate_experiment.py
analysis/dl2l_analysis/                    # shared analysis package
├── config.py  loading.py  stats.py  figures.py  report.py  runner.py
analysis/experiments/                      # per-experiment custom figures only
analysis/legacy/                           # old CSV-era scripts moved here
```

## Experiment spec (single source of truth for deployment AND analysis)

```yaml
# experiments/rotten_fruit_v1.yml
name: rotten_fruit_v1
trials: 5
conditions:
  - {key: 1_baseline, simulation: simulations/rotten_fruit_v1_1_baseline.conf, label: Baseline, color: "#9e9e9e"}
  - {key: 2_memory_only, simulation: simulations/rotten_fruit_v1_2_memory_only.conf, label: Memory, color: "#5c85d6"}
  # ... conditions 3-6
image: {source: build}            # build | registry (per-env tag from inventory group_vars)
data_dir: ml/data_rotten_fruit_v1
extract: {format: parquet, backup: true, tables: all}
upload: {enabled: true, repo: felipedreis/dl2l-experiments, prefix: rotten_fruit_v1}   # never discard data
analysis: {module: rotten_fruit_v1, tables: [creatures, drives, actions, mouth_interactions, ...]}
```

`label`/`color` replace the `CONDITIONS`/`PALETTE` constants duplicated in analysis scripts. `scripts/validate_experiment.py` checks: required keys, sim conf files exist, unique condition keys, analysis module exists.

## Usage (environment = inventory, experiment = extra-var)

```bash
ansible-playbook -i ansible/inventories/local ansible/run-experiment.yml -e experiment=rotten_fruit_v1
ansible-playbook -i ansible/inventories/pi    ansible/run-experiment.yml -e experiment=rotten_fruit_v1 [-e analyze=true]
```

`run-experiment.yml` — 3 plays (d-optimas provision/configure split adapted):
1. **localhost**: load+validate spec → `image_{{ dl2l_env }}` (skipped for cefet or `image.source: registry`)
2. **runner** group (local: localhost / pi: controller / cefet: login): `trial_runner_{{ dl2l_env }}`
3. **localhost**: pull data (pi), HF upload via `dl2l_data.upload`, optional `python3 -m dl2l_analysis`

## Key components

### One compose template (`docker-compose.yml.j2`)
Based on `docker/docker-compose-pi.yml`'s `${...}` parameterization. Jinja handles per-env/per-experiment variation (image, config dir, `{% if publish_ui %}` → 8080 mapping — **local inventory sets `publish_ui: true`** per the local-UI convention; pi/cefet publish nothing); compose `${SIMULATION:?}` env substitution handles per-trial variation so one rendered file serves a whole SLURM array. Changes vs today: drop `container_name: db` (extractor gets container id via `docker compose -p <proj> ps -q dl2l-db` — removes the one-experiment-per-host restriction); optional `holder_combines_detector` flag for the collapsed 3-container topology some experiments use.

### `trial_runner_local`
Renders the compose file once, loops `conditions × range(trials)`; `one_trial.yml` mirrors the current shell loop exactly: `down -v` (cleanup) → `up -d` → `ps -q dl2l-holder`/`dl2l-db` → `docker wait` (async 21600) → `python3 -m dl2l_data.extract --experiment X --condition C --trial T --out DATA_DIR --container <id>` → `down -v`. Project name `exp_<name>_<cond>_t<trial>` (current convention).

### `trial_runner_pi` + `dl2l_trial.sh.j2`
Deploys to `/mnt/dl2l-shared/`: rendered compose, config files, templated `dl2l_trial.sh` → `jobs/`, `dl2l_data/` package → `scripts/`. Per condition: `sbatch --wait --array=1-{{trials}} --exclusive --export=ALL,EXPERIMENT=..,CONDITION=..,SIMULATION=..,DL2L_IMAGE=..,SHARED_FS=..` (async task). `--exclusive` replaces the hand-rolled batches-of-4 in `run_exp_p7_pi.sh`; SLURM handles node fan-out. Job script: compose up with unique `-p`, `docker wait`, extract node-side (`PYTHONPATH=$SHARED_FS/scripts`, `--docker-cmd "sudo docker"` flag on extract.py for the Pi's sudo-docker setup), `trap 'compose down -v' EXIT`. Play 3 rsyncs `shared_fs/data/<exp>/` back. `image_pi`: `docker buildx --platform linux/arm64 --push` to `192.168.1.200:5000` + pull on workers. One-time `provision-pi.yml`: pandas/pyarrow on nodes, shared-FS dirs.

### `trial_runner_cefet` (seam open)
Syncs fat jar + confs to login node; templates modernized `l2l_job.sh.j2` (parameterized qos/partition, exports `DL2L_DB_URL` for the future) and `submit_experiment.sh.j2` (per-role sbatch with `--dependency=afterany:` chaining, from `deploy.sh`). `tasks/main.yml` starts with `db_setup.yml` = a clear `fail:` message ("CEFET postgres strategy not implemented — see follow-up plan") guarded by `cefet_db_strategy == 'todo'` in group_vars. Note for the future task: Java hardcodes JDBC URL in `META-INF/persistence.xml`; reading `DL2L_DB_URL` is the one Java-side change belonging there. Path is exercised via `--syntax-check`/`--check` only.

### `scripts/dl2l_data/` (extraction unification)
- `db.py`: the duplicated `psql_copy`, `decode_type_hex`, `KNOWN_TYPES`, pg_dump helper — once.
- `tables.py`: registry of the 15 table queries from `exp_extract.py` as `{name: (sql, post_fn)}`.
- `extract.py`: CLI keeping `exp_extract.py`'s exact interface + `--format parquet|csv` + `--docker-cmd`; identical output layout (`<out>/<cond>/trial_N/*.parquet`, `db_backup.sql.gz`, `manifest.json`).
- `upload.py`: `exp_upload.py` moved in, honors spec `upload.prefix`.
- `pg_extract.py` **kept** (its per-creature CSV format mirrors the Java extractors) but imports from `dl2l_data.db`.

### `analysis/dl2l_analysis/`
- `config.py`: `ExperimentAnalysis.from_spec(name)` reads the experiment YAML → conditions/labels/colors/trials/data_dir/fig_dir/report_dir.
- `loading.py`: `load_all` (conditions×trials parquet loop), `num`, enrichment scaffold (born_time/elapsed_s merge, tick_rank, drive coercion).
- `stats.py`: `cond_stats`, `kruskal_test` (KW + Bonferroni Mann-Whitney), decile helpers.
- `figures.py`: Agg setup, palette-aware save-to-FIG_DIR, recurring plot builders.
- `report.py`: markdown skeleton with mandated sections (Purpose/Assumptions/Hypothesis/Results/Analysis).
- `runner.py`: `python3 -m dl2l_analysis --experiment <name>` → imports `analysis/experiments/<module>.py`, calls `run(cfg, data)`.
- Port `exp_rotten_fruit_v1.py` and `exp_20260709_memory_vs_wm_v1.py` to `analysis/experiments/` (custom figures only); old monoliths are the parity reference, then deleted. Other CSV-era scripts → `analysis/legacy/` untouched.

## Deletions (after parity proven; git history preserves)

- `docker/docker-compose-*.yml` — all 44 experiment/pi variants. **Keep** `docker/docker-compose.yml` (dev convenience) + `Dockerfile`.
- `scripts/`: all `run_exp_*.sh`, `run_datacollect_*.sh`, `run_p59_trials.sh`, `run_phase2_trials.sh`, `run_after_cond7.sh`, `run_full_cond7_and_rotten.sh`, `run_rotten_fruit_v1_new_conds.sh`, `submit_exp.sh`, legacy CEFET `deploy.sh`/`l2l_job.sh`/`copyToCluster.sh`/`cancelJobs.sh`, stray `*.log` files, `exp_extract.py`, `exp_upload.py`.
- `analysis/exp_rotten_fruit_v1.py`, `analysis/exp_20260709_memory_vs_wm_v1.py`.
- **Keep**: `scripts/{manager,provider,detector,holder,single,help}.sh`, `run-dl2l.sh` (image entrypoint), CI workflows.

**CLAUDE.md**: rewrite Build & Run (compose section → ansible command), Data Analysis (→ `dl2l_analysis`), Development cycle 5d/5e/5i (→ experiment specs + playbook + `dl2l_data.upload`), document `experiments/` schema and the local-UI convention.

## Implementation phases (each independently verifiable)

| # | Deliverable | Verification |
|---|---|---|
| 0 | Copy this plan to `docs/plans/experiment-infra-ansible-refactor.md` | — |
| 1 | `scripts/dl2l_data/`; refactor `pg_extract.py` onto it | Bring up one smoke sim manually; run old `exp_extract.py` and `python3 -m dl2l_data.extract` against the same live db; diff file lists, parquet schemas, row counts (must be identical) |
| 2 | `analysis/dl2l_analysis/` + ported `analysis/experiments/rotten_fruit_v1.py`; move legacy scripts | Run against existing `ml/data_rotten_fruit_v1/`; figure count matches, spot-check 3 statistics (KW p-values, median lifetimes) equal old output |
| 3 | `experiments/` specs (smoke, rotten_fruit_v1, 20260709_memory_vs_wm_v1) + validator | Validator passes on all, fails on a deliberately broken spec |
| 4 | Ansible skeleton + local path (spec role, image_local, trial_runner_local, compose template, collect_upload) | `ansible-lint` + `--syntax-check`; full smoke run: `ansible-playbook -i inventories/local run-experiment.yml -e experiment=smoke` — compare output tree/columns to existing `ml/data_p58_smoke/`; UI reachable on :8080 mid-run; HF upload lands |
| 5 | Pi path (provision-pi, image_pi, trial_runner_pi, dl2l_trial.sh.j2) | `--syntax-check` + `--check` offline; render templates to scratchpad + `bash -n`; when cluster reachable: provision + smoke (1 cond × 2 trials) — array runs on 2 nodes in parallel, data syncs back |
| 6 | CEFET skeleton with open db seam | `--syntax-check`; `--check` shows seam `fail` fires with clear message; templates `bash -n` clean |
| 7 | Deletions + CLAUDE.md rewrite + `experiments/README.md` | `grep -r docker-compose-exp` empty; re-run Phase 4 smoke after deletions |

Sequencing: Python packages first (testable against existing data, zero infra risk) → specs → local ansible (proves the whole pipeline) → clusters → deletion last (old path stays as fallback until new one is proven).

## Critical reference files

- `docker/docker-compose-pi.yml` — basis of the compose template (env-substitution pattern)
- `scripts/exp_extract.py` — table queries/manifest logic to lift into `dl2l_data`
- `scripts/run_exp_p7_pi.sh` — Pi semantics (sudo docker, registry, backup-first) for `trial_runner_pi`
- `analysis/exp_rotten_fruit_v1.py` — scaffold to extract into `dl2l_analysis`; parity reference
- `~/IdeaProjects/d-optimas/ansible/` — reference patterns (role split, template sharing)
