"""按同一时间参数重建 Work 1 批注图；仅作可行示意，不求最优排程。"""
from pathlib import Path
import json
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.colors import to_rgb
from matplotlib.patches import Rectangle, FancyBboxPatch
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/figures/work1-annotated-20261031"
sys.path.insert(0, "C:/Users/Changxin/.agents/skills/nature-figure/scripts")
from audit_panel_alignment import require_matplotlib_panel_alignment

for font in ["C:/Windows/Fonts/simsun.ttc", "C:/Windows/Fonts/times.ttf"]:
    font_manager.fontManager.addfont(font)
plt.rcParams.update({"font.family": ["SimSun", "Times New Roman", "Arial"], "font.size": 11,
                     "mathtext.fontset": "stix", "pdf.fonttype": 42, "svg.fonttype": "none",
                     "axes.unicode_minus": False})
BLUE, RED, GREEN = "#0655D9", "#D41D25", "#198837"
COLORS = ["#A7CDF6", "#F3B679", "#F3B3C0", "#B7D795", "#BA98E3", "#F4D270"]
L, LW, R, RW = .095, .50, .65, .50*18/28
E, D, P = 10., 18., 4.
JOBS = [
    dict(j=1, m=1, S=0, p=4, e=1, d=3.5),
    dict(j=2, m=2, S=0, p=3, e=1, d=4),
    dict(j=3, m=1, S=4, p=4, e=10, d=18),
    dict(j=4, m=2, S=6, p=6, e=7, d=11),
    dict(j=5, m=1, S=12, p=6, e=21, d=25),
    dict(j=6, m=2, S=12, p=5, e=15, d=20),
]


def header(fig, x, y, width, title, size=14, height=.028):
    fig.add_artist(Rectangle((x, y), width, height, transform=fig.transFigure,
                            facecolor="#E7F1FC", edgecolor="#B0CFF0", lw=.65, zorder=-3))
    fig.text(x+width/2, y+height/2, title, ha="center", va="center", fontsize=size, fontweight="bold",
             fontfamily="Times New Roman" if title.isascii() else "SimSun")


def frame(fig, x, bottom, width, height, title, size=14):
    fig.add_artist(FancyBboxPatch((x, bottom), width, height,
        boxstyle="round,pad=0.004,rounding_size=0.004", transform=fig.transFigure,
        facecolor="white", edgecolor="#A9CFF3", lw=.8, zorder=-5))
    header(fig, x, bottom+height-.028, width, title, size)


def bare_axis(ax, limits=(0, 28), ylim=(0, 4.2)):
    ax.set(xlim=limits, ylim=ylim)
    ax.set_axis_off()


def time_line(ax, y):
    lo, hi = ax.get_xlim()
    ax.annotate("", (hi, y), (lo, y), arrowprops=dict(arrowstyle="-|>", lw=.8, color="black"), zorder=1)
    ax.text(hi-.1, y-.30, "时间", ha="right", fontsize=9.5)


def job(ax, j, S, p, y, height=.50, named=False, text_x=None):
    """加工矩形保留任务原色，底纹另加，完工点严格在右边界。"""
    rgb = np.array(to_rgb(COLORS[j-1]))
    shade = np.linspace(.32, 0, 96)[:, None, None]
    gradient = np.repeat((rgb[None, None, :]*(1-shade)+shade), 2, axis=1)
    ax.imshow(gradient, extent=(S, S+p, y, y+height), origin="upper", aspect="auto", zorder=3)
    ax.add_patch(Rectangle((S, y), p, height, facecolor="none", edgecolor="black", lw=.75, zorder=5))
    ax.text(S+p/2 if text_x is None else text_x, y+height/2, f"$J_{j}(p_{j})$", ha="center", va="center", fontsize=13, zorder=6)
    ax.plot(S+p, y+height, "o", color="black", ms=3.8, zorder=7)
    if named:
        ax.plot(S, y, "o", color="black", ms=2.5, zorder=7)
        ax.text(S, y-.55, "$S_3$", ha="center", fontsize=10)
        ax.text(S+p+.34, y+height+.12, "$C_3$", fontsize=10)


