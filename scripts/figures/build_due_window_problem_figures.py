"""绘制任务参数、窗口约束和三类偏差费用的二维示意；数据仅用于解释问题。"""
from pathlib import Path
import json
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.patches import Rectangle
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/figures/due-window-problem-v2-20261031"
SKILL = Path("C:/Users/Changxin/.agents/skills/nature-figure/scripts")
sys.path.insert(0, str(SKILL))
from audit_panel_alignment import require_matplotlib_panel_alignment

font_manager.fontManager.addfont("C:/Windows/Fonts/msyh.ttc")
plt.rcParams.update({"font.family": ["Microsoft YaHei", "Arial", "DejaVu Sans"], "font.size": 10,
                     "mathtext.fontset": "dejavusans", "axes.unicode_minus": False,
                     "svg.fonttype": "none", "pdf.fonttype": 42})
COLORS = ["#4E9F96", "#648BBC", "#DAA341", "#927AB4", "#C97970", "#8D9E61"]
GREEN, BLUE, RED, GRAY = "#438A67", "#4B86B4", "#C96963", "#84929B"
JOBS = [
    dict(j=1, m=1, S=0, p=5, A=4, B=14, e=7, d=11, Dmin=2, Dmax=8),
    dict(j=2, m=2, S=1, p=6, A=3, B=12, e=5, d=9, Dmin=2, Dmax=6),
    dict(j=3, m=1, S=8, p=5, A=9, B=24, e=12, d=20, Dmin=4, Dmax=10),
    dict(j=4, m=2, S=10, p=6, A=10, B=21, e=13, d=17, Dmin=2, Dmax=8),
    dict(j=5, m=1, S=21, p=6, A=18, B=29, e=21, d=25, Dmin=3, Dmax=8),
    dict(j=6, m=2, S=19, p=5, A=17, B=28, e=20, d=23, Dmin=2, Dmax=6),
]
SCENARIOS = [
    {1: (0, 4), 3: (8, 2), 5: (21, 5), 2: (1, 5), 4: (10, 5), 6: (19, 4)},
    {x["j"]: (x["S"], x["p"]) for x in JOBS},
    {1: (0, 6), 3: (8, 15), 5: (24, 7), 2: (1, 7), 4: (10, 7), 6: (19, 6)},
]
TITLES = {"ET": "ET：完工时点的提前与拖期", "COUNT": "计数型：早到／晚到事件与多档罚金",
          "WORK": "Work：窗口外的实际加工量"}


def measures(C, p=5, e=12, d=20):
    """分别计算完工偏差和加工区间与窗口外区域的交叠长度。"""
    C = np.asarray(C)
    return np.maximum(e-C, 0), np.maximum(C-d, 0), np.clip(e-C+p, 0, p), np.clip(C-d, 0, p)


def losses(kind, C, general=False):
    E, T, EW, TW = measures(C)
    if kind == "ET":
        return 2*E + 3*T
    if kind == "COUNT":
        if not general:
            return 2*(E > 0) + 3*(T > 0)
        return sum(a*(E > z) for a, z in [(2, 0), (1, 2), (2, 5)]) + sum(
            b*(T > z) for b, z in [(3, 0), (2, 2), (2, 5)])
    value = 2*EW + 3*TW
    return value + np.maximum(EW-2, 0) + 2*np.maximum(TW-2, 0) if general else value


def check_example():
    """检查示意排程可行、窗口域以及费用区间恒等式，不求解最优排程。"""
    for x in JOBS:
        assert x["A"] <= x["e"] <= x["d"] <= x["B"]
        assert x["Dmin"] <= x["d"]-x["e"] <= x["Dmax"]
    for scenario in SCENARIOS:
        for m in (1, 2):
            end = 0
            for x in sorted((x for x in JOBS if x["m"] == m), key=lambda x: x["S"]):
                S, p = scenario[x["j"]]
                assert S >= end and p > 0
                end = S+p
    assert tuple(float(x) for x in measures(13)) == (0, 0, 4, 0)
    assert float(losses("ET", 13)) == 0
    assert float(losses("WORK", 13)) == 8
    assert float(losses("WORK", 13, True)) == 10
    assert float(losses("COUNT", 12, True)) == 0
    assert float(losses("COUNT", 20, True)) == 0
    for C in np.linspace(5, 32, 109):
        _, _, EW, TW = measures(C)
        inside = max(0, min(C, 20)-max(C-5, 12))
        assert abs(float(EW+TW)+inside-5) < 1e-9


