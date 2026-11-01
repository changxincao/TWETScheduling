"""局部统一 Work 2 的加工条、work 曲线端点及中文延迟用词。"""

import json
from hashlib import sha256
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
SOURCE = HERE / "work2-a-original-label-restored.png"
OUTPUT = HERE / "work2-a-geometry-polished.png"
AUDIT = HERE / "geometry-polished-check.json"
BLUE = (20, 55, 245)
RED = (255, 0, 0)
BLACK = (15, 15, 15)
WHITE = (255, 255, 255)
BAR_WIDTH = 110

# 完工点与窗口保持原位；同一 p3 以统一的端点间距表示。
BARS = [
    dict(name="main", old_s=425, c=535, top=116, base=144),
    dict(name="parameter", old_s=1070, c=1188, top=247, base=275),
    dict(name="ET_early", old_s=166, c=254, top=690, base=719),
    dict(name="ET_late", old_s=502, c=647, top=766, base=793),
    dict(name="EW", old_s=899, c=998, top=700, base=725),
    dict(name="TW", old_s=1107, c=1220, top=770, base=796),
]


def restore_background(image, source, box, window=None):
    """清理旧加工条时，用同一行的无字背景恢复白底或窗口底色。"""
    x0, y0, x1, y1 = box
    for y in range(y0, y1):
        for x in range(x0, x1):
            color = source.getpixel((window[2], y)) if window and window[0] <= x <= window[1] else WHITE
            image.putpixel((x, y), color)


