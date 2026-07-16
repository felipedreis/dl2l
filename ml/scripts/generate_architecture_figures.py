"""Generate architecture diagrams and comparison charts for ml/README.md.

Visual language deliberately mirrors the classic "Attention Is All You Need"
Transformer diagram: portrait, bottom-up data flow, flat pastel fills with
black borders and black text, small "+" merge nodes for concatenation, plain
text (no box) for tensors entering/leaving the whole diagram.

Usage:
    cd ml
    python3 -m scripts.generate_architecture_figures
    # or: python3 scripts/generate_architecture_figures.py

Writes PNGs to ../docs/figures/jepa_architectures/.
"""

from __future__ import annotations

from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Circle, FancyArrowPatch, FancyBboxPatch

OUT = Path(__file__).resolve().parent.parent.parent / "docs" / "figures" / "jepa_architectures"
OUT.mkdir(parents=True, exist_ok=True)

# ── Palette: flat pastels + black ink, in the spirit of the Transformer figure ──
C = dict(
    encoder="#9DC3E6",           # pastel blue
    internal_encoder="#B4A7D6",  # pastel purple
    predictor="#F4A46A",         # pastel orange (≈ Multi-Head Attention block)
    critic="#A9D18E",            # pastel green
    adapter="#FFE699",           # pastel yellow (≈ Add & Norm block)
    unified_bg="#FBE5D6",        # very light peach container
    ink="#1a1a1a",
    muted="#6b6b6b",
    grid="#e3e3e3",
    bg="#ffffff",
)

plt.rcParams["font.family"] = "sans-serif"
plt.rcParams["font.sans-serif"] = ["Helvetica", "Arial", "DejaVu Sans"]


# ── Generic drawing helpers ─────────────────────────────────────────────────────

def vbox(ax, cx, y, w, h, title, subtitle, color, fontsize=11, subfontsize=8.7):
    x = cx - w / 2
    ax.add_patch(FancyBboxPatch(
        (x, y), w, h, boxstyle="round,pad=0.02,rounding_size=0.09",
        linewidth=1.4, edgecolor=C["ink"], facecolor=color, zorder=3))
    ax.text(cx, y + h * (0.68 if subtitle else 0.5), title, ha="center", va="center",
            color=C["ink"], fontsize=fontsize, fontweight="bold", zorder=4)
    if subtitle:
        ax.text(cx, y + h * 0.26, subtitle, ha="center", va="center",
                color=C["ink"], fontsize=subfontsize, zorder=4, linespacing=1.4)
    return (cx, y, w, h)


def varrow(ax, x, y1, y2, label=None, side="right", dx=0.14, fontsize=8.7):
    ax.add_patch(FancyArrowPatch(
        (x, y1), (x, y2), arrowstyle="-|>", mutation_scale=13, linewidth=1.3,
        color=C["ink"], zorder=2, shrinkA=0, shrinkB=0))
    if label:
        lx = x + dx if side == "right" else x - dx
        ha = "left" if side == "right" else "right"
        ax.text(lx, (y1 + y2) / 2, label, ha=ha, va="center", fontsize=fontsize,
                color=C["muted"], style="italic", zorder=4)


def diag_arrow(ax, p1, p2, label=None, label_pos=0.5, fontsize=8.2):
    ax.add_patch(FancyArrowPatch(
        p1, p2, arrowstyle="-|>", mutation_scale=12, linewidth=1.2,
        color=C["ink"], zorder=2, shrinkA=1, shrinkB=1))
    if label:
        mx = p1[0] + (p2[0] - p1[0]) * label_pos
        my = p1[1] + (p2[1] - p1[1]) * label_pos
        ax.text(mx, my + 0.12, label, ha="center", va="bottom", fontsize=fontsize,
                color=C["muted"], style="italic", zorder=4)


