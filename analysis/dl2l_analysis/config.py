"""Experiment analysis configuration.

Reads experiments/<name>.yml (introduced in a later phase of the
experiment-infra refactor) into a small config object that loading.py,
figures.py and report.py key off instead of each analysis script hardcoding
its own CONDITIONS/PALETTE/DATA_DIR globals.

Until experiments/<name>.yml exists for a given experiment, callers can build
an ExperimentAnalysis directly (see analysis/experiments/rotten_fruit_v1.py).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - PyYAML is an expected dependency
    yaml = None

REPO_ROOT = Path(__file__).resolve().parent.parent.parent


@dataclass
class Condition:
    key: str
    label: str
    color: str = "#999999"


@dataclass
class ExperimentAnalysis:
    name: str
    conditions: list[Condition]
    trials: int
    data_dir: Path
    fig_dir: Path | None = None
    report_dir: Path = field(default_factory=lambda: REPO_ROOT / "docs" / "reports")
    cond_dir: dict | None = None  # optional per-condition data_dir override

    def __post_init__(self):
        self.data_dir = Path(self.data_dir)
        if self.fig_dir is None:
            self.fig_dir = REPO_ROOT / "docs" / "reports" / "figures" / self.name
        self.fig_dir = Path(self.fig_dir)
        self.fig_dir.mkdir(parents=True, exist_ok=True)
        self.report_dir = Path(self.report_dir)

    @property
    def cond_keys(self) -> list[str]:
        return [c.key for c in self.conditions]

    @property
    def cond_labels(self) -> list[str]:
        return [c.label for c in self.conditions]

    @property
    def palette(self) -> dict:
        return {c.key: c.color for c in self.conditions}

    @property
    def label_by_key(self) -> dict:
        return {c.key: c.label for c in self.conditions}

    @property
    def trial_range(self) -> list[int]:
        return list(range(1, self.trials + 1))

    def data_dir_for(self, cond_key: str) -> Path:
        """Per-condition data directory, honoring cond_dir overrides
        (needed by experiments like 20260709_memory_vs_wm_v1 whose conditions
        live under different ml/data_*/ roots after a partial rerun)."""
        if self.cond_dir and cond_key in self.cond_dir:
            return Path(self.cond_dir[cond_key])
        return self.data_dir


def from_spec(name: str, specs_dir: str | Path = "experiments") -> ExperimentAnalysis:
    """Load an ExperimentAnalysis from experiments/<name>.yml.

    Spec schema (see experiments/README.md once Phase 3 lands):
        name: <str>
        trials: <int>
        conditions: [{key, label, color}, ...]
        data_dir: <path relative to repo root>
        cond_dir: {<key>: <path>, ...}   # optional per-condition override
    """
    if yaml is None:
        raise RuntimeError("PyYAML is required to load experiment specs (pip install pyyaml)")

    specs_dir = Path(specs_dir)
    if not specs_dir.is_absolute():
        specs_dir = REPO_ROOT / specs_dir
    spec_path = specs_dir / f"{name}.yml"
    if not spec_path.exists():
        raise FileNotFoundError(
            f"No experiment spec at {spec_path} — construct ExperimentAnalysis "
            f"directly until Phase 3 (experiments/ specs) lands for this experiment."
        )

    raw = yaml.safe_load(spec_path.read_text())
    conditions = [
        Condition(key=c["key"], label=c.get("label", c["key"]), color=c.get("color", "#999999"))
        for c in raw["conditions"]
    ]
    data_dir = REPO_ROOT / raw["data_dir"]
    fig_dir = REPO_ROOT / "docs" / "reports" / "figures" / raw.get("name", name)
    cond_dir = None
    if "cond_dir" in raw:
        cond_dir = {k: REPO_ROOT / v for k, v in raw["cond_dir"].items()}

    return ExperimentAnalysis(
        name=raw.get("name", name),
        conditions=conditions,
        trials=raw["trials"],
        data_dir=data_dir,
        fig_dir=fig_dir,
        cond_dir=cond_dir,
    )