def window(ax, e, d, y, j=3):
    ax.plot([e, d], [y, y], color=GREEN, lw=1.5)
    ax.plot([e, e, np.nan, d, d], [y-.06, y+.06, np.nan, y-.06, y+.06], color=GREEN, lw=.9)
    ax.text((e+d)/2, y+.13, f"$[e_{j},d_{j}]$", ha="center", color=GREEN, fontsize=11)


def stripe(ax, lo, hi, y, height, color):
    """直接绘制区间内的短斜线，避免 PDF 图案平铺的裁剪框干扰文字检查。"""
    ax.add_patch(Rectangle((lo, y), hi-lo, height, facecolor="none", edgecolor=color, lw=.65, zorder=5))
    sx = ax.bbox.width/(ax.get_xlim()[1]-ax.get_xlim()[0])
    sy = ax.bbox.height/(ax.get_ylim()[1]-ax.get_ylim()[0])
    dx = height*sy/sx
    step = 3.5*ax.figure.dpi/(72*sx)
    for start in np.arange(lo-dx, hi, step):
        a, b = max(lo, start), min(hi, start+dx)
        if b > a:
            ya = y+(a-start)*height/dx
            yb = y+(b-start)*height/dx
            ax.plot([a, b], [ya, yb], color=color, lw=.6, zorder=5)


def main_schedule(ax):
    bare_axis(ax, ylim=(0, 4.1))
    for m, y in [(1, 2.45), (2, .55)]:
        time_line(ax, y)
        ax.text(-.6, y+.17, f"$M_{m}$", ha="right", va="center", fontsize=13)
        previous = 0
        for x in [x for x in JOBS if x["m"] == m]:
            j, S, p = x["j"], x["S"], x["p"]
            if S > previous:
                ax.annotate("", (S-.15, y-.12), (previous+.15, y-.12),
                            arrowprops=dict(arrowstyle="|-|", color="#7B8290", lw=.8, ls="--"))
                ax.text((S+previous)/2, y-.44, "Idle time", ha="center", fontsize=10, style="italic", fontfamily="Times New Roman")
            job(ax, j, S, p, y)
            ax.text(S+p+.13, y+.64, f"$C_{j}$", fontsize=10)
            window(ax, x["e"], x["d"], y+1.05, j)
            previous = S+p


def positions(ax, quantities=False):
    """左侧用独立时间差条带，右侧仅给实际加工区间的交叠部分加纹。"""
    bare_axis(ax, (6, 24) if quantities else (0, 28))
    ax.axvspan(E, D, facecolor=GREEN, alpha=.035, edgecolor="none", zorder=0)
    for row, y in enumerate([2.65, .75]):
        time_line(ax, y)
        window(ax, E, D, y+.96)
        for t, label in [(E, "$e_3$"), (D, "$d_3$")]:
            ax.plot([t, t], [y+.55, y+.90], color="#BAC0C6", ls="--", lw=.6, zorder=0)
            ax.plot([t, t], [y-.045, y+.045], color=GREEN, lw=.8, zorder=6)
            ax.text(t, y-.55, label, ha="center", fontsize=10)
        S = (8 if row == 0 else 16) if quantities else (4 if row == 0 else 16)
        C = S+P
        clean_label = ((E+C)/2 if row == 0 else (S+D)/2) if quantities else None
        job(ax, 3, S, P, y, named=True, text_x=clean_label)
        color = BLUE if row == 0 else RED
        if quantities:
            lo, hi = (S, E) if row == 0 else (D, C)
            stripe(ax, lo, hi, y, .50, color)
            ax.annotate("", (lo, y+.69), (hi, y+.69), arrowprops=dict(arrowstyle="<->", lw=.8, color=color))
            ax.text((lo+hi)/2, y+.86, "$EW_3$" if row == 0 else "$TW_3$", ha="center", color=color, fontsize=10)
        else:
            lo, hi = (C, E) if row == 0 else (D, C)
            stripe(ax, lo, hi, y-.18, .12, color)
            ax.text((lo+hi)/2, y-.60, "$E_3$" if row == 0 else "$T_3$", ha="center", color=color, fontsize=10)
            ax.text(.2, y+.25, "提前完工" if row == 0 else "拖期完工", color=color, fontsize=11, va="center")


