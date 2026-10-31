"""绘制 Work 1 的单张合并问题图：共用排程、共用放大例和并列损失函数。"""
from pathlib import Path
import json
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.patches import Rectangle, FancyBboxPatch
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/figures/work1-merged-20261031"
sys.path.insert(0, "C:/Users/Changxin/.agents/skills/nature-figure/scripts")
from audit_panel_alignment import require_matplotlib_panel_alignment

font_manager.fontManager.addfont("C:/Windows/Fonts/msyh.ttc")
plt.rcParams.update({"font.family": ["Microsoft YaHei", "Arial", "DejaVu Sans"],
                     "font.size": 10, "mathtext.fontset": "dejavusans", "axes.unicode_minus": False,
                     "svg.fonttype": "none", "pdf.fonttype": 42})
BLUE, RED, GREEN = "#447EAC", "#C56663", "#448965"
INK, MUTED = "#243746", "#74838C"
COLORS = ["#4E9F96", "#648BBC", "#DAA341", "#927AB4", "#C97970", "#8D9E61"]
JOBS = [
    dict(j=1, m=1, S=0, p=3, e=4, d=8, setup=0),
    dict(j=2, m=2, S=1, p=6, e=5, d=9, setup=1),
    dict(j=3, m=1, S=4, p=5, e=12, d=20, setup=1),
    dict(j=4, m=2, S=10, p=6, e=13, d=17, setup=1),
    dict(j=5, m=1, S=21, p=6, e=21, d=25, setup=1),
    dict(j=6, m=2, S=19, p=5, e=20, d=23, setup=1),
]


def g(x):
    """示例早侧分段费用：加工量版本使用同一个函数并截断输入。"""
    return x+np.maximum(x-2, 0)+np.maximum(x-4, 0)


def h(x):
    return 1.5*x+np.maximum(x-2, 0)+np.maximum(x-4, 0)


def deviations(C):
    """放大任务固定 p=5、e=12、d=20。"""
    C = np.asarray(C)
    return np.maximum(12-C, 0), np.maximum(C-20, 0), np.clip(17-C, 0, 5), np.clip(C-20, 0, 5)


def loss(family, C):
    E, T, EW, TW = deviations(C)
    if family == 0:
        return g(E)+h(T)
    if family == 1:
        return sum(a*(E > u) for a, u in [(2, 0), (1, 2), (2, 4)])+sum(
            b*(T > v) for b, v in [(3, 0), (2, 2), (2, 4)])
    return g(EW)+h(TW)


def time_axis(ax, ymax):
    ax.set(xlim=(0, 32), ylim=(0, ymax))
    ax.spines[["top", "left", "right"]].set_visible(False)
    ax.spines["bottom"].set_color("#A6B1B8")
    ax.set_yticks([])
    ax.set_xticks([0, 8, 16, 24, 32])
    ax.tick_params(axis="x", length=3, labelsize=9, colors=MUTED)
    ax.set_xlabel("时间 $t$", loc="right", fontsize=9, labelpad=1)


def main_schedule(ax):
    """每台机器只用一层加工条和一层给定窗口，共用一条时间轴。"""
    time_axis(ax, 5.3)
    for m, y in [(1, 3.05), (2, .65)]:
        ax.text(-.6, y+.22, f"$M_{m}$", ha="right", va="center", fontsize=13, color=INK)
        for x in sorted((x for x in JOBS if x["m"] == m), key=lambda x: x["S"]):
            j, S, p, e, d, s = (x[k] for k in ["j", "S", "p", "e", "d", "setup"])
            if s:
                ax.add_patch(Rectangle((S-s, y), s, .44, facecolor="#DDE3E7", edgecolor="white", lw=.8))
            ax.add_patch(Rectangle((S, y), p, .44, facecolor=COLORS[j-1], edgecolor="white", lw=.8))
            ax.text(S+p/2, y+.22, f"$J_{j}\\ (p_{j})$", color="white", ha="center", va="center", fontsize=10)
            ax.plot(S+p, y+.44, "o", color=INK, ms=4)
            ax.text(S+p+.1, y+.59, f"$C_{j}$", fontsize=9, color=INK)
            ax.plot([e, d], [y+1.23]*2, color=GREEN, lw=2.4)
            ax.plot([e, e, np.nan, d, d], [y+1.13, y+1.33, np.nan, y+1.13, y+1.33], color=GREEN, lw=1)
            ax.text((e+d)/2, y+1.45, f"$[e_{j},d_{j}]$", ha="center", color=GREEN, fontsize=9)
            if j == 3:
                ax.text(S-.1, y-.34, "$S_3$", ha="right", fontsize=9, color=INK)
        if m == 1:
            ax.text(15, y+.18, "允许等待", color=MUTED, ha="center", fontsize=9)


