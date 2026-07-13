"""Markdown report skeleton builder.

CLAUDE.md's development cycle (step 5g) mandates every experiment report have
Purpose / Assumptions / Hypothesis / Results / Analysis sections. This module
doesn't try to auto-generate the narrative (that's the point of an
experiment-specific write-up, see docs/reports/rotten_fruit_v1_report.md for
the target tone/structure) — it gives a ReportBuilder that accumulates
markdown sections in the right order and writes them to
<report_dir>/<name>_report.md.
"""

from __future__ import annotations

from pathlib import Path


class ReportBuilder:
    SECTIONS = ("purpose", "assumptions", "hypotheses", "results", "analysis")

    def __init__(self, cfg, title: str | None = None):
        self.cfg = cfg
        self.title = title or f"Experiment Report: {cfg.name}"
        self._parts: dict[str, list[str]] = {s: [] for s in self.SECTIONS}

    def purpose(self, text: str) -> "ReportBuilder":
        self._parts["purpose"].append(text.strip())
        return self

    def assumptions(self, text: str) -> "ReportBuilder":
        self._parts["assumptions"].append(text.strip())
        return self

    def hypotheses(self, text: str) -> "ReportBuilder":
        self._parts["hypotheses"].append(text.strip())
        return self

    def result(self, heading: str, body: str, figure: str | None = None) -> "ReportBuilder":
        """Append one Results subsection. `figure` is a filename relative to
        cfg.fig_dir; the markdown image path is written relative to
        cfg.report_dir (matching the existing reports' `figures/<exp>/<fig>` convention).
        """
        chunk = [f"### {heading}", ""]
        if figure:
            fig_rel = Path("figures") / self.cfg.name / figure
            chunk += [f"![{heading}]({fig_rel.as_posix()})", ""]
        chunk.append(body.strip())
        self._parts["results"].append("\n".join(chunk))
        return self

    def analysis(self, text: str) -> "ReportBuilder":
        self._parts["analysis"].append(text.strip())
        return self

    def render(self) -> str:
        lines = [f"# {self.title}", ""]
        lines += ["## Purpose", "", *self._parts["purpose"], ""]
        lines += ["## Assumptions", "", *self._parts["assumptions"], ""]
        lines += ["## Hypotheses" if len(self._parts["hypotheses"]) != 1
                   else "## Hypothesis", "", *self._parts["hypotheses"], ""]
        lines += ["## Results", "", *self._parts["results"], ""]
        lines += ["## Analysis", "", *self._parts["analysis"], ""]
        return "\n".join(lines)

    def write(self, filename: str | None = None) -> Path:
        filename = filename or f"{self.cfg.name}_report.md"
        path = self.cfg.report_dir / filename
        path.write_text(self.render())
        print(f"  -> report written to {path}")
        return path