def costs(family, C):
    C = np.asarray(C)
    early, tardy = np.maximum(E-C, 0), np.maximum(C-D, 0)
    if family == 0:
        return early+1.5*tardy+2*np.maximum(early-2, 0)+2*np.maximum(tardy-2, 0)
    if family == 1:
        return sum(a*(early > u) for a, u in [(2, 0), (1, 2), (2, 4)])+sum(
            b*(tardy > v) for b, v in [(3, 0), (2, 2), (2, 4)])
    return np.clip(E-C+P, 0, P)+1.5*np.clip(C-D, 0, P)


def loss_axis(ax, family):
    ax.set(xlim=(6, 24) if family == 2 else (0, 28), ylim=(-.4, [33, 8, 7][family]))
    ax.spines[["top", "right"]].set_visible(False)
    ax.spines[["left", "bottom"]].set_linewidth(.75)
    ax.set_yticks([0])
    ax.set_ylabel("" if family == 2 else "损失", fontsize=11, rotation=0, rotation_mode="anchor", labelpad=12)
    if family == 2:
        ax.text(6.05, 6.55, "损失", fontsize=11)
    ax.set_xlabel("" if family == 0 else "完工时刻 $C_3$", loc="right", fontsize=10, labelpad=2)
    if family == 0:
        ax.text(27.9, 1, "$C_3$", ha="right", fontsize=10)
    ax.tick_params(labelsize=10, length=3, width=.65)
    ax.axvspan(E, D, facecolor=GREEN, alpha=.035, edgecolor="none")
    if family == 1:
        sections = [(0, 6, 5), (6, 8, 3), (8, 10, 2), (10, 18, 0), (18, 20, 3), (20, 22, 5), (22, 28, 7)]
        for a, b, value in sections:
            col = BLUE if b <= E else RED if a >= D else "black"
            ax.plot([a, b], [value, value], color=col, lw=1.5)
        for i in range(1, len(sections)):
            x, _, v = sections[i]
            ax.plot([x, x], [sections[i-1][2], v], color=BLUE if x <= E else RED, lw=1.0)
        ax.set_xticks([6, 8, E, D, 20, 22], ["$e_3-u_2$", "$e_3-u_1$", "$e_3$", "$d_3$", "$d_3+v_1$", "$d_3+v_2$"])
    else:
        x = np.array([0, 8, E, D, 20, 28]) if family == 0 else np.array([6, E, E+P, D, D+P, 24])
        y = costs(family, x)
        zero_start = E if family == 0 else E+P
        for mask, col in [(x <= zero_start, BLUE), ((x >= zero_start) & (x <= D), "black"), (x >= D, RED)]:
            ax.plot(x[mask], y[mask], color=col, lw=1.6)
        if family == 0:
            ax.set_xticks([8, E, D, 20], ["$e_3-u$", "$e_3$", "$d_3$", "$d_3+v$"])
            ax.text(1.0, 29, r"斜率 $-(\alpha+\gamma)$", color=BLUE, fontsize=9.5)
            ax.text(6.0, 10, r"斜率 $-\alpha$", color=BLUE, fontsize=9.5)
            ax.text(18.6, 7, r"斜率 $\beta$", color=RED, fontsize=9.5)
            ax.text(23, 29, r"斜率 $\beta+\delta$", color=RED, fontsize=9.5)
        else:
            ax.set_xticks([E, E+P, D, D+P], ["$e_3$", "$e_3+p_3$", "$d_3$", "$d_3+p_3$"])
            ax.text(6.1, P+.3, "$\\alpha p_3$", fontsize=11, color=BLUE)
            ax.text(23.9, 1.5*P+.3, "$\\beta p_3$", ha="right", fontsize=11, color=RED)