def shared_zoom(ax):
    """两个执行位置共用一个窗口；括号与块内着色同时服务下方三个函数。"""
    time_axis(ax, 3.2)
    ax.set_xticks([0, 12, 20, 32], ["0", "$e_3=12$", "$d_3=20$", "32"])
    ax.axvspan(12, 20, facecolor=GREEN, alpha=.075, edgecolor="none", zorder=0)
    ax.plot([12, 20], [2.82, 2.82], color=GREEN, lw=2.2)
    ax.text(16, 3.00, "$[e_3,d_3]$：给定窗口", ha="center", color=GREEN, fontsize=10)
    for C, y, name in [(9, 1.83, "提前位置"), (23, .75, "拖期位置")]:
        S = C-5
        E, T, EW, TW = deviations(C)
        ax.text(0, y+.18, name, fontsize=10, color=BLUE if E else RED, va="center")
        ax.add_patch(Rectangle((S, y), 5, .40, facecolor=COLORS[2], edgecolor="none"))
        if EW:
            ax.add_patch(Rectangle((S, y), float(EW), .40, facecolor=BLUE, edgecolor="none"))
        if TW:
            ax.add_patch(Rectangle((C-float(TW), y), float(TW), .40, facecolor=RED, edgecolor="none"))
        ax.plot(C, y+.40, "o", color=INK, ms=4)
        ax.text(C+.25, y+.50, f"$C_3={C}$", fontsize=9, color=INK)
        ax.text(S, y-.25, f"$S_3={S}$", ha="center", fontsize=9, color=INK)
        ax.text(S+2.5, y+.20, "$p_3=5$", color="white", ha="center", va="center", fontsize=9)
        a, b = (C, 12) if E else (20, C)
        ax.annotate("", (a, y-.22), (b, y-.22), arrowprops=dict(arrowstyle="|-|", lw=1, color=BLUE if E else RED))
        ax.text((a+b)/2, y-.55, "$E_3=3$" if E else "$T_3=3$", color=BLUE if E else RED, ha="center", fontsize=9)
        ax.text(31.8, y+.20, "$EW_3=5$" if E else "$TW_3=3$", color=BLUE if E else RED, ha="right", va="center", fontsize=10)


def curve(ax, family):
    """横轴统一为完工时刻；第三式保留加工量截断导致的额外折点。"""
    ax.set(xlim=(0, 32), ylim=(-.4, [39, 8, 13][family]))
    ax.spines[["top", "right"]].set_visible(False)
    ax.spines[["left", "bottom"]].set_color("#A6B1B8")
    ax.axvspan(12, 20, facecolor=GREEN, alpha=.055, edgecolor="none")
    ax.set_xticks([0, 12, 20, 32], ["0", "$e_j$", "$d_j$", "32"])
    ax.set_yticks([0])
    ax.tick_params(labelsize=9, length=3, colors=MUTED)
    ax.set_xlabel("完工时刻 $C$", loc="right", fontsize=9, labelpad=2)
    ax.set_ylabel("损失", fontsize=10, labelpad=3)
    if family == 1:
        for a, b, value in [(0, 8, 5), (8, 10, 3), (10, 12, 2), (12, 20, 0), (20, 22, 3), (22, 24, 5), (24, 32, 7)]:
            ax.plot([a, b], [value, value], lw=2.1, color=BLUE if b <= 12 else RED if a >= 20 else GREEN)
        for t in [8, 10, 12, 20, 22, 24]:
            v = float(loss(family, t))
            w = float(loss(family, t-1e-5 if t <= 12 else t+1e-5))
            ax.plot(t, v, "o", color=INK, ms=3.5)
            ax.plot(t, w, "o", mfc="white", mec=INK, ms=3.5)
    else:
        x = np.unique(np.r_[np.linspace(0, 32, 1601), 12, 13, 15, 17, 20, 22, 24, 25])
        y = loss(family, x)
        z = 17 if family == 2 else 12
        for mask, col in [(x <= z, BLUE), ((x >= z) & (x <= 20), GREEN), (x >= 20, RED)]:
            ax.plot(x[mask], y[mask], color=col, lw=2.1)
        if family == 0:
            for t in [8, 10, 22, 24]:
                ax.plot(t, float(loss(family, t)), "o", color=BLUE if t < 12 else RED, ms=3)
        if family == 2:
            ax.set_xticks([0, 12, 17, 20, 25, 32], ["0", "$e_j$", "$e_j+p_j$", "$d_j$", "$d_j+p_j$", "32"])
            ax.tick_params(axis="x", labelsize=8.5)
            ax.get_xticklabels()[2].set_ha("right")
            ax.get_xticklabels()[3].set_ha("left")
    for C, col in [(9, BLUE), (23, RED)]:
        v = float(loss(family, C))
        ax.plot([C, C], [0, v], color=col, ls=":", lw=.8, alpha=.55)
        ax.plot(C, v, "o", color=col, ms=4.5)
        ax.annotate(f"({C}, {v:g})", (C, v), xytext=((-4, 8) if C == 23 else (3, 7)),
                    textcoords="offset points", ha="right" if C == 23 else "left", fontsize=8.5)