def bus_line(ax, x, y_bottom, y_top):
    """Plain vertical trunk line (no arrowhead) that one or more tap_arrow calls
    branch off of — avoids overlapping/duplicate vertical segments when the same
    upstream tensor (e.g. z_internal) feeds two different downstream heights."""
    ax.plot([x, x], [y_bottom, y_top], color=C["ink"], linewidth=1.3, zorder=2,
            solid_capstyle="round")


def tap_arrow(ax, x_bus, y, x_to, label=None, fontsize=8.3, label_dy=0.14):
    ax.add_patch(FancyArrowPatch(
        (x_bus, y), (x_to, y), arrowstyle="-|>", mutation_scale=11, linewidth=1.25,
        color=C["ink"], zorder=3, shrinkA=0, shrinkB=8))
    if label:
        mx = (x_bus + x_to) / 2
        ax.text(mx, y + label_dy, label, ha="center", va="bottom", fontsize=fontsize,
                color=C["muted"], style="italic", zorder=4)


def side_arrow(ax, x_label, y, x_box_edge, label, fontsize=9, align="right"):
    """A short plain arrow carrying a_t (or similar) straight into a box's side
    edge — deliberately NOT a merge node, so it never crowds another arrowhead."""
    ha = "right" if align == "right" else "left"
    ax.text(x_label, y, label, ha=ha, va="center", fontsize=fontsize,
            fontweight="bold", color=C["ink"], zorder=4)
    x_start = x_label + (0.12 if align == "right" else -0.12)
    ax.add_patch(FancyArrowPatch(
        (x_start, y), (x_box_edge, y), arrowstyle="-|>", mutation_scale=11,
        linewidth=1.25, color=C["ink"], zorder=3, shrinkA=0, shrinkB=1))


def merge_node(ax, cx, y, r=0.11):
    ax.add_patch(Circle((cx, y), r, facecolor="white", edgecolor=C["ink"],
                        linewidth=1.3, zorder=5))
    ax.text(cx, y, "+", ha="center", va="center", fontsize=10, fontweight="bold",
            color=C["ink"], zorder=6)


def io_label(ax, cx, y, text, va="bottom", fontsize=12):
    ax.text(cx, y, text, ha="center", va=va, fontsize=fontsize, fontweight="bold",
            color=C["ink"], zorder=4)


def new_canvas(w, h, title, subtitle=None):
    fig, ax = plt.subplots(figsize=(w, h))
    ax.set_xlim(0, w)
    ax.set_ylim(0, h)
    ax.axis("off")
    fig.patch.set_facecolor(C["bg"])
    ax.text(w / 2, h - 0.22, title, ha="center", va="top",
            fontsize=13.5, fontweight="bold", color=C["ink"])
    if subtitle:
        ax.text(w / 2, h - 0.62, subtitle, ha="center", va="top",
                fontsize=10, color=C["muted"])
    return fig, ax


def save(fig, name):
    fig.savefig(OUT / name, dpi=200, bbox_inches="tight", facecolor=C["bg"])
    plt.close(fig)


# ── Figure 0: shared building blocks ───────────────────────────────────────────

