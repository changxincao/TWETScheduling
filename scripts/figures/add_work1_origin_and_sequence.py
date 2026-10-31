"""明确 Work 1 的调度决策并居中加工条图示，保留已认可的排程和费用图。"""
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

# 参数框说明调度是待优化的决策，不再用一组固定排程示例替代该含义。
sample = src.crop(tuple(round(v*S) for v in (1080, 196, 1240, 281)))
ink = np.any(np.asarray(sample) < 240, axis=2)
ink_y, ink_x = np.where(ink)
sample = sample.crop((int(ink_x.min()), int(ink_y.min()),
                      int(ink_x.max())+1, int(ink_y.max())+1))
draw.rectangle(tuple(round(v*S) for v in allowed[-1]), fill="white")
right_edges = [
    line(["$p_j$", "：给定加工时长"], 906, 83),
    line(["$[e_j,d_j]$", "：给定交付窗口"], 906, 115),
    line(["调度决策：任务分配、机内加工顺序"], 906, 148),
    line(["与开始时刻 ", "$S_j$", "；完工时刻 ", "$C_j=S_j+p_j$"], 906, 182),
]
# 按完整图示的墨迹边界居中，包含 S3/C3 标签，不只居中粉色加工块。
panel_center = (864+1431)/2*S
sample_left = round(panel_center-sample.width/2)
sample_top = round(242*S-sample.height/2)
canvas.paste(sample, (sample_left, sample_top))
assert abs(sample_left+sample.width/2-panel_center) <= .5
assert sample_top > 191*S and sample_top+sample.height < 286*S

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
canvas.save(OUT / "work1-reviewed-4k.png", dpi=(300, 300))
canvas.resize((1448, 1086), Image.Resampling.LANCZOS).save(OUT / "work1-reviewed.png")

# 对生图对照版同步本轮参数框；其余模型输出保持原样，保留旧纯生图文件。
model_src = Image.open(OUT / "work1-model-redraw.png").convert("RGB")
model = model_src.copy()
x0, y0, x1, y1 = allowed[-1]
panel = canvas.crop((x0*S, y0*S, (x1+1)*S, (y1+1)*S))
model.paste(panel.resize((x1-x0+1, y1-y0+1), Image.Resampling.LANCZOS), (x0, y0))
model_mask = np.zeros((1086, 1448), dtype=bool)
model_mask[y0:y1+1, x0:x1+1] = True
model_changed = np.any(np.asarray(model) != np.asarray(model_src), axis=2)
model_unexpected = int(np.count_nonzero(model_changed & ~model_mask))
assert model_unexpected == 0, model_unexpected
model.save(OUT / "work1-model-reviewed.png")

(OUT / "reviewed-check.json").write_text(json.dumps({
    "source": str(SOURCE.relative_to(ROOT)),
    "export_size": list(canvas.size),
    "source_native_size": [1448, 1086],
    "origins_at_first_job_start": [[150, 145], [145, 253]],
    "decisions": ["任务分配", "机内加工顺序", "开始时刻"],
    "scheduling_explicitly_a_decision": True,
    "fixed_sequence_example_removed": True,
    "diagram_ink_center_x": (sample_left+sample.width/2)/S,
    "parameter_panel_center_x": panel_center/S,
    "parameter_line_right_edges": right_edges,
    "unexpected_changed_pixels": unexpected,
    "schedule_and_cost_panels_unchanged": unexpected == 0,
    "model_changed_pixels_outside_parameter_panel": model_unexpected,
    "model_reviewed_size": list(model.size),
    "pass": True,
}, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"PASS: scheduling is a decision; diagram centered; unchanged outside edits: {unexpected}/{model_unexpected} pixels.")