def base_axis(ax, ylim, ylabel=None):
    ax.set(xlim=(0, 32), ylim=ylim)
    ax.spines[["top", "right", "left"]].set_visible(False)
    ax.spines["bottom"].set_color("#ABB3B8")
    ax.set_yticks([])
    ax.tick_params(axis="x", colors="#606F78", length=3, labelsize=9)
    if ylabel:
        ax.set_ylabel(ylabel, fontsize=10)


def processing(ax, j, S, p, y, height=.48, label=True):
    color = COLORS[j-1]
    ax.add_patch(Rectangle((S, y), p, height, facecolor=color, edgecolor="white", lw=.8, zorder=3))
    if label:
        ax.text(S+p/2, y+height/2, f"$J_{j}$", va="center", ha="center", color="white", fontsize=10, zorder=4)
    ax.plot(S+p, y+height, "o", color="#26343C", ms=3.5, zorder=5)


def schedule_panel(ax, work):
    """任务窗口用独立细轨显示；加工块与完工点使用同一时间坐标。"""
    base_axis(ax, (0, 6.9))
    ax.set_xticks([0, 8, 16, 24, 32])
    ax.set_xlabel("时间 $t$", loc="right", labelpad=1, fontsize=9)
    for m, ybar, tracks in [(1, 3.78, [6.15, 5.55, 4.95]), (2, .52, [2.90, 2.30, 1.70])]:
        ax.text(-.8, ybar+.17, f"$M_{m}$", ha="right", va="center", fontsize=12)
        for x, y in zip([x for x in JOBS if x["m"] == m], tracks):
            j, S, p, e, d, A, B = (x[k] for k in ["j", "S", "p", "e", "d", "A", "B"])
            if work == 2:
                ax.plot([A, B], [y, y], color=GRAY, lw=1, ls=(0, (3, 2)))
                ax.plot([A, A], [y-.08, y+.08], color=GRAY, lw=.8)
                ax.plot([B, B], [y-.08, y+.08], color=GRAY, lw=.8)
                ax.text(A-.3, y, f"$J_{j}$", ha="right", va="center", color=COLORS[j-1], fontsize=9)
            else:
                ax.text(e-.3, y, f"$J_{j}$", ha="right", va="center", color=COLORS[j-1], fontsize=9)
            ax.plot([e, d], [y, y], color=GREEN, lw=4, alpha=.6, solid_capstyle="butt")
            ax.plot([e, d], [y, y], "o", color=GREEN, ms=3)
            if j == 3:
                ax.text((e+d)/2, y+.27, r"$[e_3,d_3]=[12,20]$", ha="center", fontsize=9, color=GREEN)
                if work == 2:
                    ax.text((A+B)/2, 6.60, r"$[A_3,B_3]=[9,24]$", ha="center", fontsize=9, color=GRAY)
            processing(ax, j, S, p, ybar)
            if j == 3:
                ax.plot([S+p, S+p], [ybar+.48, y-.15], ls=":", color=COLORS[2], lw=1)
                ax.text(S+p+.23, ybar+.68, "$C_3=13$", fontsize=9, color="#3D454C")
            ax.text(S+p/2, ybar-.24, f"$p_{j}={p}$", ha="center", fontsize=8)