def fig_building_blocks():
    fig, ax = new_canvas(10.6, 6.3, "Shared building blocks", "ml/jepa/model.py")

    # _mlp(in, hidden=128, out, norm) — vertical stack, bottom to top
    cx = 2.3
    io_label(ax, cx, 0.2, "in_dim", va="bottom", fontsize=10.5)
    varrow(ax, cx, 0.42, 0.8)
    vbox(ax, cx, 0.8, 2.0, 0.62, "Linear", "in_dim → 128", C["encoder"], fontsize=9.7, subfontsize=8)
    varrow(ax, cx, 1.42, 1.72)
    vbox(ax, cx, 1.72, 2.0, 0.62, "LayerNorm + ReLU", "128", C["adapter"], fontsize=9, subfontsize=8)
    varrow(ax, cx, 2.34, 2.64)
    vbox(ax, cx, 2.64, 2.0, 0.62, "Linear", "128 → 128", C["encoder"], fontsize=9.7, subfontsize=8)
    varrow(ax, cx, 3.26, 3.56)
    vbox(ax, cx, 3.56, 2.0, 0.62, "LayerNorm + ReLU", "128", C["adapter"], fontsize=9, subfontsize=8)
    varrow(ax, cx, 4.18, 4.48)
    vbox(ax, cx, 4.48, 2.0, 0.62, "Linear", "128 → out_dim", C["encoder"], fontsize=9.7, subfontsize=8)

    ax.text(5.0, 3.15,
            "_mlp(in_dim, hidden=128,\nout_dim, norm=True)\n\n"
            "used by:  Encoder, Predictor,\nCritic, UnifiedPredictor's\ntwo heads\n\n"
            "Critic and the emotion head\npass norm=False — the two\nyellow blocks are skipped,\nleaving 3 Linear + 2 ReLU.",
            ha="left", va="center", fontsize=9.2, color=C["ink"], linespacing=1.5)

    # IndividualAdapter — small side stack
    cx2 = 8.6
    io_label(ax, cx2, 0.2, "predictor(z, a)  [64]", va="bottom", fontsize=9.7)
    varrow(ax, cx2, 0.5, 0.85)
    vbox(ax, cx2, 0.85, 1.9, 0.55, "Linear + ReLU", "64 → 32", C["adapter"], fontsize=8.8, subfontsize=7.6)
    varrow(ax, cx2, 1.4, 1.72)
    vbox(ax, cx2, 1.72, 1.9, 0.55, "Linear (zero-init)", "32 → 64", C["adapter"], fontsize=8.5, subfontsize=7.6)
    varrow(ax, cx2, 2.27, 2.58)
    ax.text(cx2, 2.66, "adapter output", ha="center", va="bottom", fontsize=8.8, fontweight="bold", color=C["ink"])
    ax.text(cx2, 3.15,
            "IndividualAdapter\nz_next = predictor(z,a)\n+ adapter(predictor(z,a))\n\n"
            "zero-init last layer ⇒\nidentity at t=0. Only module\ntrained online, during sleep.",
            ha="center", va="bottom", fontsize=8.4, color=C["ink"], linespacing=1.5)

    save(fig, "fig0_building_blocks.png")


# ── Per-variant architecture diagrams (portrait, bottom-up) ────────────────────

def fig_single():
    fig, ax = new_canvas(7.6, 5.7, "single — SpeciesModel",
                          "Critic runs in PARALLEL with Predictor, reading z_t directly (not z_next)")
    cx = 3.8
    io_label(ax, cx, 0.18, "s_t  [9]", va="bottom")
    varrow(ax, cx, 0.44, 0.85)
    vbox(ax, cx, 0.85, 2.2, 0.78, "Encoder", "9 → 128 → 128 → 64", C["encoder"])
    varrow(ax, cx, 1.63, 2.2, label="z_t  [64]")

    xl, xr = 2.0, 5.6
    y_box = 2.65
    diag_arrow(ax, (cx, 2.2), (xl, y_box - 0.05))
    diag_arrow(ax, (cx, 2.2), (xr, y_box - 0.05))

    vbox(ax, xl, y_box, 2.15, 0.78, "Predictor", "73 → 128 → 128 → 64", C["predictor"], fontsize=10, subfontsize=8)
    vbox(ax, xr, y_box, 2.15, 0.78, "Critic", "73 → 128 → 128 → 9, tanh", C["critic"], fontsize=10, subfontsize=8)
    side_arrow(ax, xl - 1.55, y_box + 0.39, xl - 1.075, "a_t [9]", align="right")
    side_arrow(ax, xr + 1.55, y_box + 0.39, xr + 1.075, "a_t [9]", align="left")

    top = y_box + 0.78
    varrow(ax, xl, top, top + 0.42)
    varrow(ax, xr, top, top + 0.42)
    io_label(ax, xl, top + 0.5, "ẑ_{t+1}  [64]")
    io_label(ax, xr, top + 0.5, "emotion_pred  [9]")

    save(fig, "fig1_arch_single.png")


