"""保留生图版式，校准三场景时间尺、窗口下标与总成本括号。"""

import json
from hashlib import sha256
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
SOURCE = HERE / "work3-model-v1.png"
OUTPUT = HERE / "work3-two-stage-draft.png"
SCALE = 4
UNIT = 16.8
BLUE, RED, GREEN = (20, 55, 245), (235, 30, 25), (0, 195, 0)
COLORS = {1: (146, 226, 244), 2: (255, 202, 112), 3: (246, 177, 218),
          4: (179, 239, 190), 5: (198, 140, 231), 6: (255, 239, 126)}
# 同一机器分配及顺序，工时为场景输入，开始时刻允许场景调整。
SCENARIOS = [
    {"M1": [(1, 0, 3), (3, 4, 6), (5, 7, 10)], "M2": [(2, 0, 3), (4, 4, 7), (6, 8, 11)]},
    {"M1": [(1, 0, 4), (3, 5, 9), (5, 10, 14)], "M2": [(2, 0, 4), (4, 5, 9), (6, 10, 14)]},
    {"M1": [(1, 0, 5), (3, 6, 13), (5, 14, 19)], "M2": [(2, 0, 5), (4, 6, 11), (6, 12, 17)]},
]


def font(name, size):
    return ImageFont.truetype(str(Path(r"C:\Windows\Fonts") / name), round(size * SCALE))


def scene_patch(index):
    """用统一时间单位绘制场景，数值与像素位置从同一数据获得。"""
    left, right = [(12, 446), (456, 875), (887, 1328)][index]
    origin = [70, 503, 935][index]
    top, bottom = 711, 892
    patch = Image.new("RGB", ((right - left) * SCALE, (bottom - top) * SCALE), "white")
    draw = ImageDraw.Draw(patch)

    def xy(x, y):
        return (round((x - left) * SCALE), round((y - top) * SCALE))

    def line(points, color, width=1):
        draw.line([xy(*p) for p in points], fill=color, width=round(width * SCALE))

    def text(x, y, value, color="black", face="times.ttf", size=14, anchor="mm"):
        draw.text(xy(x, y), value, fill=color, font=font(face, size), anchor=anchor)

    def tick_interval(x0, x1, y, color):
        line([(x0, y), (x1, y)], color, 2)
        for x in (x0, x1):
            line([(x, y - 4), (x, y + 4)], color, 1.5)

    def state_label(x, chinese, symbol, subscript, color):
        # 中文字体缺少部分 Unicode 下标，数学部分单独使用 Times 字体排版。
        cn_font, math_font, sub_font = font("simsun.ttc", 16), font("timesi.ttf", 17), font("times.ttf", 10)
        cn_width = draw.textlength(chinese + " ", font=cn_font)
        math_width = draw.textlength(symbol, font=math_font)
        sub_width = draw.textlength(subscript, font=sub_font)
        left_px = xy(x, 743)[0] - (cn_width + math_width + sub_width) / 2
        draw.text((round(left_px), xy(x, 743)[1]), chinese + " ", font=cn_font, fill=color, anchor="lm")
        draw.text((round(left_px + cn_width), xy(x, 748)[1]), symbol, font=math_font, fill=color, anchor="ls")
        draw.text((round(left_px + cn_width + math_width), xy(x, 751)[1]), subscript, font=sub_font, fill=color, anchor="ls")

    e, d = origin + 8 * UNIT, origin + 12 * UNIT
    draw.rectangle([xy(e, 712), xy(d, 797)], fill=(222, 253, 236))
    for x in (e, d):
        for y in range(713, 797, 8):
            line([(x, y), (x, min(y + 4, 797))], (0, 197, 230))
    tick_interval(e, d, 734, GREEN)
    text((e + d) / 2, 722, "[e₃,d₃]", GREEN, "timesi.ttf", 17)

    for machine, base in [("M1", 797), ("M2", 862)]:
        line([(origin, base), (origin + 350, base)], (15, 15, 15), 1.4)
        draw.polygon([xy(origin + 350, base), xy(origin + 344, base - 3), xy(origin + 344, base + 3)], fill="black")
        text(origin - 34, base - 14, "M₁" if machine == "M1" else "M₂", face="timesi.ttf", size=20)
        for t in range(0, 21, 4):
            x = origin + t * UNIT
            line([(x, base), (x, base + 5)], "black")
            text(x, base + 15, str(t), size=14)
        text(origin + 359, base + 12, "时间", face="simsun.ttc", size=12)
        for job, start, complete in SCENARIOS[index][machine]:
            x0, x1 = origin + start * UNIT, origin + complete * UNIT
            draw.rectangle([xy(x0, base - 28), xy(x1, base)], fill=COLORS[job], outline=(15, 15, 15), width=round(1.5 * SCALE))
            text((x0 + x1) / 2, base - 14, "J" + chr(0x2080 + job), face="timesi.ttf", size=18)

    complete = SCENARIOS[index]["M1"][1][2]
    cx = origin + complete * UNIT
    draw.ellipse([xy(cx - 3, 766), xy(cx + 3, 772)], fill="black")
    # C 标签放在刻度下一行，避免压在加工条或 E/T 区间上。
    text(cx, 825, "C₃," + chr(0x2081 + index), face="timesi.ttf", size=14)
    if complete < 8:
        tick_interval(cx, e, 758, BLUE)
        state_label((cx + e) / 2, "早到", "E", "3,1", BLUE)
    elif complete > 12:
        tick_interval(d, cx, 758, RED)
        state_label((d + cx) / 2, "延迟", "T", "3,3", RED)
    else:
        text((e + d) / 2, 747, "窗内完成", (0, 130, 0), "simsun.ttc", 16)
    return patch.resize((right - left, bottom - top), Image.Resampling.LANCZOS), (left, top, right, bottom)