def legend(ax):
    """以小时间轴、色块和端点构成图解式图例，避免纯参数表。"""
    ax.set(xlim=(0, 1), ylim=(0, 1)); ax.set_axis_off()
    ax.text(.06, .965, "符号与图例", color=INK, fontsize=14, fontweight="bold")
    ax.plot([.10, .75], [.90, .90], color=GREEN, lw=2.4)
    ax.plot([.10, .75], [.90, .90], "|", color=GREEN, ms=10)
    ax.text(.10, .865, "$e_j$", color=GREEN, ha="center", fontsize=10)
    ax.text(.75, .865, "$d_j$", color=GREEN, ha="center", fontsize=10)
    ax.text(.06, .81, "给定的任务窗口", color=INK, fontsize=10)
    ax.add_patch(Rectangle((.10, .70), .53, .055, facecolor=COLORS[2], edgecolor="none"))
    ax.plot(.63, .755, "o", color=INK, ms=4)
    ax.text(.10, .665, "$S_j$", ha="center", fontsize=10)
    ax.text(.63, .665, "$C_j$", ha="center", fontsize=10)
    ax.text(.365, .725, "$J_j\\ (p_j)$", ha="center", va="center", color="white", fontsize=10)
    ax.text(.06, .615, "$C_j=S_j+p_j$", color=INK, fontsize=10)
    ax.text(.06, .575, "开始时刻、工时与完工时刻", color=MUTED, fontsize=9.5)
    for y, color, text in [(.50, BLUE, "$E_j=(e_j-C_j)^+$"), (.43, RED, "$T_j=(C_j-d_j)^+$")]:
        ax.annotate("", (.06, y), (.22, y), arrowprops=dict(arrowstyle="|-|", color=color, lw=1))
        ax.text(.28, y, text, va="center", fontsize=10, color=color)
    ax.add_patch(Rectangle((.06, .335), .13, .035, facecolor=BLUE, edgecolor="none"))
    ax.text(.25, .352, "$EW_j$：窗口前的加工量", color=BLUE, fontsize=10, va="center")
    ax.add_patch(Rectangle((.06, .265), .13, .035, facecolor=RED, edgecolor="none"))
    ax.text(.25, .282, "$TW_j$：窗口后的加工量", color=RED, fontsize=10, va="center")
    ax.add_patch(Rectangle((.06, .195), .13, .035, facecolor="#DDE3E7", edgecolor="none"))
    ax.text(.25, .212, "$s_{ij}$：序列相关换型时间", color=INK, fontsize=10, va="center")
    ax.text(.25, .17, "$g_{ij}$：相应换型金额", color=INK, fontsize=9.5)
    ax.text(.06, .13, r"$(x)^+=\max\{x,0\}$", color=MUTED, fontsize=10)
    ax.text(.06, .06, r"外包：$O$，支付 $G(\sum_{j\in O}q_j)$", color=INK, fontsize=9.5)
    ax.text(.06, .006, "$q_j$：任务外包基准报价", color=INK, fontsize=9.5)