def sequential_diagram(filename, title, subtitle, internal_dim,
                       pred_uses_internal, crit_uses_internal,
                       pred_dims, crit_dims):
    """Bottom-up sequential layout shared by dual / internal_critic / internal_predictor.

    Two trunks at the bottom (s_t→Encoder, h_t→InternalEncoder) feed a single
    vertical column: Predictor → z_next → Critic → emotion_pred, matching the
    real forward() data dependency (Critic always consumes the PREDICTOR's
    output in every dual-encoder variant — unlike `single`, where it reads z_t
    directly). z_internal is carried up a single bus line on the right and
    tapped into whichever module(s) actually use it; a_t enters each module
    as a plain side arrow (not a merge node) to keep arrowheads uncluttered.
    """
    fig, ax = new_canvas(7.8, 7.8, title, subtitle)

    x_world, x_int = 2.7, 6.1
    io_label(ax, x_world, 0.18, "s_t  [9]", va="bottom")
    io_label(ax, x_int, 0.18, f"h_t  [{internal_dim}]", va="bottom")
    varrow(ax, x_world, 0.4, 0.78)
    varrow(ax, x_int, 0.4, 0.78)
    vbox(ax, x_world, 0.78, 2.15, 0.72, "Encoder", "9 → 128 → 128 → 64", C["encoder"], fontsize=10, subfontsize=8)
    vbox(ax, x_int, 0.78, 2.15, 0.72, "InternalEncoder", f"{internal_dim} → 32 → 16", C["internal_encoder"], fontsize=9.3, subfontsize=8)
    varrow(ax, x_world, 1.5, 1.95, label="z_world")
    y_int_out = 1.95
    ax.add_patch(FancyArrowPatch((x_int, 1.5), (x_int, y_int_out), arrowstyle="-|>",
                 mutation_scale=13, linewidth=1.3, color=C["ink"], zorder=2))

    # z_internal bus: one vertical trunk on the right, tapped wherever needed.
    pred_y_needed = crit_y_needed = None
    cx = x_world  # main predictor/critic column

    y = y_int_out  # tracks the world/critic trunk height as we build upward

    def predictor_box(y0):
        return vbox(ax, cx, y0, 2.55, 0.85, "Predictor", pred_dims, C["predictor"], fontsize=9.8, subfontsize=7.6)

    def critic_box(y0):
        return vbox(ax, cx, y0, 2.55, 0.85, "Critic", crit_dims, C["critic"], fontsize=9.8, subfontsize=7.6)

    # ---- Predictor input ----
    if pred_uses_internal:
        merge_y = y + 0.35
        merge_node(ax, cx, merge_y)
        varrow(ax, cx, y, merge_y - 0.11)
        pred_y_needed = merge_y
        y = merge_y + 0.11
    else:
        y += 0.02
    y_box = y + 0.3
    varrow(ax, cx, y, y_box)
    side_arrow(ax, cx - 1.7, y_box + 0.42, cx - 1.275, "a_t [9]", align="right")
    predictor_box(y_box)
    y = y_box + 0.85
    varrow(ax, cx, y, y + 0.4, label="z_next")
    y += 0.4

    # ---- Critic input ----
    if crit_uses_internal:
        merge_y = y + 0.35
        merge_node(ax, cx, merge_y)
        varrow(ax, cx, y, merge_y - 0.11)
        crit_y_needed = merge_y
        y = merge_y + 0.11
    else:
        y += 0.02
    y_box = y + 0.3
    varrow(ax, cx, y, y_box)
    side_arrow(ax, cx - 1.7, y_box + 0.42, cx - 1.275, "a_t [9]", align="right")
    critic_box(y_box)
    y = y_box + 0.85
    varrow(ax, cx, y, y + 0.42)
    io_label(ax, cx, y + 0.5, "emotion_pred  [9]")

    # z_internal bus, drawn last so tap heights are known
    bus_top = max([h for h in (pred_y_needed, crit_y_needed) if h is not None], default=y_int_out)
    if pred_y_needed or crit_y_needed:
        bus_line(ax, x_int, y_int_out, bus_top)
        if pred_y_needed:
            tap_arrow(ax, x_int, pred_y_needed, cx, label="z_internal")
        if crit_y_needed:
            tap_arrow(ax, x_int, crit_y_needed, cx, label="z_internal")

    save(fig, filename)