def parameters(ax):
    bare_axis(ax, (0, 1), (0, 1))
    lines = ["$p_j$：给定加工时长", "$[e_j,d_j]$：给定交付窗口", "$S_j$：调度决策的开始时刻",
             "$C_j$：调度确定的完工时刻", "$C_j=S_j+p_j$"]
    for k, line in enumerate(lines):
        ax.text(.035, .98-.15*k, line, fontsize=11.5, va="top")
    job(ax, 3, .12, .50, .09, .13)
    ax.text(.12, .005, "$S_j$", ha="center", fontsize=10)
    ax.text(.62, .005, "$C_j$", ha="center", fontsize=10)


def build():
    fig = plt.figure(figsize=(18, 13.5), facecolor="white")
    frame(fig, .025, .767, .590, .217, "Work 1：给定窗口的并行机调度", 20)
    frame(fig, .635, .767, .345, .217, "参数与决策", 18)
    frame(fig, .025, .085, .590, .663, "同一任务的完工偏差", 16)
    frame(fig, .635, .085, .345, .663, "同一任务的窗口外加工量", 16)
    axes = {}
    axes["main"] = fig.add_axes([L, .785, LW, .155])
    main_schedule(axes["main"])
    parameters(fig.add_axes([R, .785, RW, .155]))
    axes["completion"] = fig.add_axes([L, .548, LW, .155])
    positions(axes["completion"])
    axes["processing"] = fig.add_axes([R, .548, RW, .155])
    positions(axes["processing"], True)

    header(fig, .031, .492, .578, "Weighted earliness and tardiness cost", 15)
    fig.text(.320, .481, "凸分段线性推广", ha="center", fontsize=10)
    fig.text(.320, .461, r"$\varphi_3(C_3)=\alpha E_3+\beta T_3+\gamma(E_3-u)^++\delta(T_3-v)^+$", ha="center", fontsize=12)
    axes["convex"] = fig.add_axes([L, .337, LW, .105])
    loss_axis(axes["convex"], 0)

    header(fig, .031, .283, .578, "Weighted number of early and tardy jobs", 15)
    fig.text(.320, .270, "多档阶跃推广（Stepwise extension）", ha="center", fontsize=10)
    fig.text(.320, .250, r"$\varphi_3(C_3)=\sum a_r\mathbf{1}\{E_3>u_r\}+\sum b_r\mathbf{1}\{T_3>v_r\}$", ha="center", fontsize=12)
    fig.text(.320, .231, "对 $r=0,1,2$ 求和，$u_0=v_0=0$；仅保留 $r=0$ 时为标准加权早晚任务数", ha="center", fontsize=9.5)
    axes["number"] = fig.add_axes([L, .120, LW, .095])
    loss_axis(axes["number"], 1)

    header(fig, .641, .492, .333, "Weighted early work and tardy work cost", 14.2)
    fig.text(.807, .480, "按窗口外实际加工量计费", ha="center", fontsize=10.5)
    fig.text(.807, .450, r"$EW_3=\min\{p_3,(e_3-S_3)^+\}$", ha="center", fontsize=12)
    fig.text(.807, .424, r"$TW_3=\min\{p_3,(C_3-d_3)^+\}$", ha="center", fontsize=12)
    fig.text(.807, .398, r"$\varphi_3(C_3)=\alpha EW_3+\beta TW_3$", ha="center", fontsize=12.5)
    axes["work"] = fig.add_axes([R, .225, RW, .150])
    loss_axis(axes["work"], 2)
    fig.text(.655, .168, r"比较加工区间 $[S_3,C_3]$ 与给定窗口 $[e_3,d_3]$。", fontsize=10.5)
    fig.text(.655, .142, "蓝纹、红纹分别表示窗口前、窗口后的加工量。", fontsize=10.5)

    for i, color in enumerate(COLORS):
        fig.add_artist(Rectangle((.035+i*.018, .048), .014, .014, transform=fig.transFigure,
                                facecolor=color, edgecolor="black", lw=.6))
    fig.text(.150, .050, "加工", fontsize=10)
    fig.text(.205, .050, "● 完工点", fontsize=10)
    fig.text(.295, .050, "绿色区间：给定窗口", color=GREEN, fontsize=10)
    fig.text(.465, .050, "蓝／红实线：提前／拖期损失", fontsize=10)
    fig.text(.690, .050, "底纹：对应示意中的计费区间", fontsize=10)
    fig.text(.035, .022, r"$(x)^+=\max\{x,0\}$；$E_3=(e_3-C_3)^+$，$T_3=(C_3-d_3)^+$；$u_r,v_r$为分档偏差阈值；费用权重非负；备选位置不同时执行。", fontsize=10)

    fig.canvas.draw()
    require_matplotlib_panel_alignment(fig, json_out=str(OUT/"work1-annotated.alignment.json"),
        overlay_svg=str(OUT/"work1-annotated.alignment.svg"), axes=list(axes.values()),
        panel_ids=list(axes), row_groups=[], column_groups=[["main", "completion", "convex", "number"], ["processing", "work"]],
        exemptions=[{"panels": ["main", "completion"], "checks": ["vertical-gutter"],
                     "reason": "The schedule and position diagram have different roles and require distinct formula/header spacing; shared x coordinates remain fully checked."}], strict=True)
    coordinate_check = {}
    widths = []
    for name, ax in axes.items():
        left = ax.transData.transform((E, 0))[0]*72/fig.dpi
        right = ax.transData.transform((D, 0))[0]*72/fig.dpi
        coordinate_check[name] = {"e_pt": left, "d_pt": right, "width_pt": right-left}
        widths.append(right-left)
    assert max(widths)-min(widths) < 1e-8
    for name in ["completion", "convex", "number"]:
        assert abs(coordinate_check[name]["e_pt"]-coordinate_check["main"]["e_pt"]) < 1e-8
        assert abs(coordinate_check[name]["d_pt"]-coordinate_check["main"]["d_pt"]) < 1e-8
    (OUT/"coordinate-check.json").write_text(json.dumps(coordinate_check, indent=2), encoding="utf-8")
    fig.savefig(OUT/"work1-annotated.png", dpi=300)
    fig.savefig(OUT/"work1-annotated.pdf")
    fig.savefig(OUT/"work1-annotated.svg")
    svg = OUT/"work1-annotated.svg"
    svg.write_text("\n".join(line.rstrip() for line in svg.read_text(encoding="utf-8").splitlines())+"\n", encoding="utf-8")
    plt.close(fig)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for m in [1, 2]:
        end = 0
        for x in [x for x in JOBS if x["m"] == m]:
            assert x["S"] >= end
            end = x["S"]+x["p"]
    knots = np.array([0., 8., E, D, 20., 28.])
    slopes = np.diff(costs(0, knots))/np.diff(knots)
    assert np.allclose(slopes, [-3, -1, 0, 1.5, 3.5]) and np.all(np.diff(slopes) >= 0)
    for C in np.linspace(6, 24, 181):
        S = C-P
        ew = max(0, min(C, E)-S)
        tw = max(0, C-max(S, D))
        assert abs(float(costs(2, C))-(ew+1.5*tw)) < 1e-8
    (OUT/"source-data.json").write_text(json.dumps({"purpose": "illustrative feasible schedule, not optimized", "jobs": JOBS,
        "job3_window": [E, D], "job3_processing": P, "completion_examples": [[4, 8], [16, 20]],
        "work_examples": [[8, 12], [16, 20]], "convex_slopes": slopes.tolist(),
        "thresholds": {"u": [0, 2, 4], "v": [0, 2, 4]},
        "weights": {"alpha": 1, "beta": 1.5, "gamma": 2, "delta": 2, "a": [2, 1, 2], "b": [3, 2, 2]}},
        ensure_ascii=False, indent=2), encoding="utf-8")
    build()
    print("Aligned figure exported; common endpoint/width, feasibility, convexity and processing-intersection checks passed.")