def brace_patch(source):
    """各括号只覆盖对应的窗口费或完整期望执行费。"""
    box = (470, 934, 971, 972)
    patch = Image.new("RGB", ((box[2] - box[0]) * SCALE, (box[3] - box[1]) * SCALE))
    for y in range(patch.height):
        ImageDraw.Draw(patch).line((0, y, patch.width, y), fill=source.getpixel((1200, box[1] + y // SCALE)))
    draw = ImageDraw.Draw(patch)
    for x0, x1, label in [(478, 644, "一阶段窗口设置成本"), (661, 963, "二阶段期望 ET 成本")]:
        mid = (x0 + x1) / 2
        points = [(x0, 937), (x0 + 3, 942), (mid - 8, 942), (mid, 947), (mid + 8, 942), (x1 - 3, 942), (x1, 937)]
        draw.line([(round((x - box[0]) * SCALE), round((y - box[1]) * SCALE)) for x, y in points], fill=(230, 90, 0), width=6, joint="curve")
        draw.text((round((mid - box[0]) * SCALE), 26 * SCALE), label, font=font("simsun.ttc", 16), fill=(230, 90, 0), anchor="mm")
    return patch.resize((box[2] - box[0], box[3] - box[1]), Image.Resampling.LANCZOS), box


def main():
    source = Image.open(SOURCE).convert("RGB")
    image = source.copy()
    regions = []
    for i in range(3):
        patch, box = scene_patch(i)
        image.paste(patch, box[:2])
        regions.append(box)
    # 统一六个选定窗口标签的字形与字号，同时校正 J5 的错误下标。
    for j, cx, cy in [(1, 257, 116), (3, 512, 116), (5, 751, 116), (2, 264, 223), (4, 506, 223), (6, 735, 223)]:
        box = (cx - 31, cy - 9, cx + 32, cy + 10)
        patch = Image.new("RGB", (63 * SCALE, 19 * SCALE), "white")
        digit = chr(0x2080 + j)
        ImageDraw.Draw(patch).text((31.5 * SCALE, 9 * SCALE), f"[e{digit},d{digit}]", font=font("timesi.ttf", 17), fill=(0, 220, 0), anchor="mm")
        image.paste(patch.resize((63, 19), Image.Resampling.LANCZOS), box[:2])
        regions.append(box)
    patch, box = brace_patch(source)
    image.paste(patch, box[:2])
    regions.append(box)

    check = []
    for omega, scenario in enumerate(SCENARIOS, 1):
        for machine, order in [("M1", [1, 3, 5]), ("M2", [2, 4, 6])]:
            rows = scenario[machine]
            assert [row[0] for row in rows] == order
            assert all(s >= 0 and c > s for _, s, c in rows)
            assert all(rows[k][2] <= rows[k + 1][1] for k in range(2))
        _, start, complete = scenario["M1"][1]
        check.append(dict(omega=omega, probability=1 / 3, p3=complete - start, S3=start, C3=complete, e3=8, d3=12, E3=max(8 - complete, 0), T3=max(complete - 12, 0), rows=scenario))
    before, after = np.asarray(source), np.asarray(image)
    changed = np.any(before != after, axis=2)
    allowed = np.zeros(changed.shape, dtype=bool)
    for x0, y0, x1, y1 in regions:
        allowed[y0:y1, x0:x1] = True
    assert not np.any(changed & ~allowed)
    assert np.array_equal(before[977:1127], after[977:1127]), "ET 费用公式与曲线发生变化"
    assert np.array_equal(before[440:621, 880:1320], after[440:621, 880:1320]), "窗口成本发生变化"
    measured_pink_spans = []
    for origin, scenario in zip([70, 503, 935], SCENARIOS):
        _, start, complete = scenario["M1"][1]
        x0, x1 = round(origin + start * UNIT), round(origin + complete * UNIT)
        row = after[792, x0 - 3:x1 + 4].astype(int)
        pink = (row[:, 0] > 210) & (row[:, 2] > row[:, 1] + 20) & (row[:, 1] < 210)
        xs = np.flatnonzero(pink)
        span = int(xs[-1] - xs[0] + 1)
        assert abs(span - ((complete - start) * UNIT - 2)) < 3, "场景条长与随机工时不符"
        measured_pink_spans.append(span)
    image.save(OUTPUT, optimize=True)
    audit = dict(status="draft", base=SOURCE.name, output=OUTPUT.name, size=image.size,
                 source_sha256=sha256(SOURCE.read_bytes()).hexdigest(), output_sha256=sha256(OUTPUT.read_bytes()).hexdigest(),
                 units_to_pixels=UNIT, scenario_checks=check, stage_one_shared=True,
                 processing_times_are_scenario_inputs=True, only_ET_cost=True,
                 measured_pink_fill_pixels=measured_pink_spans,
                 ET_formula_and_curve_unchanged=True, window_cost_unchanged=True,
                 unexpected_changed_pixels=int(np.count_nonzero(changed & ~allowed)))
    (HERE / "draft-check.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(audit, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
