# Run DL2L Experiments on CCAD via Singularity (replaces the dead-cluster CEFET seam)

## Context

The original ansible refactor left CEFET data-collection blocked behind two seams: no Docker
on `cluster.decom.cefetmg.br`, hence no postgres. Since then: **that cluster no longer exists**
— "CEFET's cluster" now always means CCAD (`login.ccad.cefetmg.br`), which we already wired up
for JEPA training. CCAD *can* run containers via Singularity/Apptainer, and the user wants
experiment execution (data collection) adapted to run there via containers, replacing the old
bare-metal-Java SLURM model (`l2l_job.sh`/`deploy.sh`-derived `trial_runner_cefet`) entirely.

**Direct answer already given to the user:** docker-compose itself cannot run under Singularity
(no compose equivalent, no bridge networking/service-DNS, no `depends_on`/healthchecks) — this
requires a purpose-built Singularity orchestration script, not a literal reuse of
`docker-compose.yml.j2`.

## Key technical facts established

- `ansible/inventories/cefet/` and `ansible/roles/trial_runner_cefet/` + `roles/image_cefet/`
  target the dead cluster — **delete entirely**.
- `ansible/inventories/ccad/` already exists (built for training) — **extend** it with a second
  role for running experiments, rather than creating a new inventory. `dl2l_env: ccad` already
  drives `include_role: name: "trial_runner_{{ dl2l_env }}"` in `run-experiment.yml` — no
  framework change needed there.
- **CCAD requires the CEFET VPN, which drops on idle** — same constraint already solved for
  training (`roles/train_ccad`'s submit/rescue split, `-e rescue=true`, a `DONE` sentinel file
  written via a bash `trap ... EXIT`, job id persisted to a gitignored local file). Experiment
  runs need the **identical pattern**, not `sbatch --wait`.
- **CCAD's `gpu` partition is for training only.** Simulations are pure CPU/JVM work — data
  collection must target a CPU partition (`ansible/inventories/ccad`'s CCAD guide lists
  `dev`/`short`/`normal`/`long`/`extended`, nodes c1–c11). New group_vars:
  `ccad_sim_partition`/`ccad_sim_qos` (distinct from the training-only `ccad_partition`/`ccad_qos`/`ccad_gres`).
- **Every experiment conf uses `holders = 1`** (confirmed across `simulations/*.conf`) and
  `docker-compose.yml.j2` only ever templates a single `dl2l-holder` service — no multi-holder
  scaling to replicate; the legacy `deploy.sh`'s per-holder-count loop was dead weight even
  before this change.