def stochastic_panel(ax):
    base_axis(ax, (0, 6.9))
    ax.set_xticks([0, 8, 16, 24, 32])
    ax.set_xlabel("时间 $t$", loc="right", labelpad=1, fontsize=9)
    for m, ys, focus in [(1, [5.50, 4.75, 4.00], JOBS[2]), (2, [2.45, 1.70, .95], JOBS[3])]:
        ax.add_patch(Rectangle((focus["e"], min(ys)-.12), focus["d"]-focus["e"], max(ys)-min(ys)+.66,
                               facecolor=GREEN, alpha=.09, zorder=0))
        ax.text((focus["e"]+focus["d"])/2, max(ys)+.78,
                f"$J_{focus['j']}$：同一窗口跨场景共享", ha="center", fontsize=9, color=GREEN)
        if m == 1:
            ax.plot([focus["A"], focus["B"]], [6.55, 6.55], ls="--", color=GRAY, lw=.9)
            ax.text(8.7, 6.55, "$[A_3,B_3]$", ha="right", va="center", fontsize=8, color=GRAY)
        ax.text(-1, np.mean(ys)+.15, f"$M_{m}$", ha="right", va="center", fontsize=12)
        for omega, y, scenario in zip((1, 2, 3), ys, SCENARIOS):
            ax.text(-.15, y+.16, rf"$\omega_{omega}$", ha="right", va="center", fontsize=8)
            for x in [x for x in JOBS if x["m"] == m]:
                S, p = scenario[x["j"]]
                processing(ax, x["j"], S, p, y, height=.46)
    ax.text(31.9, .10, r"$J_3$：$p_{3\omega}=2/5/15$，$C_{3\omega}=10/13/23$；分配、顺序及窗口共享", ha="right", fontsize=8, color="#5D6B74")


def parameter_panel(ax):
    ax.set_axis_off()
    lines = ["放大例：任务 $J_3$（确定值／$\\omega_2$）",
             "$p_3=5,\\quad S_3=8,\\quad C_3=13$",
             "$[A_3,B_3]=[9,24]$：可承诺范围",
             "$[e_3,d_3]=[12,20]$：选定窗口",
             "$4\\leq D_3\\leq 10,\\quad D_3=8$",
             "位置费：$\\rho(e_3-A_3)=1\\times3=3$",
             "额外宽度费：$\\lambda(D_3-4)=0.5\\times4=2$",
             "问题2/3窗口费 $K_3=5$，另加偏差费用"]
    for k, line in enumerate(lines):
        ax.text(0, .96-k*.115, line, transform=ax.transAxes, va="top", fontsize=12 if k == 0 else 10,
                color="#243642" if k < 5 else GREEN, fontweight="bold" if k == 0 else "normal")


def zoom_panel(ax, kind):
    base_axis(ax, (0, 4.8))
    ax.set_xticks([0, 12, 20, 32], ["0", "$e_3=12$", "$d_3=20$", "32"])
    ax.set_title("同一任务的三种位置示意（非同时加工）", loc="left", fontsize=11, pad=12)
    ax.axvspan(12, 20, color=GREEN, alpha=.08)
    for C, y, text in [(9, 3.35, "提前位置"), (13, 2.10, "当前位置"), (23, .85, "拖期位置")]:
        S = C-5
        ax.add_patch(Rectangle((S, y), 5, .42, facecolor=COLORS[2], edgecolor="#A5752C", lw=.7))
        if kind == "WORK":
            early_end, tardy_start = min(C, 12), max(S, 20)
            if early_end > S:
                ax.add_patch(Rectangle((S, y), early_end-S, .42, facecolor=BLUE, edgecolor="white", lw=.5))
            if C > tardy_start:
                ax.add_patch(Rectangle((tardy_start, y), C-tardy_start, .42, facecolor=RED, edgecolor="white", lw=.5))
            E, T, EW, TW = measures(C)
            caption = f"$EW={int(EW)},\\ TW={int(TW)}$"
        else:
            E, T, _, _ = measures(C)
            a, b, col = (C, 12, BLUE) if E else (20, C, RED)
            if E or T:
                ax.annotate("", (a, y-.22), (b, y-.22), arrowprops=dict(arrowstyle="|-|", color=col, lw=1))
            caption = f"$E={int(E)},\\ T={int(T)}$"
        ax.plot(C, y+.42, "o", color="#26343C", ms=4, zorder=5)
        ax.text(C+.3, y+.52, f"$C={C}$", fontsize=9)
        ax.text(0, y+.19, text, ha="left", va="center", fontsize=9)
        ax.text(31.7, y+.16, caption, ha="right", va="center", fontsize=9)
    ax.text(16, 4.5, "承诺窗口", color=GREEN, ha="center", fontsize=9)