def fig_dual():
    sequential_diagram(
        "fig2_arch_dual.png",
        "dual — DualSpeciesModel",
        "internal state informs Predictor AND Critic  ·  internal_state_dim = 4",
        internal_dim=4, pred_uses_internal=True, crit_uses_internal=True,
        pred_dims="concat(z_world,z_internal)+a\n80+9 → 128 → 128 → 64",
        crit_dims="concat(z_next,z_internal)+a\n80+9 → 128 → 128 → 9, tanh",
    )


def fig_internal_critic():
    sequential_diagram(
        "fig3_arch_internal_critic.png",
        "internal_critic — InternalCriticModel",
        "Predictor is world-only; only the Critic sees h_t  ·  internal_state_dim = 4",
        internal_dim=4, pred_uses_internal=False, crit_uses_internal=True,
        pred_dims="z_world + a  (world-only)\n73 → 128 → 128 → 64",
        crit_dims="concat(z_next,z_internal)+a\n89 → 128 → 128 → 9, tanh",
    )


def fig_internal_predictor():
    sequential_diagram(
        "fig4_arch_internal_predictor.png",
        "internal_predictor — InternalPredictorModel",
        "Predictor sees h_t; the Critic is world-only  ·  internal_state_dim = 4",
        internal_dim=4, pred_uses_internal=True, crit_uses_internal=False,
        pred_dims="concat(z_world,z_internal)+a\n89 → 128 → 128 → 64",
        crit_dims="z_next + a  (world-only)\n73 → 128 → 128 → 9, tanh",
    )


def fig_unified_critic():
    fig, ax = new_canvas(8.6, 7.1, "unified_critic — InternalCriticUnifiedModel",
                          "in active use  ·  Predictor + Critic merged into one module; internal_critic routing preserved")

    x_world, x_int = 2.7, 6.4
    io_label(ax, x_world, 0.18, "s_t  [9]", va="bottom")
    io_label(ax, x_int, 0.18, "h_t  [8]", va="bottom")
    varrow(ax, x_world, 0.4, 0.78)
    varrow(ax, x_int, 0.4, 0.78)
    vbox(ax, x_world, 0.78, 2.15, 0.72, "Encoder", "9 → 128 → 128 → 64", C["encoder"], fontsize=10, subfontsize=8)
    vbox(ax, x_int, 0.78, 2.15, 0.72, "InternalEncoder", "8 → 32 → 16", C["internal_encoder"], fontsize=9.3, subfontsize=8)
    varrow(ax, x_world, 1.5, 1.95, label="z_world")
    ax.add_patch(FancyArrowPatch((x_int, 1.5), (x_int, 1.95), arrowstyle="-|>",
                 mutation_scale=13, linewidth=1.3, color=C["ink"], zorder=2))

    cx = x_world

    # UnifiedPredictor container — sized to just wrap world_trunk + emotion_head
    box_y0 = 2.25
    wt_y = box_y0 + 0.35
    varrow(ax, cx, 1.95, wt_y)
    side_arrow(ax, cx - 1.45, wt_y + 0.36, cx - 1.075, "a_t [9]", align="right", fontsize=8.5)
    vbox(ax, cx, wt_y, 2.15, 0.72, "world_trunk", "73 → 128 → 128 → 64", C["predictor"], fontsize=9.3, subfontsize=7.6)

    z_next_y = wt_y + 0.72
    merge_y = z_next_y + 0.4
    merge_node(ax, cx, merge_y)
    varrow(ax, cx, z_next_y, merge_y - 0.11)

    eh_y = merge_y + 0.3
    varrow(ax, cx, merge_y + 0.11, eh_y)
    side_arrow(ax, cx - 1.45, eh_y + 0.36, cx - 1.075, "a_t [9]", align="right", fontsize=8.5)
    vbox(ax, cx, eh_y, 2.15, 0.72, "emotion_head", "89 → 128 → 9, tanh", C["critic"], fontsize=9.3, subfontsize=7.6)

    box_top = eh_y + 0.72 + 0.4
    box_h = box_top - box_y0
    ax.add_patch(FancyBboxPatch((cx - 2.05, box_y0), 4.1, box_h,
                 boxstyle="round,pad=0.03,rounding_size=0.13",
                 linewidth=1.5, edgecolor=C["ink"], facecolor=C["unified_bg"], zorder=0))
    ax.text(cx - 1.9, box_top - 0.12, "UnifiedPredictor", ha="left", va="top",
            fontsize=9.5, fontweight="bold", color=C["ink"], style="italic")

    # z_next exits right, but routed ABOVE the z_internal bus's height range
    # (which only spans 1.95→merge_y) so the two lines never cross.
    x_stub, y_exit = cx + 1.3, merge_y + 0.55
    ax.plot([cx + 1.075, x_stub], [z_next_y, z_next_y], color=C["ink"], linewidth=1.3, zorder=2)
    bus_line(ax, x_stub, z_next_y, y_exit)
    tap_arrow(ax, x_stub, y_exit, 6.95, label=None)
    io_label(ax, 7.55, y_exit, "z_next  [64]", va="center", fontsize=10)

    bus_line(ax, x_int, 1.95, merge_y)
    tap_arrow(ax, x_int, merge_y, cx, label="z_internal", fontsize=7.8)

    emo_top = eh_y + 0.72
    varrow(ax, cx, emo_top, emo_top + 0.6)
    io_label(ax, cx, emo_top + 0.68, "emotion  [9]")

    save(fig, "fig5_arch_unified_critic.png")


