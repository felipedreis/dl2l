# Issue #70: Consolidate Docker image distribution across CI, Pi, and CCAD

GitHub issue: https://github.com/felipedreis/dl2l/issues/70

## Context

Three disconnected image-distribution paths exist today:

1. **CI** (`.github/workflows/cd.yml`) — single-arch (`linux/amd64`) build/push to
   `ghcr.io/felipedreis/dl2l`, triggered on every push to `main`.
2. **Pi** (`ansible/roles/image_pi`) — cross-builds `linux/arm64` locally via `docker buildx`,
   pushes to the Pi cluster's own private registry (`192.168.1.200:5000`), unrelated to GHCR/CI.
3. **CCAD** (`ansible/roles/image_ccad`) — pulls from GHCR, but pinned to a manually-built,
   manually-pushed stopgap tag (`ghcr.io/felipedreis/dl2l:ccad-djl-fix`) instead of `:latest`,
   because `:latest` was built before PR #69 fixed a DJL PyTorch native-library classifier bug
   that crashes every Holder on CCAD (no internet there to fall back on).

Prior art (unmerged, referenced by the issue): branch `claude/github-runner-raspberry-pi-slurm-311o5m`,
commit `e657a77` (2026-06-28) — added QEMU + buildx multi-platform CI build, an arch-conditional
DJL classifier (`linux-x86_64` bundled, ARM64 deferred to runtime download, `mac-dev` Maven
profile for native Mac dev), CI-friendly `${revision}` Maven versioning, and a `release.published`
tag-publishing trigger. It predates PR #69 (2026-07-15, commit `7e375f5`), which independently
fixed the classifier bug via a different, since-superseding mechanism (see below) — so its
classifier-handling approach is now partially obsolete, but the CI multi-platform build part is
still directly applicable.

### Key finding that changes the shape of item 2

PR #69 added `DjlCacheWarmup` (`src/main/java/.../creature/ml/DjlCacheWarmup.java`) plus a
`docker/Dockerfile` build step that runs it: `RUN java -cp dl2l.jar ...DjlCacheWarmup`, baking
DJL's PyTorch native library + JNI bridge into `$DJL_CACHE_DIR` **at Docker build time**, using
the *build machine's* internet access (always available — GH Actions runners, Mac dev, Pi
controller) rather than the running container's (frequently unavailable — CCAD compute nodes).

This runs *inside* the Docker build, so under `docker buildx --platform linux/arm64` (QEMU
emulation) the JVM reports the emulated architecture, and DJL's engine-init auto-download
correctly fetches `linux-aarch64` regardless of what `pom.xml`'s bundled
`pytorch-native-cpu` classifier says — the mismatch just triggers DJL's existing "wrong bundled
classifier → auto-download the right one" fallback (already documented in `pom.xml`'s comment),
except now it happens at build time (internet always available there) instead of at container
startup (not always available, which is what broke CCAD originally). The downloaded
`linux-aarch64` lib is then part of the image layer, so the running arm64 container never needs
network access for it either.