def curve_panel(ax, kind):
    base_axis(ax, (-.5, 23 if kind == "WORK" else 38 if kind == "ET" else 8.5))
    ax.spines["left"].set_visible(True)
    ax.set_yticks([0, 5, 10, 15, 20] if kind == "WORK" else [0, 10, 20, 30] if kind == "ET" else [0, 2, 3, 5, 7])
    ax.tick_params(axis="y", labelsize=9)
    ax.set_ylabel("偏差费用", labelpad=4)
    ax.set_xlabel("完工时刻 $C$", loc="right", fontsize=9)
    ax.axvspan(12, 20, color=GREEN, alpha=.06)
    ax.set_title("固定所示窗口下的费用函数", loc="left", fontsize=11, pad=12)
    x = np.linspace(0, 32, 1601)
    if kind == "COUNT":
        segments = [(0, 7, 5), (7, 10, 3), (10, 12, 2), (12, 20, 0), (20, 22, 3), (22, 25, 5), (25, 32, 7)]
        for a, b, v in segments:
            ax.plot([a, b], [v, v], color=BLUE if b <= 12 else RED if a >= 20 else GREEN, lw=2)
        for t in [7, 10, 12, 20, 22, 25]:
            value = float(losses(kind, t, True))
            other = float(losses(kind, t-1e-6 if t <= 12 else t+1e-6, True))
            ax.plot(t, value, "o", color="#364852", ms=4)
            ax.plot(t, other, "o", mfc="white", mec="#364852", ms=4)
        ax.plot([0, 12], [2, 2], color="#78848D", lw=1.3, ls="--", label="普通加权计数")
        ax.plot([12, 20], [0, 0], color="#78848D", lw=1.3, ls="--")
        ax.plot([20, 32], [3, 3], color="#78848D", lw=1.3, ls="--")
        ax.plot([], [], color=BLUE, lw=2, label="多档事件罚金")
        ax.set_xticks([7, 10, 12, 20, 22, 25])
    else:
        vals = losses(kind, x, kind == "WORK")
        for mask, col in [(x <= (17 if kind == "WORK" else 12), BLUE),
                          ((x >= (17 if kind == "WORK" else 12)) & (x <= 20), GREEN), (x >= 20, RED)]:
            ax.plot(x[mask], vals[mask], color=col, lw=2.2)
        if kind == "WORK":
            ax.plot(x, losses(kind, x), color="#78848D", ls="--", lw=1.2, label="线性 work 费用")
            ax.plot([], [], color=BLUE, lw=2, label="多段 work 费用")
            ax.set_xticks([0, 12, 17, 20, 25, 32])
            ax.text(18.5, 3, "零费用", ha="center", fontsize=8, color=GREEN)
        else:
            ax.plot([], [], color=BLUE, lw=2, label="$2E+3T$")
            ax.set_xticks([0, 9, 12, 20, 23, 32])
    for C in [9, 13, 23]:
        val = float(losses(kind, C, kind != "ET"))
        ax.plot([C, C], [0, val], color="#A5ADB3", ls=":", lw=.8)
        ax.plot(C, val, "o", color=BLUE if C == 9 else RED if C == 23 else COLORS[2], ms=5)
        ax.annotate(f"({C}, {val:g})", (C, val), xytext=((-4, 7) if C == 23 and kind == "COUNT" else (18, 3) if C == 23 else (4, 7)),
                    textcoords="offset points", ha="right" if C == 23 and kind == "COUNT" else "left", fontsize=8)
    ax.legend(loc="upper center", bbox_to_anchor=(.5, -.25), ncol=2, fontsize=8, frameon=False)