- **`--exclusive` per array task** (mirrors `roles/trial_runner_pi`'s existing `sbatch --array
  ... --exclusive` pattern) sidesteps port collisions: since Singularity instances share the
  host's network namespace (no per-container hostnames, no bridge network), running each trial
  on its own whole node means every trial can safely use the same fixed ports
  (`localhost:5432`, `localhost:2551`, etc.) without colliding with a concurrently-running trial
  on a different node.
- **No bare `psql` client on the cluster host, and no `docker exec` equivalent.**
  `scripts/dl2l_data/db.py`'s `psql_copy`/`pg_dump` assume `docker exec -i <container> psql ...`.
  Postgres runs *inside* the Singularity container here too — there's no reason to assume a
  `psql` binary is installed on the bare CCAD node itself (HPC login/compute nodes don't
  generally have arbitrary client tools pre-installed), so a plain TCP `psql -h localhost -p
  5432 ...` from the host would likely fail with "command not found". The fix: run `psql` via
  `singularity exec instance://dl2l-db psql ...` — reusing the `psql` binary that's already
  bundled inside the running postgres container image itself, the direct Singularity analog of
  `docker exec -i <container> psql ...` (different flag shape: `instance://<name>` prefix
  instead of a bare container name, no `-i` flag).
- **The JDBC URL has zero override mechanism today.** `src/main/resources/META-INF/persistence.xml`
  hardcodes `jdbc:postgresql://dl2l-db:5432/l2l` (confirmed: no Maven resource filtering, no
  profile). `JpaPersister.java:18` calls `Persistence.createEntityManagerFactory("L2LPU")` with
  no properties map — the standard JPA override mechanism
  (`createEntityManagerFactory(String, Map<String,Object>)`) is available and unused.
- **A Singularity SIF is read-only by default.** Caught mid-implementation (the user asked "what
  happens to the container volume when the job completes?"): without an explicit writable layer,
  postgres can't write its own data directory (initdb/WAL) at all — it likely wouldn't even start.
  Fix: `--writable-tmpfs` on every `singularity instance start` (ephemeral, in-memory, discarded
  when the instance stops) — intentional, not a shortcut: this mirrors how the local/Pi
  docker-compose postgres has no named volume either. `dl2l_data.extract` runs *before* the job
  script's EXIT trap tears instances down, copying everything needed out to the shared NFS first.
- **Known real risk, flagged rather than assumed away:** running the official `postgres` Docker
  image under unprivileged Singularity is a known rough edge — the image's entrypoint expects to
  run as a specific uid (postgres) inside the container, and Singularity by default runs as the
  invoking user with no uid remapping unless `--fakeroot` is available. The plan below tries the
  standard approach (`--fakeroot`) and documents a fallback path (a rootless-friendly postgres
  image, e.g. a Bitnami variant) as an untested-against-live-cluster risk, matching how the
  `module load` caveat was handled for training.

## Design

### 1. Java change: `DL2L_DB_URL` override (small, backward-compatible)

`src/main/java/br/cefetmg/lsi/l2l/creature/bd/JpaPersister.java` — change
`Persistence.createEntityManagerFactory("L2LPU")` to build a properties map, reading
`System.getenv("DL2L_DB_URL")`; if set, override `javax.persistence.jdbc.url`; if unset, behavior
is byte-identical to today (falls through to `persistence.xml`'s hardcoded value). No other
Java files need changes — this is the only `createEntityManagerFactory` call site.

### 2. New Singularity orchestration script (replaces docker-compose.yml.j2 for CCAD)

`ansible/roles/trial_runner_ccad/templates/run_trial.sh.j2` — one bash script per (condition,
trial) array task, run under `set -euo pipefail` with a `trap ... EXIT` writing a `DONE`
sentinel (mirrors `roles/train_ccad/templates/train_variants.sh.j2` exactly):

```bash
#!/bin/bash
#SBATCH --exclusive --nodes=1
#SBATCH --partition={{ ccad_sim_partition }} --qos={{ ccad_sim_qos }}
set -euo pipefail
TRIAL=${SLURM_ARRAY_TASK_ID}
trap 'echo "$?" > "{{ remote_trial_dir }}/trial_${TRIAL}/DONE"' EXIT

singularity instance start --fakeroot \
  --bind {{ remote_config_dir }}/init-db.sql:/docker-entrypoint-initdb.d/init-db.sql \
  --env POSTGRES_PASSWORD=postgres --env POSTGRES_DB=l2l \
  docker://postgres:16 dl2l-db
# wait for postgres: loop `pg_isready -h localhost -p 5432` (or nc -z) until it accepts connections

singularity instance start --env ROLE=manager,idProvider --env SIMULATION=... \
  --env HOST=localhost --env PORT=2551 --env DL2L_DB_URL=jdbc:postgresql://localhost:5432/l2l \
  docker://ghcr.io/felipedreis/dl2l:latest dl2l-manager
# wait for manager: nc -z localhost 2551 (mirrors the existing healthcheck)

singularity instance start --env ROLE=collisionDetector --env HOST=localhost --env PORT=2552 ... dl2l-detector
singularity instance start --env ROLE=holder --env HOST=localhost --env PORT=2553 --env DL2L_DB_URL=... dl2l-holder
# wait for the holder process to exit (poll `singularity instance list` for dl2l-holder's absence,
# or tail its log for the simulation's own "Finish" log line — no `docker wait` equivalent exists)

PYTHONPATH={{ remote_scripts_dir }} python3 -m dl2l_data.extract \
  --experiment ... --condition ... --trial "$TRIAL" --out {{ remote_trial_dir }} \
  --container dl2l-db --runtime singularity   # NEW --runtime flag, see below

singularity instance stop --all
```

Each role gets its own port (manager=2551, detector=2552, holder=2553) since Singularity
instances share the node's network namespace — no service-name DNS, so `HOST=localhost`
everywhere and the Akka seed-nodes reference (currently hardcoded to
`"akka.tcp://l2l@dl2l-manager:2551"` in `config/docker-config.conf`) needs a CCAD-specific Akka
config file with that line parameterized as `"akka.tcp://l2l@localhost:2551"` — add
`config/ccad-config.conf` (copy of `docker-config.conf` with that one line changed), synced to
the cluster alongside the job script.

### 3. Extraction: teach `scripts/dl2l_data/` to exec into a Singularity instance

`scripts/dl2l_data/db.py`'s `psql_copy`/`psql_query`/`pg_dump` currently build
`_docker_argv(docker_cmd) + ["exec", "-i", container, "psql", ...]` — correct for Docker (and
already reused for the Pi's `sudo docker` via the existing `docker_cmd` param), but Singularity's
exec-into-a-running-instance syntax is shaped differently (`singularity exec instance://<name>
psql ...` — no `-i` flag, and the container reference needs the `instance://` prefix). Rather
than stretch the `docker_cmd` string-prefix trick to cover a fundamentally different argv shape,
add an explicit `runtime` parameter (`"docker"` default, or `"singularity"`) to `psql_copy`/
`psql_query`/`pg_dump`, branching to build the right argv per runtime. `scripts/dl2l_data/extract.py`
gains a `--runtime docker|singularity` flag (default `docker`, so every existing call site —
local, Pi — is unaffected) threaded through to these functions. No client tool needs to exist on
the CCAD host at all — `psql` is only ever invoked *inside* the already-running `dl2l-db`
Singularity instance, via `singularity exec instance://dl2l-db`, exactly mirroring how the
Docker path never needs a host-side `psql` either.

### 4. Ansible: `image_ccad` + `trial_runner_ccad` (submit/rescue, mirrors `train_ccad`)

- **`ansible/roles/image_ccad/tasks/main.yml`**: CCAD only supports `image.source: registry` (no
  local docker daemon there to build from) — `singularity pull docker://ghcr.io/felipedreis/dl2l:latest`
  once per run (idempotent — skip if the `.sif` already exists and is recent), failing clearly
  if the spec says `image.source: build`.
- **`ansible/roles/trial_runner_ccad/tasks/main.yml`**: branches on `rescue`, exactly like
  `train_ccad`:
  - `submit.yml`: sync `config/init-db.sql` + `config/ccad-config.conf` + `scripts/dl2l_data/` +
    the rendered `run_trial.sh.j2` per condition to the login node; submit one
    `sbatch --array=1-{{ trials }} --exclusive` per condition (no `--wait`); persist a
    `{condition_key: job_id}` mapping to `ansible/.run/experiment_{{ name }}.ccad.jobs`; report
    all job ids and the exact rescue command to run later.
  - `rescue.yml`: for each condition, check whether all `trials` count of
    `.../trial_N/DONE` sentinels exist; sync back whatever trials are done regardless; report
    per-condition status (`"3/5 conditions finished"`); only set `experiment_finished: true`
    once **every** condition's every trial is done (so `run-experiment.yml`'s upload play never
    fires on a partial dataset) — otherwise `false`, safe to re-run later.
- **`ansible/inventories/ccad/group_vars/all.yml`**: add `ccad_sim_partition: short`,
  `ccad_sim_qos` (default/omit if CCAD has no separate qos for CPU partitions — confirm the
  literal qos name isn't required the same way `gpu_qos` was, since the guide didn't list one
  for the CPU partitions; if none exists, omit `--qos` from the sbatch directive entirely for
  the sim script, only `gpu_qos` was confirmed required for the `gpu` partition specifically).
- **`ansible/run-experiment.yml`**: add the same `-e rescue=true` extra-var support as
  `train-model.yml` — gate `collect_upload`'s upload/analyze/train-chain tasks on
  `experiment_finished | default(true) | bool` (mirrors `training_finished` exactly); `trial_runner_local`/
  `trial_runner_pi` set it to `true` unconditionally at the end (both complete synchronously
  within one invocation, matching `train_local`'s pattern).

### 5. Delete the dead cluster

`ansible/inventories/cefet/`, `ansible/roles/trial_runner_cefet/`, `ansible/roles/image_cefet/`
— all gone (git history preserves them). Update `experiments/README.md`, `training/README.md`,
`CLAUDE.md`, and `docs/plans/experiment-infra-ansible-refactor.md`'s references from "CEFET
(`cluster.decom.cefetmg.br`, blocked seam)" to "CCAD (`ccad` inventory, Singularity)".

## Verification

1. `mvn package` compiles clean after the `JpaPersister.java` change; a quick local run
   confirms `DL2L_DB_URL` unset still connects exactly as before (byte-identical fallback).
2. `python3 -m py_compile` on the `dl2l_data.db`/`extract.py` `runtime` additions; a live local
   test against an already-running `docker compose` postgres, comparing
   `dl2l_data.extract --container db` (default `--runtime docker`, today's behavior, must be
   byte-identical) against a manually-substituted argv to confirm the `singularity` branch
   builds the expected `singularity exec instance://... psql ...` command shape (can't fully
   exercise it without a Singularity host, but the argv construction itself is directly
   testable and should be, e.g. via a unit-style check on the function's returned argv list).
3. `ansible-playbook --syntax-check` + `--list-tasks` for `-i inventories/ccad run-experiment.yml`
   in both submit and `-e rescue=true` modes.
4. Render `run_trial.sh.j2` and `ccad-config.conf` with representative vars, `bash -n` the
   rendered script (same technique used for `train_variants.sh.j2`).
5. **Live smoke run on CCAD** (now that SSH access is confirmed working): submit the `smoke`
   experiment for real, watch it land on the CPU partition (not `gpu`), confirm the job
   completes and rescue-mode picks up the sentinel and syncs data back — this is the step most
   likely to surface the postgres-under-Singularity uid/fakeroot risk flagged above; treat
   whatever surfaces as real findings to fix, not a plan failure.

   **Attempted for real; found and fixed three genuine bugs before hitting the CEFET VPN's
   idle-disconnect too many times in a row to finish the full cycle:**
   - `image_ccad`'s (and `image_pi`'s) `delegate_to` tasks were silently running on the control
     machine instead of the remote host — the play's `connection: local` overrides `delegate_to`
     unless the task explicitly forces `vars: {ansible_connection: ssh}` back. Confirmed by a
     `remote_work_dir`-named directory appearing locally under `ansible/` instead of on the
     cluster. Fixed in both roles.
   - `rsync` doesn't create a destination file's parent directory on its own — syncing straight
     into a freshly-created `remote_work_dir` failed with "No such file or directory" until the
     `config`/`jobs`/`logs`/`scripts`/`data/<experiment>` directory-creation task was moved
     *before* the sync task in `submit.yml` (it was previously running after).
   - `--exclusive` (requesting a whole node per trial, chosen to avoid port collisions across
     concurrent trials) tripped `AssocGrpCpuLimit` — this account's CPU grant is smaller than a
     full node (`GrpTRES`/`MaxTRES` weren't visible via `sacctmgr show associations` to size it
     precisely). Replaced with an explicit modest `--cpus-per-task=4`. Also found the CPU
     partitions **do** require an explicit qos (`short` → `short_qos`) even though the guide
     didn't list one and the account's default `dev_qos` isn't valid there.

   Dropping `--exclusive` reopens the same-node port-collision risk it existed to prevent (two
   trials could now land on the same node and fight over ports 2551-2553/5432 and singularity
   instance names) — acceptable for now since only one condition's array job runs at a time in
   practice, but the real fix (deriving ports/instance names from `$SLURM_ARRAY_TASK_ID`, which
   also touches `ccad-config.conf`'s seed-nodes port) is an explicit follow-up, not done here.

   **Current actual blocker (found on a later live retry, after the VPN issues above were
   worked around by targeting the login node's IP directly instead of its hostname — a
   separate, unresolved DNS/split-tunnel gap on the client side): the compute nodes' user
   database doesn't have this CCAD account registered.** Job 113 failed instantly (exit 127,
   0s elapsed) with `singularity`'s own error: `Couldn't determine user account information:
   user: unknown userid 2533`. Isolated with a plain `sbatch --wrap='id; whoami'` (no
   Singularity involved) on the same node (`c8`): `id` resolves the uid number but not the
   username, and `whoami` fails outright with "cannot find name for user ID 2533" — while the
   *login* node resolves this account fully (`id` → `10822696622(...)`). This is a CCAD
   infrastructure gap (NSS/LDAP/sssd not synced to at least node c8), not anything in this
   repo's ansible/Singularity design — needs CCAD admins (`ccad@cefetmg.br`) to fix account
   propagation to the compute nodes before the postgres/`--fakeroot` and completion-detection
   risks above can even be exercised (the job died before Singularity got far enough to reach
   either of them).

   Confirmed working end-to-end up through job submission: image pull (GHCR, public, no auth
   needed), directory setup, config/script sync, per-condition array job rendering and
   submission (`sbatch`, no `--wait`), job id parsing and local persistence, and
   `experiment_finished` correctly gating the upload play to "not yet" — resubmitted twice
   successfully (jobs 111, 112) after each fix. Not yet observed: a job actually completing and
   `-e rescue=true` picking up its `DONE` sentinel — the VPN dropped four times in the ~15
   minutes this took, each requiring the user to manually reconnect, and repeated reconnection
   requests were no longer a good use of a live debugging session. The postgres-under-Singularity
   `--fakeroot`/`--writable-tmpfs` risk and the `singularity instance list` completion-detection
   assumption remain genuinely untested — next live attempt should watch `squeue`/the job's
   `slurm-*.out` log through to actual completion.
6. Confirm `ansible/inventories/cefet`/`trial_runner_cefet`/`image_cefet` are fully gone and
   nothing else references them (`grep -r cluster.decom.cefetmg.br`).

## Update (2026-07-14/15): account propagation fixed, three more real bugs found and fixed

**The compute-node account gap above is resolved** — CCAD support mapped this account's uid to
a username on compute nodes directly (not the login node's NSS/LDAP source), confirmed live:
plain `singularity exec` against a pre-built sandbox now resolves the uid cleanly (previously
`Couldn't determine user account information: user: unknown userid 2533`, tried and ruled out
along the way: `nss_wrapper`/`LD_PRELOAD`, an `unshare --map-root-user --mount` bind-mounted
fake `/etc/passwd` trick, `--fakeroot` (blocked separately — `newuidmap` isn't setuid-root on
this install), and a Bitnami rootless postgres image — all now moot, the account fix was the
actual answer). No workaround code from that investigation was kept.

With account resolution working, three more real, previously-unreachable bugs surfaced running
an actual trial end-to-end:

1. **`singularity instance start` runs `.singularity.d/startscript`, not `runscript`.** SIFs
   built from a `docker://` image only ever get a populated `runscript` (translates
   ENTRYPOINT/CMD; used by `run`/bare `exec`) — `startscript` is an empty no-op. `instance
   start` reported "started successfully" and `pg_isready` looped forever with nothing ever
   listening; both the instance's own `.out`/`.err` logs were empty. Fixed in `image_ccad`:
   build a writable sandbox from each pulled `.sif` (needed anyway — compute nodes have no
   internet, so `instance start`/`exec` against a bare `.sif` can't extract on first use either)
   and copy `runscript` over `startscript` there.
2. **`instance start` doesn't apply the image's Docker `WORKDIR`, and has no `--pwd` flag to set
   it per-invocation either** (confirmed: `unknown flag: --pwd` — that flag only exists on
   `exec`/`run`). dl2l's `ENTRYPOINT ./run-dl2l.sh ...` (see `docker/Dockerfile`'s `WORKDIR
   /dl2l/run`) failed with `./run-dl2l.sh: not found` under `instance start` specifically. Fixed
   at the source: `image_ccad` now inserts `cd /dl2l/run` as the first line of the dl2l
   sandbox's `startscript`, so every code path the generated script can take runs through it.
3. **The real blocker: DJL's PyTorch native library was declared for the wrong platform.**
   `pom.xml` hardcoded the `pytorch-native-cpu` classifier to `osx-aarch64` (Mac dev) instead of
   `linux-x86_64` (what `docker/Dockerfile` actually builds/runs) — every environment this ran
   in before had internet, so DJL's runtime fallback (`LibUtils.downloadPyTorch()`, pulling from
   `publish.djl.ai`) silently papered over the mismatch every time and nobody noticed. On CCAD's
   no-internet compute nodes this download fails with `UnknownHostException: publish.djl.ai`,
   which throws out of `Holder.preStart()` (via `MLServiceExtension`) — the holder's Akka system
   comes up and even joins the cluster, but the actor itself never properly starts, so no
   creature is ever spawned and extraction correctly reports "No creatures found". This one
   applies everywhere, not just CCAD — Pi/local runs have just been silently eating a redundant
   network download on every single run. Fixed by swapping the classifier to `linux-x86_64` (Mac
   dev now relies on the same runtime auto-download it was already silently depending on).

**Image publishing:** GHCR only auto-publishes via `.github/workflows/cd.yml`, which triggers
*only* on push to `main` (no `workflow_dispatch`). A manual `docker buildx build --push` to a
distinct tag (`ghcr.io/felipedreis/dl2l:ccad-djl-fix`, `dl2l_image` in
`ansible/inventories/ccad/group_vars/all.yml`) was used instead to avoid touching `main`/
`:latest` — the local `gh` CLI token initially lacked `write:packages` scope; the user granted
it via the interactive browser device-code flow (`gh auth refresh -s write:packages`) since that
step can't be done non-interactively. **Follow-up, deliberately deferred past this PR** (user
request): the pipeline currently only ever builds one platform/classifier combination
(`linux-x86_64`, now correct for CCAD/Pi/Docker) — Mac dev and any other target should get their
own correctly-matched jar/image variant so this exact bug class (see finding 3 above) can't
recur elsewhere.

Also fixed along the way (robustness, not correctness): the wait-loops in `run_trial.sh.j2` for
postgres/manager readiness and the holder actually coming up had no failure path — a service
that never starts was indistinguishable from one that started instantly, so the script silently
sailed on to a doomed extraction instead of failing with a clear error. Every wait loop now
exits non-zero with the instance list attached if what it's waiting for never shows up.

### Further findings from the first real (non-smoke) trial run

Getting an actual trial to fully complete (not just start) surfaced four more real bugs,
each confirmed live and fixed:

4. **`persistence.xml`'s hardcoded `dl2l-db` hostname was bypassed by `DL2L_DB_URL` in only
   2 of 6 `createEntityManagerFactory("L2LPU")` call sites.** `JpaPersister`/`Main.java` had the
   override; `Holder.java`, `CreatureActor.java`, `MemoryConsolidator.java`, and
   `MemoryTraceConsolidator.java` didn't — confirmed live via `UnknownHostException: dl2l-db`
   even though `DL2L_DB_URL` demonstrably reached the process environment correctly (verified by
   dumping it from inside a live instance). `BDActor.java` was worse: its field initializer
   *unconditionally* called the unoverridden `createEntityManagerFactory("L2LPU")` even though
   its only constructor immediately discards that value and uses an injected `EntityManager`
   instead — pure dead code that also crashed BDActor construction outright. Fixed by widening
   `JpaPersister.jdbcUrlOverride()` to `public static` and reusing it at every call site;
   deleted BDActor's dead field initializer entirely.
5. **`--writable-tmpfs`'s default overlay size is too small for postgres.** Even a short trial's
   DDL bootstrap + WAL churn hit `PANIC: could not write to file pg_wal/xlogtemp.NN: No space
   left on device` — the flag has no size option. A plain writable *directory* overlay
   (`--overlay <dir>`) needs root ("only root user can use sandbox as overlay in setuid mode").
   Fixed with a disk-backed **ext3 overlay image** instead (`singularity overlay create --size
   1024 <path>` then `--overlay <path>.img`) — confirmed live this survives a 200MB write with
   room to spare, unprivileged. Only postgres needs this; the app containers keep
   `--writable-tmpfs` (logs/tmp only).
6. **The extraction step's `python3` has no pandas/pyarrow.** `--format parquet` crashed with
   `ModuleNotFoundError: No module named 'pandas'` even after a trial fully succeeded. Tried
   `module load miniforge3 && conda activate <env>` first — **confirmed live this breaks the
   *unrelated* `singularity exec instance://dl2l-db psql` subprocess call `dl2l_data.db` shells
   out to** (almost certainly `LD_LIBRARY_PATH` contamination from the conda module, inherited
   by that Python subprocess): postgres was demonstrably still running, but psql suddenly
   couldn't find its own unix socket right after the conda activation was added, and the error
   disappeared again the moment it was removed. Fixed with `pip install --user pandas pyarrow`
   against the system python3 instead (`provision-ccad.yml`) — no environment activation at all,
   so nothing can leak into that subprocess call.
7. **Ansible `set_fact` + `delegate_to: localhost` without `delegate_facts: true` sets the fact
   on the *original* play's host, not on `localhost`.** `experiment_finished` (set this way in
   `submit.yml`/`rescue.yml`/`trial_runner_pi/main.yml`) was invisible to the third play
   (`Collect, upload, and (optionally) analyze`, `hosts: localhost`) — confirmed live: the
   upload task ran immediately after a plain submit (should have been gated off), crashing with
   `FileNotFoundError` on a data dir that didn't exist yet since no trial had run. Fixed by
   adding `delegate_facts: true` to all three set_fact tasks.

