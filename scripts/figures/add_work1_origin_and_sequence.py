"""给已认可的 Work 1 PNG 补时间原点和排序决策，保留其余图元。"""
from pathlib import Path
import json

import matplotlib
import numpy as np
from matplotlib.font_manager import FontProperties
from matplotlib.mathtext import MathTextParser
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/figures/work1-polished-20261031/work1-polished-4k.png"
OUT = ROOT / "docs/figures/work1-optimized-20261031"
S = 3
src = Image.open(SOURCE).convert("RGB")
canvas = src.copy()
draw = ImageDraw.Draw(canvas)
parser = MathTextParser("agg")
allowed = [(137, 142, 164, 176), (132, 250, 159, 284), (875, 55, 1421, 289)]


def math_ink(expression, size):
    """沿用 STIX 数学字形，保持变量、下标与正体运算符的区别。"""
    with matplotlib.rc_context({"mathtext.fontset": "stix"}):
        result = parser.parse(expression, dpi=72*S, prop=FontProperties(size=size))
    alpha = Image.fromarray(np.asarray(result.image))
    patch = Image.new("RGBA", alpha.size, "black")
    patch.putalpha(alpha)
    return result, patch


def line(parts, x, baseline, size=22):
    """中文与数学表达式共用基线，返回实际右边界供版面检查。"""
    font = ImageFont.truetype("C:/Windows/Fonts/simsun.ttc", size*S)
    for part in parts:
        if part.startswith("$"):
            result, patch = math_ink(part, size)
            top = round(baseline*S-(result.height-result.depth))
            canvas.paste(patch, (round(x*S), top), patch)
            x += result.width/S + 2
        else:
            draw.text((round(x*S), round(baseline*S)), part, font=font,
                      fill="black", anchor="ls")
            x += draw.textlength(part, font=font)/S
    assert x < 1418, x
    return round(x, 2)


# 0 对齐首个任务的左端；轴线在左边的延伸只承担视觉留白。
zero_boxes = []
for x, y in [(150, 145), (145, 253)]:
    draw.line([(x*S, y*S), (x*S, (y+6)*S)], fill="black", width=S)
    result, patch = math_ink("$0$", 21)
    left, top = round(x*S-result.width/2), (y+9)*S
    canvas.paste(patch, (left, top), patch)
    zero_boxes.append([left, top, left+patch.width, top+patch.height])

# 参数框只重排内部，保留原有浅蓝标题、边界和 S3/C3 小图。
sample = src.crop(tuple(round(v*S) for v in (1080, 196, 1240, 281)))
draw.rectangle(tuple(round(v*S) for v in allowed[-1]), fill="white")
right_edges = [
    line(["$p_j$", "：给定加工时长"], 906, 83),
    line(["$[e_j,d_j]$", "：给定交付窗口"], 906, 115),
    line(["决策：任务分配、机内加工顺序、"], 906, 148),
    line(["开始时刻 ", "$S_j$", "；完工时刻 ", "$C_j=S_j+p_j$"], 906, 182),
    line(["排程示例"], 906, 209, size=17),
    line([r"$M_1:\ J_1\to J_3\to J_5$"], 922, 240, size=21),
    line([r"$M_2:\ J_2\to J_4\to J_6$"], 922, 273, size=21),
]
canvas.paste(sample, (1244*S, 201*S))

before, after = np.asarray(src), np.asarray(canvas)
mask = np.zeros(before.shape[:2], dtype=bool)
for x0, y0, x1, y1 in allowed:
    mask[y0*S:y1*S+1, x0*S:x1*S+1] = True
changed = np.any(before != after, axis=2)
unexpected = int(np.count_nonzero(changed & ~mask))
assert unexpected == 0, unexpected
for x0, y0, x1, y1 in zero_boxes:
    assert np.count_nonzero(changed[y0:y1, x0:x1]) > 0

geometry = json.loads((ROOT / "docs/figures/work1-layout-20261031/layout-check.json")
                      .read_text(encoding="utf-8"))
schedule = geometry["main_schedule"]
assert [job[0] for job in schedule[:3]] == [1, 3, 5]
assert [job[0] for job in schedule[3:]] == [2, 4, 6]
assert schedule[3][2] < schedule[3][4] and schedule[5][2] > schedule[5][5]
assert len(set(geometry["raster_pink_widths"])) == 1

OUT.mkdir(parents=True, exist_ok=True)
canvas.save(OUT / "work1-optimized-4k.png", dpi=(300, 300))
canvas.resize((1448, 1086), Image.Resampling.LANCZOS).save(OUT / "work1-optimized.png")
(OUT / "review-check.json").write_text(json.dumps({
    "source": str(SOURCE.relative_to(ROOT)),
    "export_size": list(canvas.size),
    "source_native_size": [1448, 1086],
    "origins_at_first_job_start": [[150, 145], [145, 253]],
    "decisions": ["任务分配", "机内加工顺序", "开始时刻"],
    "example_sequences": {"M1": [1, 3, 5], "M2": [2, 4, 6]},
    "parameter_line_right_edges": right_edges,
    "unexpected_changed_pixels": unexpected,
    "schedule_and_cost_panels_unchanged": unexpected == 0,
    "pass": True,
}, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"PASS: two origins, scheduling decisions and sequences; unchanged outside edits: {unexpected} pixels.")
