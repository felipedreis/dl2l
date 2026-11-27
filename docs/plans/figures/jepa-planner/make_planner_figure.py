g = 2 ** (-1/5)
W, H = 1600, 1180
INK, MUTE, LINE = "#1f2933", "#52606d", "#cbd2d9"
BLUE, GREEN, RED, AMBER, GREY = "#2f6fb0", "#2d8a5b", "#c0392b", "#b7791f", "#eef1f4"
o = []
def t(x, y, s, size=18, w=400, fill=INK, anchor="start", it=False):
    o.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{w}" fill="{fill}" text-anchor="{anchor}"{" font-style=\"italic\"" if it else ""}>{s}</text>')
def r(x, y, w, h, fill="#fff", stroke=LINE, sw=1.5, rx=8, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    o.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{d}/>')
def ln(x1, y1, x2, y2, c=MUTE, sw=2, dash=None, arrow=False):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    m = ' marker-end="url(#ar)"' if arrow else ""
    o.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{c}" stroke-width="{sw}"{d}{m}/>')

o.append(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" font-family="Helvetica, Arial, sans-serif">')
o.append(f'<defs><marker id="ar" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="{MUTE}"/></marker></defs>')
o.append(f'<rect width="{W}" height="{H}" fill="#fff"/>')
t(40, 46, "JEPA planner: what it is trained on, what it predicts, how the arbiter uses it", 28, 700)

# ---------- Panel A
t(40, 100, "A  One training row: the label for an APPROACH step is what follows it, not what it does itself", 20, 700, BLUE)
x0, bw, gap, y0 = 150, 112, 8, 130
acts = ["APPROACH"]*6 + ["EAT", "WANDER", "WANDER", "WANDER"]
K = [0,0,0,0,0,0,-0.29,0,0,0]
for i, a in enumerate(acts):
    x = x0 + i*(bw+gap)
    col = "#e4eefb" if a == "APPROACH" else ("#e2f3ea" if a == "EAT" else GREY)
    r(x, y0, bw, 44, col, BLUE if i == 0 else LINE, 3 if i == 0 else 1.5)
    t(x+bw/2, y0+28, a, 16, 600, INK, "middle")
    t(x+bw/2, y0-10, "t" if i == 0 else f"t+{i}", 15, 400, MUTE, "middle")
t(40, y0+28, "action", 16, 600, MUTE)
# K bars
yk = 215
t(40, yk+34, "hunger K", 16, 600, MUTE); t(40, yk+54, "(per act)", 14, 400, MUTE)
ln(x0, yk+20, x0+10*(bw+gap)-gap, yk+20, LINE, 1.5)
for i, k in enumerate(K):
    x = x0 + i*(bw+gap)
    if k:
        h = abs(k)*190
        r(x+bw/2-22, yk+20, 44, h, GREEN, GREEN, 1, 3)
        t(x+bw/2, yk+20+h+20, "-0.29", 15, 700, GREEN, "middle")
    else:
        t(x+bw/2, yk+16, "0", 14, 400, MUTE, "middle")
t(x0+6*(bw+gap)+bw/2, yk+116, "(illustrative)", 12, 400, MUTE, "middle", True)
# weights
yw = 345
t(40, yw+24, "weight γ^j", 16, 600, MUTE); t(40, yw+44, "γ = 2^(-1/5)", 14, 400, MUTE)
for i in range(10):
    x = x0 + i*(bw+gap); w = g**i
    r(x+bw/2-22, yw+60-w*55, 44, w*55, AMBER, AMBER, 1, 3)
    t(x+bw/2, yw+80, f"{w:.2f}", 14, 600, INK, "middle")
t(x0+10*(bw+gap)+6, yw+30, "… to t+29", 15, 400, MUTE)
t(x0+10*(bw+gap)+6, yw+50, "weight 0.02", 15, 400, MUTE)
# result
r(150, 450, 700, 78, "#fff7e6", AMBER, 2)
t(168, 480, "label for the APPROACH at t  =  Σ  γ^j · K(t+j)  over 30 cycles", 18, 700)
t(168, 508, f"here  =  (-0.29) × {g**6:.2f}  =  {-0.29*g**6:.2f} hunger        (an immediate-only label would be 0)", 17, 400)
r(890, 450, 670, 78, GREY, LINE, 1.5)
t(908, 476, "Why 30: after 6 half-lives (5 cycles each) the weight is 0.02, so more cycles add nothing.", 15, 400)
t(908, 500, "No episode cut: a timeout cannot zero the APPROACH credit (the p117 flaw).", 15, 400)
t(908, 520, "Value is under the behaviour that followed (a3_kal + exploration), not the best possible.", 15, 400, MUTE)

# ---------- Panel B
t(40, 590, "B  The model: one row in, expected change in each drive out", 20, 700, BLUE)
yb = 620
ins = [("candidate percept", "type, distance, bearing, contact"), ("drives h", "hunger, sleep, pain, tedium"), ("action", "APPROACH, EAT, AVOID, …")]
for i, (a, b) in enumerate(ins):
    y = yb + i*70
    r(40, y, 300, 58, "#fff", LINE, 1.5)
    t(56, y+25, a, 17, 700); t(56, y+46, b, 14, 400, MUTE)
    ln(340, y+29, 420, yb+99, MUTE, 2, None, True)
r(425, yb+55, 170, 90, "#e4eefb", BLUE, 2)
t(510, yb+95, "encoders", 18, 700, INK, "middle"); t(510, yb+118, "z(percept), z(h)", 14, 400, MUTE, "middle")
ln(595, yb+100, 660, yb+100, MUTE, 2, None, True)
r(665, yb+20, 230, 160, "#e4eefb", BLUE, 2)
t(780, yb+52, "critic: 5 heads", 18, 700, INK, "middle")
for i in range(5):
    r(690+i*38, yb+72, 30, 46, "#fff", BLUE, 1.5, 5)
t(780, yb+148, "trained on the labels of A", 14, 400, MUTE, "middle")
t(780, yb+168, "bootstrap by seed", 14, 400, MUTE, "middle")
ln(895, yb+70, 990, yb+55, MUTE, 2, None, True); ln(895, yb+130, 990, yb+145, MUTE, 2, None, True)
r(995, yb+10, 330, 80, "#e2f3ea", GREEN, 2)
t(1010, yb+40, "μ: expected change per drive", 17, 700); t(1010, yb+64, "(mean of the 5 heads, 4 values)", 14, 400, MUTE)
r(995, yb+108, 330, 80, "#fdecea", RED, 2)
t(1010, yb+138, "SE: do the 5 heads disagree?", 17, 700); t(1010, yb+162, "σ: spread of single outcomes", 14, 400, MUTE)
r(1350, yb+10, 210, 178, GREY, LINE, 1.5, 8, "6 4")
t(1455, yb+40, "optional", 15, 700, MUTE, "middle"); t(1455, yb+64, "predictor ẑ(t+1)", 15, 400, MUTE, "middle")
t(1455, yb+90, "only for beam search;", 13, 400, MUTE, "middle"); t(1455, yb+108, "the plan's #135 ablation", 13, 400, MUTE, "middle"); t(1455, yb+126, "tests if it helps", 13, 400, MUTE, "middle")

# ---------- Panel C
t(40, 860, "C  At run time: every cycle, all candidates scored in one batch, then the arbiter decides", 20, 700, BLUE)
yc = 890
r(40, yc, 320, 230, "#fff", LINE, 1.5)
t(56, yc+28, "candidates in view", 17, 700)
cands = [("APPROACH green apple", "0.07"), ("EAT red apple", "0.13"), ("AVOID cactus", "0.00"), ("WANDER", "0.01"), ("… 10 to 25 in total", "")]
for i, (c, v) in enumerate(cands):
    t(56, yc+62+i*34, c, 16, 400)
    if v: t(344, yc+62+i*34, v, 16, 700, GREEN, "end")
t(344, yc+44, "Q", 13, 700, MUTE, "end")
t(56, yc+222, "Q = V(μ | current need): drive reduction, illustrative", 12, 400, MUTE, "start", True)
ln(360, yc+115, 440, yc+115, MUTE, 2, None, True)
t(400, yc+100, "1 call", 13, 400, MUTE, "middle")
r(445, yc+60, 190, 110, "#e4eefb", BLUE, 2)
t(540, yc+98, "model B", 18, 700, INK, "middle"); t(540, yc+122, "→ Q, SE, σ for each", 15, 400, MUTE, "middle"); t(540, yc+144, "far from training → abstain", 13, 400, MUTE, "middle")
ln(635, yc+115, 715, yc+115, MUTE, 2, None, True)
# arbiter
r(720, yc, 840, 230, "#fff", INK, 2)
t(740, yc+30, "Arbiter: cheapest controller first, stop when one is decisive", 18, 700)
cx = [745, 1015, 1285]
cn = [("1 operant (Kalman)", "cost 0", "per object, what to do"), ("2 episodic memory", "needs efficiency e ≥ 0.3", "similar past episodes"), ("3 PLANNER (new)", "needs e ≥ 0.7", "population-trained critic")]
for i, (a, b, c) in enumerate(cn):
    col = "#e2f3ea" if i == 2 else GREY
    r(cx[i], yc+55, 240, 100, col, GREEN if i == 2 else LINE, 3 if i == 2 else 1.5)
    t(cx[i]+14, yc+86, a, 17, 700); t(cx[i]+14, yc+110, b, 14, 400, MUTE); t(cx[i]+14, yc+132, c, 14, 400, MUTE)
    if i < 2: ln(cx[i]+240, yc+105, cx[i+1], yc+105, MUTE, 2, None, True)
t(740, yc+190, "Each reports Q, SE, σ in the same unit. Thompson draw among candidates of the deciding controller → chosen action.", 15, 400)
t(740, yc+214, "Arms: planner alone · a3_kal + planner · a3_kal + shuffled planner (\"any prior\" control).", 15, 400, MUTE)
o.append('</svg>')
open("planner.svg", "w")  # render: rsvg-convert -z 2 planner.svg -o planner.png.write("\n".join(o))
