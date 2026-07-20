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

## Files touched

- `.github/workflows/cd.yml` — QEMU/buildx setup, `platforms:` line
- `pom.xml` — comment only, on the `pytorch-native-cpu` dependency
- `ansible/inventories/ccad/group_vars/all.yml` — `dl2l_image` back to `:latest`, comment update
- `ansible/inventories/pi/group_vars/all.yml` — comment documenting the untested GHCR option
- This plan doc

## Verification available in this sandbox

- `mvn package` (jar still builds; no dependency changes)
- YAML syntax sanity-check on `cd.yml`
- No Docker daemon available here → cannot actually run `docker buildx build --platform
  linux/amd64,linux/arm64 ...` locally. The real test is the next push to `main` triggering CI,
  which the user can watch.
- Cannot reach live CCAD or Pi hardware from here — items 3 and 4's real-world correctness needs
  a live follow-up run, called out above rather than assumed.
