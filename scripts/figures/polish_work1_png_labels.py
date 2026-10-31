"""在已授权的生图 PNG 校正版上精修标题与数学字形，不改变排程和曲线。"""
from pathlib import Path
import json

import matplotlib
import numpy as np
from matplotlib.font_manager import FontProperties
from matplotlib.mathtext import MathTextParser
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/figures/work1-layout-20261031/work1-layout-4k.png"
OUT = ROOT / "docs/figures/work1-polished-20261031"
OUT.mkdir(parents=True, exist_ok=True)
S = 3
src = Image.open(SOURCE).convert("RGB")
canvas = src.copy()
draw = ImageDraw.Draw(canvas)
BLACK, BLUE, RED = (0, 0, 0), (0, 25, 245), (245, 0, 0)
song = ImageFont.truetype("C:/Windows/Fonts/simsun.ttc", 26*S)
small_song = ImageFont.truetype("C:/Windows/Fonts/simsun.ttc", 18*S)
parser = MathTextParser("agg")
allowed = []


def rect_pixels(rect):
    return tuple(round(v*S) for v in rect)


def clear(rect):
    allowed.append(rect)
    draw.rectangle(rect_pixels(rect), fill="white")


def stroke(points, color, width):
    draw.line([(round(x*S), round(y*S)) for x, y in points], fill=color,
              width=round(width*S))


def heading(rect, sample_x, text, center):
    """从原图空白列恢复浅蓝底色，再写新标题，保留原有框线。"""
    allowed.append(rect)
    x0, y0, x1, y1 = rect
    strip = src.crop(rect_pixels((sample_x, y0, sample_x+1, y1)))
    strip = strip.resize((round((x1-x0)*S), round((y1-y0)*S)))
    canvas.paste(strip, (round(x0*S), round(y0*S)))
    draw.text((round(center*S), 311*S), text, font=song, fill=BLACK,
              anchor="mt", stroke_width=1)


def math_label(expression, x, baseline, color, size=22):
    """数学字体自行处理正体定界符、斜体变量与下标基线，只输出栅格墨迹。"""
    with matplotlib.rc_context({"mathtext.fontset": "stix"}):
        result = parser.parse(expression, dpi=72*S, prop=FontProperties(size=size))
    alpha = Image.fromarray(np.asarray(result.image))
    patch = Image.new("RGBA", alpha.size, color+(255,))
    patch.putalpha(alpha)
    top = round(baseline*S-(result.height-result.depth))
    canvas.paste(patch, (round(x*S), top), patch)


heading((24, 306, 846, 345), 25, "与完工时间偏差有关的成本", 434.5)
heading((877, 306, 1419, 345), 870, "与窗口外加工量有关的成本", 1147.5)

# 删除原句，并把不含该句的既有图例整体居中。
legend = src.crop(rect_pixels((15, 1027, 926, 1067)))
clear((0, 1020, 1448, 1086))
canvas.paste(legend, (round((1448-911)*S/2), 1027*S))

for rect in [(97, 665, 236, 702), (240, 710, 322, 745),
             (628, 708, 706, 745), (695, 659, 823, 701),
             (915, 772, 974, 806), (1360, 737, 1422, 773)]:
    clear(rect)

# 清理文字时覆盖到的线段按原坐标复原，曲线的形状、颜色和节点不变。
convex = [(80, 682), (167, 736), (407, 774), (607, 774), (718, 741), (839, 669)]
stroke(convex[:3], BLUE, 1.8)
stroke(convex[2:4], BLACK, 2)
stroke(convex[3:], RED, 1.8)
stroke([(904, 805), (1040, 805), (1140, 900)], BLUE, 1.8)
stroke([(1140, 900), (1240, 900)], BLACK, 2)
stroke([(1240, 900), (1340, 772), (1415, 772)], RED, 1.8)
for x in [167, 407, 607, 718]:
    stroke([(x, 774), (x, 780)], BLACK, 1)
for x in [1040, 1140, 1240, 1340]:
    stroke([(x, 900), (x, 906)], BLACK, 1)

for x, y, expression, color in [
        (101, 669, r"$-(\alpha+\gamma)$", BLUE),
        (244, 715, r"$-\alpha$", BLUE),
        (633, 715, r"$\beta$", RED),
        (700, 666, r"$\beta+\delta$", RED)]:
    draw.text((x*S, y*S), "斜率", font=small_song, fill=color, anchor="lt")
    math_label(expression, x+40, y+19, color)
math_label(r"$\alpha p_3$", 922, 797, BLUE)
math_label(r"$\beta p_3$", 1367, 762, RED)

# 实际像素校验：除本轮允许的字形区和页脚外，所有图形和公式原封不动。
a, b = np.asarray(canvas), np.asarray(src)
mask = np.zeros(a.shape[:2], dtype=bool)
for rect in allowed:
    x0, y0, x1, y1 = rect_pixels(rect)
    mask[max(0, y0):min(a.shape[0], y1+1), max(0, x0):min(a.shape[1], x1+1)] = True
diff = np.any(a != b, axis=2)
unexpected = int(np.count_nonzero(diff & ~mask))
assert unexpected == 0, unexpected
geometry = json.loads((SOURCE.parent/"layout-check.json").read_text(encoding="utf-8"))
schedule = geometry["main_schedule"]
assert schedule[3][2] < schedule[3][4] and schedule[5][2] > schedule[5][5]
assert len(set(geometry["raster_pink_widths"])) == 1
assert geometry["partial_late_job"][0] < geometry["shared_completion_window"][1] < geometry["partial_late_job"][1]
assert tuple(a[774*S, 500*S]) == BLACK
assert tuple(a[900*S, 1200*S]) == BLACK
canvas.save(OUT/"work1-polished-4k.png", dpi=(300, 300))
canvas.resize((1448, 1086), Image.Resampling.LANCZOS).save(OUT/"work1-polished.png")
(OUT/"review-check.json").write_text(json.dumps({
    "source": str(SOURCE.relative_to(ROOT)), "export_size": list(canvas.size),
    "left_title": "与完工时间偏差有关的成本", "right_title": "与窗口外加工量有关的成本",
    "footer_sentence_removed": True, "legend_centered": True,
    "math_fontset": "stix", "upright_delimiters": True, "proper_work_subscripts": True,
    "unchanged_pixels_outside_label_edits": unexpected == 0,
    "unexpected_changed_pixel_count": unexpected,
    "inherited_geometry_checks": "../work1-layout-20261031/layout-check.json",
    "reviewed": ["job and window indices", "equal J3 processing lengths", "window projections", "main early and tardy cases", "partial tardiness", "convex/step/five-piece work curves"],
    "pass": True}, ensure_ascii=False, indent=2), encoding="utf-8")
print("Labels polished: upright mathematical delimiters, work subscripts, removed footer note; geometry unchanged PASS.")