### Making the pipeline actually replicable (no manual intervention)

Live debugging repeatedly fell back to rendering `run_trial.sh`/`build_image.sh` by hand and
`sbatch`-ing them directly, bypassing the playbook entirely — fast for debugging, but means
those runs' job ids never land in `ansible/.run/experiment_<name>.ccad.jobs`, so
`-e rescue=true` has no way to find them. Per explicit user direction, every fix that made a
manual step necessary was folded back into the roles themselves instead of staying a one-off:

- `image_ccad`'s pull/sandbox-build tasks were rewritten around the exact manual pattern that
  proved reliable: `roles/image_ccad/templates/build_image.sh.j2` (pull + sandbox build +
  startscript fixes, all in one script) is rendered, launched **detached** on the login node
  (`setsid nohup ... & disown`, survives the SSH session that launched it dying), and polled for
  a completion marker via plain `until`/`retries` (each retry opens a fresh SSH connection, so a
  drop just delays the next check). This replaces two failed earlier attempts at Ansible's
  `async`+`poll`:
  - **With `loop`**: fires and polls each item sequentially — the second item's async job never
    even launched (stuck 15+ min, only one job file ever appeared under `~/.ansible_async`).
  - **Without `loop`** (two separate named tasks): still hung — the remote job provably finished
    (its own `~/.ansible_async/<jid>` result file showed `rc=0` within ~2 minutes) but Ansible's
    poll loop kept reporting stale `started=1 finished=0` indefinitely. Whatever's causing this
    is specific to `async_status` polling over this VPN, not something in this repo's control.
- `run_trial.sh.j2`'s wait-loop and postgres-overlay fixes (findings above) are template
  changes, so every future submit already gets them automatically — no separate fix-up step.

## Housekeeping

Continues on `feat/experiment-infra-ansible-refactor` (PR #69, still open) unless told otherwise.