def build(kind):
    fig = plt.figure(figsize=(18, 13), facecolor="white")
    fig.text(.045, .965, TITLES[kind], fontsize=22, fontweight="bold", color="#223744")
    fig.text(.045, .928, "任务参数 → 可承诺范围与选定窗口 → 完工／加工区间 → 偏差费用", fontsize=11, color="#5F6F79")
    fig.text(.045, .901, "彩色块：加工　 ●：完工　 绿线：任务窗口　 灰虚线：可承诺范围　 空白：允许等待", fontsize=10, color="#52616C")
    left_axes, right_axes = [], []
    rows = [(.645, "a", "问题 1：确定工时＋给定窗口", 1),
            (.375, "b", "问题 2：确定工时＋窗口决策", 2),
            (.105, "c", "问题 3：随机工时＋共同窗口决策", 3)]
    for bottom, panel, title, work in rows:
        ax = fig.add_axes([.07, bottom, .595, .21], label=f"scene-{work}")
        left_axes.append(ax)
        fig.text(.045, bottom+.229, f"{panel}  {title}", fontsize=13, fontweight="bold", color="#2B4555")
        if work == 3:
            stochastic_panel(ax)
        else:
            schedule_panel(ax, work)
    for bottom, name in [(.645, "parameters"), (.375, "zoom"), (.105, "cost")]:
        right_axes.append(fig.add_axes([.73, bottom, .24, .21], label=name))
    parameter_panel(right_axes[0])
    zoom_panel(right_axes[1], kind)
    curve_panel(right_axes[2], kind)
    note = {"ET": "提前／拖期按完工点与窗口端点之间的时间差计费。",
            "COUNT": "普通计数每次越窗收固定罚金；多档版本按偏差等级叠加事件罚金。",
            "WORK": "work 按实际加工区间在窗口外的长度计费；完工在窗口内仍可能存在 early work。"}[kind]
    fig.text(.045, .038, note+"  所有排程仅为可行示意，不表示最优解。", fontsize=10, color="#445963")
    fig.text(.045, .017, "问题 1 的外包与 setup 见数学描述；主图聚焦内部调度。随机计数／work 为问题层面的推广示意。", fontsize=9, color="#77838C")
    stem = OUT / f"{kind.lower()}-problem"
    fig.canvas.draw()
    require_matplotlib_panel_alignment(fig, json_out=f"{stem}.alignment.json",
                                     overlay_svg=f"{stem}.alignment.svg", strict=True,
                                     axes=left_axes+right_axes, panel_ids=["a", "b", "c", "d", "e", "f"],
                                     row_groups=[["a", "d"], ["b", "e"], ["c", "f"]],
                                     column_groups=[["a", "b", "c"], ["d", "e", "f"]])
    fig.savefig(f"{stem}.png", dpi=300)
    fig.savefig(f"{stem}.svg")
    svg_path = Path(f"{stem}.svg")
    # Matplotlib 的路径行尾空格不影响图形，归档前统一移除，避免 Git 空白检查噪声。
    svg_path.write_text("\n".join(line.rstrip() for line in svg_path.read_text(encoding="utf-8").splitlines())+"\n", encoding="utf-8")
    fig.savefig(f"{stem}.pdf")
    plt.close(fig)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    check_example()
    payload = {"purpose": "illustrative feasible schedules, not optimization results", "jobs": JOBS,
               "scenarios": SCENARIOS, "probabilities": [1/3]*3,
               "costs": {"rho": 1, "lambda": .5, "alpha": 2, "beta": 3},
               "zoom_completions": [9, 13, 23]}
    (OUT/"illustrative-data.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    for family in TITLES:
        build(family)
    print("3 figures exported; schedule, window and cost-identity checks passed.")