**Conclusion:** correctness for item 2 is already achieved by the `DjlCacheWarmup` mechanism,
independent of the single hardcoded `linux-x86_64` classifier in `pom.xml`. No Maven
profile/matrix-build complexity is needed to fix a correctness bug that no longer exists. The
one remaining decision (per the issue's ask for "a real decision... not another silent
workaround") is documentation, not code: make this mechanism and its implication explicit at the
`pom.xml` comment site, so nobody re-"fixes" the classifier per-arch later under the mistaken
belief it's still required for correctness.

## Scope (per user: all four items from the issue)

### 1. Multi-platform CI build

`.github/workflows/cd.yml`: add `docker/setup-qemu-action@v3` +
`docker/setup-buildx-action@v3`, and `platforms: linux/amd64,linux/arm64` on the
`docker/build-push-action@v5` step — adapted from commit `e657a77`, minus the parts superseded
or out of scope (no `${revision}`/`flatten-maven-plugin` versioning, no `release.published`
trigger — the issue's proposed scope doesn't ask for release tagging, only a correct
`:latest`/`:sha-*` multi-platform manifest).

`mvn package` still runs once, producing one arch-independent fat jar (it's Java) that both
buildx legs use as Docker build context input — no matrix build needed.

### 2. DJL classifier — document, don't re-engineer

Update the `pom.xml` comment on the `pytorch-native-cpu` dependency to explain the
`DjlCacheWarmup`-at-build-time mechanism above, and that the multi-platform build (item 1) makes
this correct for both `linux/amd64` and `linux/arm64` outputs without per-arch Maven profiles.
No dependency/profile changes.

### 3. Retire the `ccad-djl-fix` stopgap

Once item 1 lands on `main` and CI produces a working multi-arch `:latest`:
`ansible/inventories/ccad/group_vars/all.yml` — change `dl2l_image` from
`ghcr.io/felipedreis/dl2l:ccad-djl-fix` back to `ghcr.io/felipedreis/dl2l:latest`, replace the
STOPGAP comment explaining why it's safe now.

**Caveat I can't resolve from this sandbox:** verifying this live requires the CEFET VPN + a real
CCAD run, neither available here. I'll make the config change, but actual verification (does the
new `:latest` really come up clean on a CCAD Holder) needs to happen in a follow-up live run —
flagged clearly, not silently assumed.

### 4. Evaluate retiring Pi's private-registry build path

Investigated: `ghcr.io/felipedreis/dl2l` **is a public GHCR package** (confirmed via the GitHub
package page), so pulling it needs no registry auth — one of the two "not yet investigated"
concerns from the issue is resolved. The other — whether the Pi cluster's nodes actually have
outbound internet access to `ghcr.io` — I cannot verify from this sandbox (no access to the Pi
hardware).

Given that, **I will not flip Pi's default `dl2l_image` away from the private registry** — doing
so unverified risks silently breaking every Pi experiment run with no way for me to catch it.
Instead:
- Document the option in `ansible/inventories/pi/group_vars/all.yml`: once multi-arch `:latest`
  exists (item 1), an operator can test pulling from GHCR by setting
  `image: {source: registry}` in an experiment spec and pointing `dl2l_image` at
  `ghcr.io/felipedreis/dl2l:latest` (via `-e dl2l_image=...` or a group_vars override), without
  touching the default.
- Leave `image_pi`'s cross-build role and the default private-registry `dl2l_image` untouched —
  it keeps working exactly as today regardless of what happens with GHCR.
- Record this as an explicit open item (needs a live test from a Pi-connected machine) rather
  than silently declaring it done.

### 5. Feature-branch images (added at user's request, beyond the original issue text)

Today `cd.yml` only triggers on push to `main`, so testing an in-progress branch on Pi/CCAD means
falling back to the exact per-environment local-build fragility this issue is about. Extend the
same multi-platform CI build (item 1) to every branch push:

- Trigger: `on: push: branches: ['**']` (any branch, not tags — no release-tag flow exists or is
  being added here).
- `:latest` stays reserved for `main` only — gate it with `enable=${{ github.ref ==
  'refs/heads/main' }}` on the `type=raw,value=latest` tag entry.
- Add a `type=ref,event=branch` tag (docker/metadata-action's branch-name slug) alongside the
  existing `type=sha,prefix=sha-,format=short` — gives a convenient moving per-branch tag
  (`ghcr.io/felipedreis/dl2l:<branch-slug>`) for iterative testing, on top of the immutable
  per-commit `:sha-*` tag that already exists.
- Multi-platform (`linux/amd64,linux/arm64`) applies to every push, not just main, since the
  point is letting Pi (arm64) pull-test a branch directly. Tradeoff: QEMU-emulated arm64 builds
  are slower than native, so every push now pays that cost, not just merges to main — acceptable
  for this repo's commit volume, called out explicitly in case it isn't later.

### 6. Decouple simulation config from the image

Found while discussing this: `docker/Dockerfile` does `COPY simulations/ ./simulations/`, baking
the whole `simulations/` directory into the image. Since `SIMULATION` is just a runtime
`--simulation <path>` flag (relative to `/dl2l/run`, see `scripts/run-dl2l.sh`), a new or edited
`.conf` file forces a full image rebuild today even though no Java code changed — image build and
simulation authoring are coupled for no reason.

Fix: stop baking `simulations/` into the image; bind-mount it at runtime instead, on every
environment:

- `docker/Dockerfile` — drop the `COPY simulations/ ./simulations/` line.
- `ansible/roles/common/templates/docker-compose.yml.j2` (shared by local + Pi) — add a
  read-only bind mount of `{{ dl2l_simulations_dir }}` → `/dl2l/run/simulations` on the manager,
  detector, and holder services (not `db`).
- `ansible/inventories/local/group_vars/all.yml` — `dl2l_simulations_dir: "{{ repo_root
  }}/simulations"` (repo checkout is the same machine running docker, same pattern as
  `dl2l_config_dir`).
- `ansible/inventories/pi/group_vars/all.yml` — `dl2l_simulations_dir: "{{ shared_fs
  }}/simulations"`; `ansible/roles/trial_runner_pi/tasks/main.yml` syncs
  `{{ repo_root }}/simulations/` → `{{ shared_fs }}/simulations/`, mirroring the existing
  config/scripts sync.
- CCAD has no docker-compose (Singularity instances instead) — `ansible/roles/trial_runner_ccad/tasks/submit.yml`
  syncs `simulations/` to `{{ remote_work_dir }}/simulations/` alongside the existing
  config/scripts sync, and `run_trial.sh.j2` adds a `SIMULATIONS_BIND` bound into the
  manager/detector/holder `singularity instance start` calls (not postgres), the same pattern
  `CONFIG_BIND`/`INITDB_BIND` already use.

One image now serves any simulation config; rebuilds are only needed for real code changes.

## Item 4, finalized: also flip Pi's default to GHCR

Per user decision, going further than the original "document as opt-in" plan: Pi's *default*
(when an experiment spec doesn't declare `image.source`) now resolves to pulling the shared
multi-arch GHCR manifest, not cross-building to the private registry. Design, chosen to keep the
existing cross-build path fully intact as an explicit opt-out (not deleted — GHCR reachability
from real Pi hardware is still unverified from this sandbox):

- `ansible/roles/experiment_spec/tasks/main.yml` — the `image.source` default becomes env-aware:
  `'registry'` when `dl2l_env == 'pi'`, `'build'` otherwise (local unchanged; CCAD's own
  `image_ccad` role already independently requires — and enforces via a `fail` task — an
  explicit `source: registry` regardless of any default, so it's untouched). All five existing
  `experiments/*.yml` specs already declare `image.source` explicitly, so this changes no
  existing spec's resolved behavior — it only changes what an *unset* `image.source` means on
  Pi going forward.
- `ansible/inventories/pi/group_vars/all.yml` — `dl2l_image` default becomes
  `ghcr.io/felipedreis/dl2l:latest`. The private-registry host (`registry:
  192.168.1.200:5000`) variable is untouched and stays the push target for the `build` fallback
  path specifically (see next point) — it must never collide with GHCR's CI-owned `:latest`.
- `ansible/roles/image_pi/tasks/main.yml` — branches on `experiment_cfg.image.source`:
  - `build` (explicit opt-in): `set_fact dl2l_image: "{{ registry }}/dl2l:latest"` *before*
    cross-building, so the rest of the pipeline (compose render, worker pre-pull) targets the
    just-built private-registry image — byte-identical to today's behavior, fully isolated from
    GHCR.
  - `registry` (the new default): skip the cross-build/push steps entirely; still run the
    existing "pre-pull on every worker node" task, now pulling whatever `dl2l_image` resolved to
    (GHCR `:latest` by default, or an operator override).
- `ansible/run-experiment.yml` — the image-build play's `when:` clause is broadened so
  `image_{{ dl2l_env }}` always runs for `pi` too (mirrors the existing `dl2l_env == 'ccad'`
  special-case), since the registry branch above still needs to execute the worker pre-pull step
  even though it skips the build.

**Caveat, same as before:** confirmed `ghcr.io/felipedreis/dl2l` is a public package (no auth
needed to pull), but Pi hardware's actual outbound internet access to `ghcr.io` is still
unverified from this sandbox. This change makes that the *default* path, so it needs a live test
on real Pi hardware before being trusted — flagged, not silently assumed.

## Files touched

- `.github/workflows/cd.yml` — QEMU/buildx setup, `platforms:` line, all-branch trigger, gated
  `:latest`, branch-slug tag
- `pom.xml` — comment only, on the `pytorch-native-cpu` dependency
- `docker/Dockerfile` — drop `COPY simulations/`
- `ansible/roles/common/templates/docker-compose.yml.j2` — bind-mount `simulations/`
- `ansible/inventories/local/group_vars/all.yml` — `dl2l_simulations_dir`
- `ansible/inventories/pi/group_vars/all.yml` — `dl2l_simulations_dir`, `dl2l_image` → GHCR
  default
- `ansible/roles/trial_runner_pi/tasks/main.yml` — sync `simulations/` onto `shared_fs`
- `ansible/roles/image_pi/tasks/main.yml` — build-vs-registry branch
- `ansible/roles/experiment_spec/tasks/main.yml` — env-aware `image.source` default
- `ansible/run-experiment.yml` — broadened `when:` for the image-build play
- `ansible/roles/trial_runner_ccad/tasks/submit.yml` — sync `simulations/`
- `ansible/roles/trial_runner_ccad/templates/run_trial.sh.j2` — `SIMULATIONS_BIND`
- `ansible/inventories/ccad/group_vars/all.yml` — `dl2l_image` back to `:latest`, comment update
- `experiments/README.md` — updated `image.source` schema doc
- This plan doc

## Verification actually done in this sandbox

- `mvn validate` — passes; confirms `pom.xml` is well-formed (XML comments can't contain `--`,
  caught and fixed one instance of that here) and its dependency graph is otherwise unchanged.
  `mvn package` itself can't run end-to-end here — this sandbox only has JDK 21, the project
  requires JDK 23 — a pre-existing sandbox limitation, not something introduced by this change.
- Every touched non-Jinja YAML file (`cd.yml` and all touched `group_vars`/`tasks/*.yml`)
  parses cleanly via `yaml.safe_load`.
- `docker-compose.yml.j2` rendered directly (both `holder_combines_detector` branches) and the
  output re-parsed as YAML: confirms the `simulations/` bind mount lands on `dl2l-manager`,
  `dl2l-detector`, and `dl2l-holder` and *not* `dl2l-db`, in both branches.
- `run_trial.sh.j2` rendered directly: confirms `SIMULATIONS_BIND` is defined and `--bind
  "$SIMULATIONS_BIND"` appears exactly 3 times (manager/detector/holder, not postgres).
- No `ansible-playbook`/`ansible-lint` available in this sandbox, so the roles' task graphs
  (e.g. `experiment_spec`'s new `ternary()` default, `run-experiment.yml`'s broadened `when:`)
  are reviewed by hand, not executed.
- No Docker daemon here → cannot actually run `docker buildx build --platform
  linux/amd64,linux/arm64 ...` locally. The real test is the next push to `main` (or any branch)
  triggering CI, which the user can watch.
- Cannot reach live CCAD or Pi hardware from here — items 3, 4, and the pi default-source flip's
  real-world correctness need a live follow-up run, called out above rather than assumed.
