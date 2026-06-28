# GitHub Self-Hosted Runner Setup — Raspberry Pi 4 Cluster

This guide configures the GitHub Actions self-hosted runner on **node 0** of the
4-node Raspberry Pi 4 cluster (controller + compute). Run all commands on node 0
as a privileged user (e.g. via `sudo`), then switch to the `gh-runner` user for
the runner registration.

---

## 1. Prerequisites on node 0

### 1.1 Docker

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker gh-runner   # done after creating the user in §2
```

Verify:
```bash
docker run --rm hello-world
```

### 1.2 Slurm client tools

Node 0 is already the Slurm controller, so `sbatch`, `squeue`, `scancel`, and
`sacct` are already installed. Confirm:

```bash
sinfo            # should show the rpi partition
sbatch --version
```

### 1.3 Python 3 + analysis dependencies

```bash
sudo apt-get install -y python3 python3-pip python3-venv
pip3 install --user pandas scipy scikit-learn matplotlib pyyaml jsonschema jinja2
```

A `requirements.txt` for reproducibility is at `experiments/requirements.txt`
(generated from the above). Pin a venv inside the runner work dir if you want
isolation:

```bash
python3 -m venv /srv/gh-runner/venv
/srv/gh-runner/venv/bin/pip install -r experiments/requirements.txt
```

### 1.4 GitHub CLI (`gh`)

```bash
curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
    | sudo dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] \
    https://cli.github.com/packages stable main" \
    | sudo tee /etc/apt/sources.list.d/github-cli.list > /dev/null
sudo apt-get update && sudo apt-get install -y gh
```

The `gh` CLI is authenticated via `GH_TOKEN` injected by the workflow — no
interactive login needed on the Pi.

### 1.5 Shared run directory

```bash
sudo mkdir -p /srv/dl2l/runs
sudo chown gh-runner:gh-runner /srv/dl2l/runs
```

If nodes 1–3 need to read output from jobs submitted from node 0, mount this
over NFS. For `layout: single_node` experiments (Phase C v1), everything runs
on node 0 only and NFS is not required.

---

## 2. Create the `gh-runner` user

```bash
sudo useradd -m -s /bin/bash gh-runner
sudo usermod -aG docker gh-runner
# No sudo rights needed for normal experiment operation
```

---

## 3. Install the runner

All commands from here run **as `gh-runner`**:

```bash
sudo -u gh-runner -i
mkdir -p /srv/gh-runner && cd /srv/gh-runner
```

Download the latest linux-arm64 runner:

```bash
RUNNER_VERSION=$(curl -s https://api.github.com/repos/actions/runner/releases/latest \
    | grep '"tag_name"' | sed 's/.*"v\([^"]*\)".*/\1/')
curl -fsSL \
    "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/actions-runner-linux-arm64-${RUNNER_VERSION}.tar.gz" \
    -o actions-runner.tar.gz
tar xzf actions-runner.tar.gz
rm actions-runner.tar.gz
```

### 3.1 Register the runner

Generate a registration token in GitHub:
**Repository → Settings → Actions → Runners → New self-hosted runner**
(or use `gh api repos/felipedreis/dl2l/actions/runners/registration-token`).

```bash
./config.sh \
    --url https://github.com/felipedreis/dl2l \
    --token <REGISTRATION_TOKEN> \
    --name rpi-node0 \
    --labels "self-hosted,linux,ARM64,raspberry-pi,slurm" \
    --runnergroup Default \
    --work /srv/dl2l/runs
```

Accept all defaults when prompted.

### 3.2 Install as a systemd service

Back as a privileged user:

```bash
exit   # leave gh-runner session
cd /srv/gh-runner
sudo ./svc.sh install gh-runner
sudo ./svc.sh start
sudo ./svc.sh status   # should show "active (running)"
```

The service is named `actions.runner.felipedreis-dl2l.rpi-node0`. Enable it
to survive reboots:

```bash
sudo systemctl enable actions.runner.felipedreis-dl2l.rpi-node0
```

---

## 4. Verify the runner is online

In GitHub: **Repository → Settings → Actions → Runners** — the runner
`rpi-node0` should show a green **Idle** status within ~30 seconds of starting
the service.

Trigger a manual workflow run:

```bash
gh workflow run experiments.yml \
    --repo felipedreis/dl2l \
    --field tag=latest
```

---

## 5. Partition and resource defaults

The Slurm partition name used by the experiments manifest defaults to `rpi`.
Check your partition name with `sinfo` and update `experiments/manifest.yml`
accordingly:

```yaml
defaults:
  partition: rpi      # ← adjust to match your partition name
  time: "06:00:00"
  cpus_per_task: 4    # Pi 4 has 4 cores; leave 0 for the OS
  mem: 6G             # 8 GB total; reserve 2 GB for OS + Slurm overhead
```

---

## 6. Security hardening

- The runner is **repository-scoped** (not organisation-scoped). It can only
  run workflows from `felipedreis/dl2l`.
- **Never allow fork pull requests to trigger workflows on this runner.**
  In **Repository → Settings → Actions → General**, confirm that
  *"Require approval for first-time contributors who are new to GitHub"*
  is set to at least **"Require approval for all outside contributors"** and
  that self-hosted runners are restricted to protected branches or approved
  workflows.
- The `experiments.yml` workflow runs only on `release.published` and
  `workflow_dispatch` — not on arbitrary PRs.
- The `gh-runner` user has no `sudo` access. The Docker daemon socket is
  the only elevated resource it holds.

---

## 7. Updating the runner

```bash
sudo ./svc.sh stop
sudo -u gh-runner /srv/gh-runner/config.sh remove --token <REMOVE_TOKEN>
# Re-run §3 with the new binary
```

GitHub auto-updates the runner binary in the background if the service is
configured with `--disableupdate false` (the default).