# ── Comparison charts ───────────────────────────────────────────────────────────

VARIANT_ORDER = ["single", "dual", "internal_critic", "internal_predictor", "unified_critic"]

PARAM_BREAKDOWN = {
    "single":              {"encoder": 26560, "predictor": 34752, "critic": 27145, "adapter": 4192},
    "dual":                {"encoder": 26560, "internal_encoder": 688, "predictor": 36800, "critic": 29193, "adapter": 4192},
    "internal_critic":     {"encoder": 26560, "internal_encoder": 688, "predictor": 34752, "critic": 29193, "adapter": 4192},
    "internal_predictor":  {"encoder": 26560, "internal_encoder": 688, "predictor": 36800, "critic": 27145, "adapter": 4192},
    "unified_critic":      {"encoder": 26560, "internal_encoder": 816, "unified_predictor": 63945, "adapter": 4192},
}
MODULE_COLOR = {
    "encoder": C["encoder"], "internal_encoder": C["internal_encoder"],
    "predictor": C["predictor"], "critic": C["critic"],
    "adapter": C["adapter"], "unified_predictor": "#E8927C",
}
MODULE_LABEL = {
    "encoder": "Encoder", "internal_encoder": "InternalEncoder", "predictor": "Predictor",
    "critic": "Critic", "adapter": "IndividualAdapter", "unified_predictor": "UnifiedPredictor",
}


def _style_axes(ax):
    ax.spines[["top", "right"]].set_visible(False)
    ax.spines[["left", "bottom"]].set_color(C["ink"])
    ax.spines[["left", "bottom"]].set_linewidth(1.1)
    ax.tick_params(colors=C["ink"])
    ax.yaxis.grid(True, color=C["grid"], linewidth=0.9)
    ax.set_axisbelow(True)