def draw_bar(image, source, bar, math_ink):
    """保留 C3、窗口和高度，仅统一加工块长度，并移动 S3。"""
    start = bar["c"] - BAR_WIDTH
    old_s, end, top, base = (bar[k] for k in ("old_s", "c", "top", "base"))
    left = min(start, old_s) - 4
    window = None
    if bar["name"] == "ET_late":
        window = (381, 570, 480)
    elif bar["name"] in ("EW", "TW"):
        window = (951, 1158, 1048)
    restore_background(image, source, (left, top - 3, end + 4, base + 5), window)

    # 用原加工块的纵向色阶填色，数学文字沿用原图字形而不拉伸。
    draw = ImageDraw.Draw(image)
    if bar["name"] != "parameter":
        draw.line((left, base, end + 3, base), fill=BLACK, width=2)
    for y in range(top + 1, base):
        donor_y = 118 + round((y - top - 1) * 23 / max(1, base - top - 2))
        draw.line((start + 1, y, end - 1, y), fill=source.getpixel((432, donor_y)))
    draw.rectangle((start, top, end, base), outline=BLACK, width=2)
    image.paste(BLACK, (round((start + end - math_ink.width) / 2), top + (base - top - math_ink.height) // 2), math_ink)
    draw.ellipse((end - 3, top - 3, end + 3, top + 3), fill=BLACK)

    if bar["name"] == "main":
        return
    label_boxes = {
        "parameter": (1058, 280, 1085, 301),
        "ET_early": (154, 726, 179, 747),
        "ET_late": (490, 799, 515, 820),
        "EW": (886, 730, 912, 751),
        "TW": (1094, 800, 1121, 821),
    }
    box = label_boxes[bar["name"]]
    label = source.crop(box)
    shift = start - old_s
    draw.rectangle((min(box[0], box[0] + shift), box[1], max(box[2], box[2] + shift) - 1, box[3] - 1), fill=WHITE)
    image.paste(label, (box[0] + shift, box[1]))
    # 移除旧 S3 点并恢复基线；参数图没有时间轴。
    if bar["name"] == "parameter":
        draw.rectangle((old_s - 4, base - 1, start - 1, base + 4), fill=WHITE)
        draw.rectangle((start, top, end, base), outline=BLACK, width=2)
    else:
        draw.rectangle((min(old_s, start) - 4, base, max(old_s, start) + 4, base + 4), fill=WHITE)
        draw.line((left, base, end + 3, base), fill=BLACK, width=2)
    draw.ellipse((start - 3, base - 3, start + 3, base + 3), fill=BLACK)


def smooth_line(image, points, color):
    """局部超采样，只平滑新线条，避免整图缩放损失。"""
    xs, ys = zip(*points)
    box = (min(xs) - 3, min(ys) - 3, max(xs) + 4, max(ys) + 4)
    layer = Image.new("RGBA", ((box[2] - box[0]) * 4, (box[3] - box[1]) * 4))
    ImageDraw.Draw(layer).line([((x - box[0]) * 4, (y - box[1]) * 4) for x, y in points], fill=color + (255,), width=8, joint="curve")
    layer = layer.resize((box[2] - box[0], box[3] - box[1]), Image.Resampling.LANCZOS)
    image.paste(layer, box[:2], layer)


def main():
    source = Image.open(SOURCE).convert("RGB")
    image = source.copy()
    # 从原始 J3(p3) 中提取墨迹，保持字形且不复制粉色文字底块。
    token = np.asarray(source.crop((456, 120, 514, 142)), dtype=float)
    alpha = np.clip((248 - token.max(axis=2)) / 215, 0, 1)
    alpha[alpha < 0.08] = 0
    math_ink = Image.fromarray(np.round(alpha * 255).astype("uint8"))
    for bar in BARS:
        draw_bar(image, source, bar, math_ink)

    # E3/T3 的完工端点未变，保留原有偏差区间；EW3 随新 S3 左移。
    image.paste(source.crop((254, 718, 384, 733)), (254, 718))
    image.paste(source.crop((569, 793, 651, 805)), (569, 793))
    draw = ImageDraw.Draw(image)
    draw.line((888, 700, 951, 700), fill=BLUE, width=5)
    for x in (888, 951):
        draw.line((x, 697, x, 704), fill=BLUE, width=3)

    # 中文标签采用相同字体与字号，保留已有英文数学名称。
    chinese = ImageFont.truetype(r"C:\Windows\Fonts\simhei.ttf", 22)
    for box, label, color in [
        ((25, 694, 122, 723), "提前完工", (0, 0, 255)),
        ((25, 768, 122, 797), "延迟完工", RED),
    ]:
        draw.rectangle(box, fill=WHITE)
        draw.text((box[0] + 2, box[1]), label, font=chinese, fill=color)
    # 图例只替换中文文字，不移动旁边的红色线段。
    draw.rectangle((1159, 1109, 1292, 1140), fill=WHITE)
    legend_font = ImageFont.truetype(r"C:\Windows\Fonts\simsun.ttc", 21)
    draw.text((1165, 1113), "延迟成本", font=legend_font, fill=BLACK)

    # 清除旧曲线的彩色墨迹，窗口背景和轴线不重绘。
    a = np.asarray(image).copy()
    for box, is_blue in [((950, 981, 1045, 1047), True), ((1170, 962, 1342, 1047), False)]:
        x0, y0, x1, y1 = box
        part = a[y0:y1, x0:x1]
        r, g, b = (part[:, :, k].astype(int) for k in range(3))
        mask = (b > r + 5) & (b > g + 5) if is_blue else (r > g + 5) & (r > b + 5)
        for yy, xx in np.argwhere(mask):
            part[yy, xx] = source.getpixel((1080 if is_blue else 1345, y0 + int(yy)))
    image = Image.fromarray(a)
    # 两个 p3 横向区间统一为 91 px，红色封顶与既有刻度 x=1262 对齐。
    smooth_line(image, [(951, 985), (1042, 1046)], (0, 0, 255))
    smooth_line(image, [(1171, 1046), (1262, 966), (1340, 966)], RED)
    # 蓝线的零点刻度仅右移 2 px，原数学标签仍可保持居中。
    draw = ImageDraw.Draw(image)
    draw.rectangle((1038, 1047, 1044, 1054), fill=WHITE)
    draw.line((1042, 1047, 1042, 1054), fill=BLACK, width=1)

    # 验证修改仅发生在获准区域，e2/d2 与三类费用公式保持像素一致。
    regions = [
        (420, 111, 541, 151), (1054, 242, 1194, 304),
        (24, 684, 286, 750), (24, 766, 124, 800), (488, 760, 653, 823),
        (871, 695, 1005, 754), (1090, 766, 1226, 824),
        (1158, 1107, 1295, 1143), (947, 959, 1348, 1057),
    ]
    before, after = np.asarray(source), np.asarray(image)
    changed = np.any(before != after, axis=2)
    allowed = np.zeros(changed.shape, dtype=bool)
    for x0, y0, x1, y1 in regions:
        allowed[y0:y1, x0:x1] = True
    assert not np.any(changed & ~allowed), "存在编辑框外改动"
    assert np.array_equal(before[208:237, 218:325], after[208:237, 218:325]), "e2/d2 被改动"
    for box in [(895, 428, 1343, 466), (230, 855, 702, 908), (236, 1020, 699, 1045), (967, 858, 1322, 957)]:
        x0, y0, x1, y1 = box
        assert np.array_equal(before[y0:y1, x0:x1], after[y0:y1, x0:x1]), "费用公式被改动"
    pink_spans = {}
    for bar in BARS:
        start, end, y = bar["c"] - BAR_WIDTH, bar["c"], bar["base"] - 5
        row = after[y, start - 5:end + 6].astype(int)
        pink = (row[:, 0] > 220) & (row[:, 1] < 220) & (row[:, 2] > row[:, 1] + 10)
        xs = np.flatnonzero(pink)
        assert xs[-1] - xs[0] + 1 == 107, "加工条实际填色长度不一致"
        assert np.max(after[y, start]) < 50 and np.max(after[y, end]) < 50, "加工条边框缺失"
        pink_spans[bar["name"]] = int(xs[-1] - xs[0] + 1)
    # 排除抗锯齿的浅红边缘，测量不透明红线起点（允许半线宽的 1 px 偏差）。
    red_plateau = next(x for x in range(1255, 1270) if np.all(after[966, x:x + 30, 0] > 200) and np.all(after[966, x:x + 30, 1:3] < 40))
    assert abs(red_plateau - 1262) <= 1, "红线封顶与刻度未对齐"
    image.save(OUTPUT, optimize=True)
    check = dict(
        source=SOURCE.name, output=OUTPUT.name, size=image.size,
        source_sha256=sha256(SOURCE.read_bytes()).hexdigest(),
        output_sha256=sha256(OUTPUT.read_bytes()).hexdigest(),
        bars=[dict(name=b["name"], s=b["c"] - BAR_WIDTH, c=b["c"], p3_pixels=BAR_WIDTH) for b in BARS],
        measured_pink_fill_pixels=pink_spans,
        work_curve=dict(e=951, e_plus_p=1042, d=1171, d_plus_p=1262, red_plateau_x=1262),
        measured_red_plateau_start=red_plateau,
        outside_regions_changed_pixels=int(np.count_nonzero(changed & ~allowed)),
        e2_d2_unchanged=True, cost_formulas_unchanged=True,
        example_condition_note_added=False,
    )
    AUDIT.write_text(json.dumps(check, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(check, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
