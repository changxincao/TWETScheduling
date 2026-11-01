"""针对三处批注修正 E/T 端点连接及 ET 红线的多余水平段。"""

import json
from hashlib import sha256
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
SOURCE = HERE / "work3-two-stage-draft.png"
OUTPUT = HERE / "work3-annotations-corrected.png"
SCALE = 4
BLUE, RED = (20, 55, 245), (235, 30, 25)


def correct_interval(image, left, right, complete, color):
    """区间两端严格对应 C/e 或 d/C，并连接完工点以便辨认。"""
    box = (int(left) - 4, 752, int(right) + 5, 773)
    layer = Image.new("RGBA", ((box[2] - box[0]) * SCALE, (box[3] - box[1]) * SCALE))
    draw = ImageDraw.Draw(layer)

    def xy(x, y):
        return (round((x - box[0]) * SCALE), round((y - box[1]) * SCALE))

    # 原区间端点本已按统一时间尺校准，补强端线并连接到 C3 黑点。
    draw.line([xy(left, 758), xy(right, 758)], fill=color + (255,), width=8)
    for x in (left, right):
        draw.line([xy(x, 754), xy(x, 762)], fill=color + (255,), width=6)
    draw.line([xy(complete, 762), xy(complete, 766)], fill=color + (255,), width=4)
    draw.ellipse([xy(complete - 3, 766), xy(complete + 3, 772)], fill=(0, 0, 0, 255))
    layer = layer.resize((box[2] - box[0], box[3] - box[1]), Image.Resampling.LANCZOS)
    image.paste(layer, box[:2], layer)
    return box


def correct_red_curve(image):
    """两段正斜率直线仅在 d+v 折转，后段更陡，删除旧水平段。"""
    box = (1028, 1015, 1316, 1094)
    pixels = np.asarray(image).copy()
    part = pixels[box[1]:box[3], box[0]:box[2]]
    r, g, b = (part[:, :, k].astype(int) for k in range(3))
    old_red = (r > g + 1) & (r > b + 1)
    part[old_red] = (255, 255, 255)
    image = Image.fromarray(pixels)
    # 清理旧线的浅色残影及旧拐点投影；保留窗口边界与横轴。
    ImageDraw.Draw(image).rectangle((1035, 1015, 1315, 1087), fill="white")

    d, kink, end = 1033.0, 1154.0, 1307.0
    baseline, kink_y = 1090.0, 1074.0
    # 示意取两段斜率之比为 1:2，符合 beta>0、delta>0 的凸 ET 费用。
    first_slope = (baseline - kink_y) / (kink - d)
    end_y = kink_y - 2 * first_slope * (end - kink)
    points = [(d, baseline), (kink, kink_y), (end, end_y)]
    layer = Image.new("RGBA", ((box[2] - box[0]) * SCALE, (box[3] - box[1]) * SCALE))
    draw = ImageDraw.Draw(layer)

    def xy(x, y):
        return (round((x - box[0]) * SCALE), round((y - box[1]) * SCALE))

    for y in range(round(kink_y) + 1, 1090, 6):
        draw.line([xy(kink, y), xy(kink, min(y + 3, 1089))], fill=(100, 100, 100, 255), width=4)
    draw.line([xy(*point) for point in points], fill=RED + (255,), width=8, joint="curve")
    chinese = ImageFont.truetype(r"C:\Windows\Fonts\simsun.ttc", 20 * SCALE)
    math_font = ImageFont.truetype(r"C:\Windows\Fonts\timesi.ttf", 23 * SCALE)
    for center, y, symbol in [(1107, 1049, "β"), (1215, 1028, "β+δ")]:
        cn_width = draw.textlength("斜率 ", font=chinese)
        math_width = draw.textlength(symbol, font=math_font)
        x = xy(center, y)[0] - (cn_width + math_width) / 2
        draw.text((round(x), xy(center, y)[1]), "斜率 ", font=chinese, fill=RED, anchor="lm")
        draw.text((round(x + cn_width), xy(center, y)[1]), symbol, font=math_font, fill=RED, anchor="lm")
    layer = layer.resize((box[2] - box[0], box[3] - box[1]), Image.Resampling.LANCZOS)
    image.paste(layer, box[:2], layer)
    return image, box, points


def main():
    source = Image.open(SOURCE).convert("RGB")
    image = source.copy()
    # 同一时间尺：omega1 C=6,e=8；omega3 d=12,C=13。
    early = [70 + 6 * 16.8, 70 + 8 * 16.8]
    late = [935 + 12 * 16.8, 935 + 13 * 16.8]
    regions = [correct_interval(image, *early, early[0], BLUE),
               correct_interval(image, *late, late[1], RED)]
    image, box, points = correct_red_curve(image)
    regions.append(box)
    before, after = np.asarray(source), np.asarray(image)
    changed = np.any(before != after, axis=2)
    allowed = np.zeros(changed.shape, dtype=bool)
    for x0, y0, x1, y1 in regions:
        allowed[y0:y1, x0:x1] = True
    assert not np.any(changed & ~allowed), "三处编辑区域之外有变化"
    assert np.array_equal(before[1020:1118, :1028], after[1020:1118, :1028]), "ET 公式或蓝线发生变化"
    for interval, color in [(early, BLUE), (late, RED)]:
        for x in [interval[0], sum(interval) / 2, interval[1]]:
            neighborhood = after[757:760, round(x) - 1:round(x) + 2].astype(int)
            assert np.any(np.max(abs(neighborhood - np.array(color)), axis=2) < 50), "区间端点或中段缺少对应颜色"
    for complete in [early[0], late[1]]:
        assert np.max(after[769, round(complete)]) < 40, "完工点与区间投影未对齐"
    # 实测红色墨迹沿每条目标直线分布，避免仅核对输入坐标。
    raster_errors = []
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        segment_errors = []
        for x in range(round(x0) + 5, round(x1) - 4):
            expected_y = y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            lo, hi = int(expected_y) - 4, int(expected_y) + 5
            row = after[lo:hi, x].astype(int)
            ys = np.flatnonzero((row[:, 0] > 180) & (row[:, 0] > row[:, 1] + 80) & (row[:, 0] > row[:, 2] + 80))
            assert len(ys), "目标直线上缺少红色墨迹"
            segment_errors.append(abs(float(np.mean(ys + lo)) - expected_y))
        raster_errors.append(max(segment_errors))
    assert max(raster_errors) < 1.1
    image.save(OUTPUT, optimize=True)
    audit = dict(source=SOURCE.name, output=OUTPUT.name, size=image.size,
                 source_sha256=sha256(SOURCE.read_bytes()).hexdigest(),
                 output_sha256=sha256(OUTPUT.read_bytes()).hexdigest(),
                 early_interval=dict(from_C=early[0], to_e=early[1], E=2),
                 late_interval=dict(from_d=late[0], to_C=late[1], T=1),
                 red_curve_points=points, red_slope_ratio=2,
                 red_curve_max_raster_error=raster_errors,
                 ET_formula_and_blue_curve_unchanged=True,
                 unexpected_changed_pixels=int(np.count_nonzero(changed & ~allowed)))
    (HERE / "annotations-corrected-check.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(audit, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