def fig_param_counts():
    fig, ax = plt.subplots(figsize=(8.6, 5.2))
    fig.patch.set_facecolor(C["bg"])
    modules_seen = ["encoder", "internal_encoder", "predictor", "critic", "unified_predictor", "adapter"]
    x = range(len(VARIANT_ORDER))
    bottoms = [0.0] * len(VARIANT_ORDER)
    for mod in modules_seen:
        heights = [PARAM_BREAKDOWN[v].get(mod, 0) for v in VARIANT_ORDER]
        if sum(heights) == 0:
            continue
        ax.bar(x, heights, bottom=bottoms, width=0.58, color=MODULE_COLOR[mod],
               edgecolor=C["ink"], linewidth=1.1, label=MODULE_LABEL[mod])
        bottoms = [b + h for b, h in zip(bottoms, heights)]

    for i, v in enumerate(VARIANT_ORDER):
        total = sum(PARAM_BREAKDOWN[v].values())
        ax.text(i, total + 1500, f"{total:,}", ha="center", va="bottom",
                fontsize=9.5, color=C["ink"], fontweight="bold")

    ax.set_xticks(list(x))
    ax.set_xticklabels(VARIANT_ORDER, fontsize=10)
    ax.set_ylabel("Trainable parameters", fontsize=10, color=C["ink"])
    ax.set_title("Species-model size by variant (all trainable weights)", fontsize=12.5, fontweight="bold", color=C["ink"])
    _style_axes(ax)
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1.0), frameon=False, fontsize=9)
    fig.tight_layout()
    save(fig, "fig6_param_counts.png")


VAL_LOSS = {
    "internal_critic": 0.1683,
    "single": 0.1732,
    "internal_predictor": 0.1750,
    "dual": 0.1884,
}


def fig_val_loss():
    fig, ax = plt.subplots(figsize=(7, 4.8))
    fig.patch.set_facecolor(C["bg"])
    order = sorted(VAL_LOSS, key=VAL_LOSS.get)
    x = range(len(order))
    heights = [VAL_LOSS[v] for v in order]
    ax.bar(x, heights, width=0.5, color=C["encoder"], edgecolor=C["ink"], linewidth=1.1)
    for i, v in enumerate(order):
        ax.text(i, heights[i] + 0.003, f"{heights[i]:.4f}", ha="center", va="bottom",
                fontsize=10, color=C["ink"], fontweight="bold")
    ax.set_xticks(list(x))
    ax.set_xticklabels(order, fontsize=10)
    ax.set_ylabel("Best validation L_pred (MSE, lower = better)", fontsize=9.5, color=C["ink"])
    ax.set_title("Next-latent prediction error, p9 data\n(unified_critic excluded — different dataset, see caveat)",
                 fontsize=11.5, fontweight="bold", color=C["ink"])
    _style_axes(ax)
    ax.set_ylim(0, max(heights) * 1.2)
    fig.tight_layout()
    save(fig, "fig7_val_loss.png")


# ── Runtime pipeline (Java / DJL) — horizontal system diagram ──────────────────

def sysbox(ax, x, y, w, h, title, subtitle, color, fontsize=9.5):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.02,rounding_size=0.08",
                 linewidth=1.3, edgecolor=C["ink"], facecolor=color, zorder=3))
    ax.text(x + w / 2, y + h * 0.64, title, ha="center", va="center",
            color=C["ink"], fontsize=fontsize, fontweight="bold", zorder=4, linespacing=1.3)
    if subtitle:
        ax.text(x + w / 2, y + h * 0.26, subtitle, ha="center", va="center",
                color=C["ink"], fontsize=fontsize - 2, zorder=4, linespacing=1.3)


def sysarrow(ax, p1, p2):
    ax.add_patch(FancyArrowPatch(p1, p2, arrowstyle="-|>", mutation_scale=13,
                 linewidth=1.3, color=C["ink"], zorder=2))