def build():
    fig = plt.figure(figsize=(16, 10.8), facecolor="white")
    fig.text(.045, .952, "Work 1｜给定窗口的确定性并行机调度", fontsize=21, fontweight="bold", color=INK)
    fig.text(.045, .912, "同机加工不重叠 · 任务不可抢占 · 允许等待；下方分别选择一种任务损失函数", fontsize=11, color=MUTED)
    # 共用场景与放大例各占一块，下方三式共用一个区块，不重复上层信息。
    for bottom, height, tint in [(.064, .829, "#FBFCFD")]:
        fig.add_artist(FancyBboxPatch((.035, bottom), .715, height,
                                    boxstyle="round,pad=0.004,rounding_size=0.006", transform=fig.transFigure,
                                    facecolor=tint, edgecolor="#DEE7EB", lw=.7, zorder=-2))
    fig.text(.045, .870, "两台同质并行机上的内部任务", fontsize=11, fontweight="bold", color=INK)
    main = fig.add_axes([.07, .655, .665, .215], label="schedule")
    main_schedule(main)
    fig.text(.045, .606, r"放大 $J_3$：$p_3=5$，$[e_3,d_3]=[12,20]$；两行仅比较不同执行位置", fontsize=12, fontweight="bold", color=INK)
    zoom = fig.add_axes([.07, .405, .665, .165], label="shared-zoom")
    shared_zoom(zoom)
    fig.text(.045, .351, "同一任务的三种可选损失表达", fontsize=12, fontweight="bold", color=INK)
    formulas = [r"$\varphi_j(C)=g_j(E_j)+h_j(T_j)$",
                r"$\varphi_j(C)=\sum_r a_{jr}\mathbf{1}\{E_j>u_{jr}\}$"+"\n"+r"$\qquad+\sum_r b_{jr}\mathbf{1}\{T_j>v_{jr}\}$",
                r"$\varphi_j(C)=g_j(EW_j)+h_j(TW_j)$"]
    fig.add_artist(FancyBboxPatch((.060, .268), .680, .072,
                                 boxstyle="round,pad=0.003,rounding_size=0.006", transform=fig.transFigure,
                                 facecolor="#EDF4F9", edgecolor="none", zorder=-1))
    axes = []
    for i, left in enumerate([.07, .30, .53]):
        fig.text(left, .333, formulas[i], fontsize=10.5, va="top", color=INK, linespacing=1.3)
        ax = fig.add_axes([left, .10, .205, .17], label=f"loss-{i}")
        curve(ax, i)
        axes.append(ax)
    fig.text(.045, .041, "$g_j,h_j$ 为非减分段线性函数；$a_{jr},b_{jr}\\geq0$ 为分档费用增量；$u_{jr},v_{jr}$ 为偏差阈值。排程仅作示意。", fontsize=10, color=MUTED)
    fig.add_artist(FancyBboxPatch((.775, .064), .207, .829, boxstyle="round,pad=0.008,rounding_size=0.009",
                                 transform=fig.transFigure, facecolor="#F7F9FA", edgecolor="#DCE3E7", lw=.8, zorder=-1))
    legend(fig.add_axes([.78, .09, .197, .78], label="illustrated-legend"))
    stem = OUT / "work1-merged"
    fig.canvas.draw()
    require_matplotlib_panel_alignment(fig, json_out=f"{stem}.alignment.json", overlay_svg=f"{stem}.alignment.svg",
                                     strict=True, axes=axes, panel_ids=["loss-1", "loss-2", "loss-3"],
                                     row_groups=[["loss-1", "loss-2", "loss-3"]], column_groups=[])
    fig.savefig(f"{stem}.png", dpi=300)
    fig.savefig(f"{stem}.pdf")
    fig.savefig(f"{stem}.svg")
    svg = Path(f"{stem}.svg")
    svg.write_text("\n".join(x.rstrip() for x in svg.read_text(encoding="utf-8").splitlines())+"\n", encoding="utf-8")
    plt.close(fig)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for m in [1, 2]:
        end = 0
        for job in sorted((x for x in JOBS if x["m"] == m), key=lambda x: x["S"]):
            assert job["S"] >= end+job["setup"]
            end = job["S"]+job["p"]
    assert [float(loss(i, 9)) for i in range(3)] == [4, 3, 9]
    assert [float(loss(i, 23)) for i in range(3)] == [5.5, 5, 5.5]
    assert float(loss(0, 13)) == 0 and float(loss(2, 13)) == 6
    assert all(float(loss(i, 20)) == 0 for i in range(3))
    (OUT/"illustrative-data.json").write_text(json.dumps({"jobs": JOBS, "zoom_completions": [9, 23],
        "window": [12, 20], "processing_time": 5,
        "g": "x+(x-2)^++(x-4)^+", "h": "1.5x+(x-2)^++(x-4)^+",
        "early_steps": [[0, 2], [2, 1], [4, 2]], "late_steps": [[0, 3], [2, 2], [4, 2]],
        "purpose": "illustrative feasible schedules, not optimization results"}, ensure_ascii=False, indent=2), encoding="utf-8")
    build()
    print("Work 1 merged figure exported; schedule and loss checks passed.")