def fig_runtime_pipeline():
    fig, ax = plt.subplots(figsize=(13, 7.2))
    fig.patch.set_facecolor(C["bg"])
    ax.set_xlim(0, 13)
    ax.set_ylim(0, 7.2)
    ax.axis("off")
    ax.text(6.5, 6.95, "Runtime integration (creature/ml/*.java)", ha="center", va="top",
            fontsize=14, fontweight="bold", color=C["ink"])
    ax.text(6.5, 6.55, "waking inference vs. sleep consolidation", ha="center", va="top",
            fontsize=11, color=C["muted"])

    ax.text(0.3, 5.95, "WAKING — synchronous, per candidate action",
            fontsize=10.5, fontweight="bold", color=C["ink"])
    sysbox(ax, 0.3, 4.85, 1.5, 0.85, "s_t, h_t", "creature state", "white")
    sysbox(ax, 2.1, 4.85, 1.6, 0.85, "encoder", "frozen", C["encoder"])
    sysbox(ax, 4.0, 4.85, 1.7, 0.85, "adapter", "per-creature", C["adapter"])
    sysbox(ax, 6.0, 4.85, 2.3, 0.85, "predictor + critic\n(or unified)", "species base, frozen", C["predictor"])
    sysbox(ax, 8.7, 4.85, 2.9, 0.85, "predicted emotional cost", "ranks candidate actions", "white", fontsize=9)
    for p1, p2 in [((1.8, 5.27), (2.1, 5.27)), ((3.7, 5.27), (4.0, 5.27)),
                   ((5.7, 5.27), (6.0, 5.27)), ((8.3, 5.27), (8.7, 5.27))]:
        sysarrow(ax, p1, p2)

    ax.text(0.3, 4.25, "self-disable gate: rolling prediction-error EMA above threshold\n"
            "→ candidate actions pass through unchanged (fall back to reactive behaviour)",
            fontsize=8.5, color=C["muted"], style="italic", va="top")

    ax.text(0.3, 3.35, "SLEEP — batched, cancellable, on a dedicated executor",
            fontsize=10.5, fontweight="bold", color=C["ink"])
    sysbox(ax, 0.3, 2.15, 1.9, 0.9, "recent engrams", "(s_t,a_t,Δemotion,elig.)", "white", fontsize=9)
    sysbox(ax, 2.6, 2.15, 2.4, 0.9, "encoder → adapter →\npredictor/critic", "forward pass through\nfrozen + adapter weights", C["predictor"], fontsize=8.6)
    sysbox(ax, 5.4, 2.15, 2.2, 0.9, "MSE(pred, target)\n× mean(eligibility)", "weighted loss", "white", fontsize=9)
    sysbox(ax, 8.0, 2.15, 2.1, 0.9, "adapter.step()", "only the adapter\nis updated", C["adapter"])
    sysbox(ax, 10.4, 2.15, 2.3, 0.9, "per-batch stats", "persisted for analysis", "white", fontsize=9)
    for p1, p2 in [((2.2, 2.6), (2.6, 2.6)), ((5.0, 2.6), (5.4, 2.6)),
                   ((7.6, 2.6), (8.0, 2.6)), ((10.1, 2.6), (10.4, 2.6))]:
        sysarrow(ax, p1, p2)

    ax.text(0.3, 1.45, "waking up mid-consolidation aborts training between batches —\n"
            "sleep never blocks or delays the creature's cognitive cycle",
            fontsize=8.5, color=C["muted"], style="italic", va="top")

    ax.text(0.3, 0.75,
            "Species base (Encoder/Predictor/Critic/InternalEncoder) is trained OFFLINE and frozen at export time.\n"
            "Only IndividualAdapter — a 4,192-parameter per-creature residual, zero-initialised (identity at birth) — is ever updated online, in-simulation.",
            fontsize=9.5, color=C["ink"], va="top")

    save(fig, "fig8_runtime_pipeline.png")


if __name__ == "__main__":
    fig_building_blocks()
    fig_single()
    fig_dual()
    fig_internal_critic()
    fig_internal_predictor()
    fig_unified_critic()
    fig_param_counts()
    fig_val_loss()
    fig_runtime_pipeline()
    print(f"Wrote figures to {OUT}")
