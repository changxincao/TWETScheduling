"""在用户授权的生图 PNG 上校正几何；文字和标题均取自模型底稿，不生成 SVG。"""
from pathlib import Path
import json

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/figures/work1-final-20261031"
SOURCE = OUT / "work1-model-source.png"
SCALE = 3
src = Image.open(SOURCE).convert("RGB")
assert src.size == (1448, 1086)
canvas = src.resize((1448*SCALE, 1086*SCALE), Image.Resampling.LANCZOS)
draw = ImageDraw.Draw(canvas)
BLACK, BLUE, RED, GREEN = (0, 0, 0), (0, 25, 245), (245, 0, 0), (0, 155, 52)
BAND, GUIDE = (229, 255, 241), (72, 205, 240)
E, D, ER, DR, P = 407, 607, 1040, 1240, 100


def box(xy, fill, outline=None, width=1):
    draw.rectangle(tuple(round(v*SCALE) for v in xy), fill=fill, outline=outline,
                   width=max(1, round(width*SCALE)))


def line(points, fill=BLACK, width=1):
    draw.line([(round(x*SCALE), round(y*SCALE)) for x, y in points], fill=fill,
              width=max(1, round(width*SCALE)))


def dot(x, y, r=3.7):
    draw.ellipse(tuple(round(v*SCALE) for v in (x-r, y-r, x+r, y+r)), fill=BLACK)


def dashed(x, lo, hi):
    for y in range(round(lo), round(hi), 9):
        line([(x, y), (x, min(y+5, hi))], GUIDE, .8)


def ink(xy, color=BLACK, colored=False, drop_border=False):
    """从模型图中取文字的透明墨迹，去掉原来白底／粉底，保留原字形。"""
    a = np.asarray(src.crop(xy)).astype(float)
    if colored:
        alpha = 255-a.min(axis=2)
    else:
        background = float(np.median(a.max(axis=2)))
        alpha = np.clip((background-a.max(axis=2))*255/background, 0, 255)
    alpha[alpha < (45 if colored else 26)] = 0
    if drop_border:
        mask = Image.fromarray((alpha > 0).astype("uint8")*255)
        w, h = mask.size
        seeds = [(x, y) for x in range(w) for y in [0, h-1]] + [(x, y) for y in range(h) for x in [0, w-1]]
        for point in seeds:
            if mask.getpixel(point):
                ImageDraw.floodfill(mask, point, 0)
        alpha = np.where(np.asarray(mask) > 0, alpha, 0)
    patch = Image.new("RGBA", (a.shape[1], a.shape[0]), color+(255,))
    patch.putalpha(Image.fromarray(alpha.astype("uint8")))
    bounds = patch.getbbox()
    assert bounds, xy
    return patch.crop(bounds)


def paste(patch, x, y, center=False):
    if center:
        x -= patch.width/2
    patch = patch.resize((patch.width*SCALE, patch.height*SCALE), Image.Resampling.LANCZOS)
    canvas.paste(patch, (round(x*SCALE), round(y*SCALE)), patch)


def axis(x0, x1, y, top=None):
    line([(x0, y), (x1-5, y)], width=1.2)
    draw.polygon([(round(x*SCALE), round(z*SCALE)) for x, z in
                  [(x1, y), (x1-9, y-4), (x1-9, y+4)]], fill=BLACK)
    if top is not None:
        line([(x0, top+5), (x0, y)], width=1.2)
        draw.polygon([(round(x*SCALE), round(z*SCALE)) for x, z in
                      [(x0, top), (x0-4, top+9), (x0+4, top+9)]], fill=BLACK)


job_text_boxes = [(180, 115, 259, 142), (166, 223, 251, 250),
                  (461, 115, 548, 142), (442, 223, 527, 250),
                  (744, 115, 828, 142), (648, 223, 747, 250)]
job_text = [ink(b) for b in job_text_boxes]
old_jobs = [(150, 111, 280, 145), (145, 220, 267, 253), (444, 111, 561, 145),
            (406, 220, 551, 253), (724, 111, 846, 145), (623, 220, 770, 253)]
colors = []
for x0, y0, x1, y1 in old_jobs:
    pixels = np.asarray(src)[y0+3:y1-3, x0+3:x1-3].reshape(-1, 3)
    colors.append(tuple(int(v) for v in np.median(pixels, axis=0)))
c_labels = [ink(b) for b in [(286, 91, 318, 111), (273, 201, 304, 222),
                            (568, 91, 598, 113), (556, 201, 586, 223),
                            (851, 91, 882, 113), (775, 201, 807, 223)]]
windows = [ink(b, GREEN, True) for b in [(170, 60, 267, 82), (165, 173, 249, 196),
                                         (475, 60, 547, 82), (446, 173, 523, 196),
                                         (741, 60, 827, 82), (657, 173, 738, 196)]]
s_label = ink((158, 426, 187, 451))
c_label = ink((286, 369, 319, 394))
e_label = ink((395, 430, 423, 454))
d_label = ink((594, 430, 623, 454))
early_label = ink((327, 430, 360, 457), BLUE, True)
tardy_label = ink((644, 527, 683, 550), RED, True)
ew_label = ink((982, 353, 1037, 379), BLUE, True)
tw_label = ink((1270, 447, 1320, 475), RED, True)
time_label = ink((866, 150, 920, 176))


def window(x0, x1, y, text, ends=False):
    line([(x0, y), (x1, y)], GREEN, 1.5)
    for x in [x0, x1]:
        line([(x, y-5), (x, y+5)], GREEN, 1.2)
    paste(text, (x0+x1)/2, y-24, True)


def job(j, x0, x1, y, label=None, start=False, completion_above=True):
    top = y-32
    box((x0, top, x1, y), colors[j-1], BLACK, 1.2)
    paste(job_text[j-1], (x0+x1)/2, top+(32-job_text[j-1].height)/2, True)
    dot(x1, top)
    paste(c_label if label is None else label, x1+6, top-18 if completion_above else top+3)
    if start:
        dot(x0, y, 2.4)
        paste(s_label, x0, y+10, True)


def idle(lo, hi, y):
    for x in range(lo+6, hi-5, 7):
        line([(x, y), (min(x+4, hi-5), y)], (90, 90, 90), .8)
    for x, direction in [(lo, 1), (hi, -1)]:
        draw.polygon([(round(a*SCALE), round(b*SCALE)) for a, b in
                      [(x, y), (x+direction*6, y-3), (x+direction*6, y+3)]], fill=BLACK)
    idle_font = ImageFont.truetype("C:/Windows/Fonts/timesi.ttf", 17*SCALE)
    draw.text(((lo+hi)*SCALE/2, (y+5)*SCALE), "Idle time", font=idle_font, fill=BLACK, anchor="mt")


# 只清理并校正图中对象区域；浅蓝标题、正文公式和图例文字保留模型原像素。
box((130, 57, 887, 146), "white")
box((130, 146, 887, 289), "white")
for x in [E, D]:
    dashed(x, 58, 145)
axis(80, 911, 145)
axis(80, 911, 253)
schedule = [(1, 150, 280, 145, 211, 349), (3, 447, 547, 145, E, D),
            (5, 674, 796, 145, 720, 848), (2, 145, 267, 253, 205, 330),
            (4, 406, 551, 253, 480, 625), (6, 551, 698, 253, 623, 773)]
for j, lo, hi, y, e, d in schedule:
    window(e, d, 85 if y == 145 else 198, windows[j-1])
    job(j, lo, hi, y, c_labels[j-1])
idle(280, 447, 154)
idle(547, 674, 154)
idle(267, 406, 262)
for y in [152, 260]:
    box((876, y, 920, y+25), "white")
    paste(time_label, 884, y+1)

# 右上参数说明中的 J3 也使用同一个 100 px 加工长度。
box((1084, 210, 1276, 288), "white")
job(3, 1115, 1215, 250, start=True)

# 左侧两个备选完工位置，共享精确端点；费用条带在加工矩形之外。
for lo, hi in [(347, 453), (448, 552)]:
    box((129, lo, 836, hi), "white")
for y in [417, 514]:
    box((E+1, y-65, D-1, y), BAND)
    for x in [E, D]:
        dashed(x, y-70, y)
    window(E, D, y-35, windows[2])
    axis(132, 834, y)
    paste(e_label, E, y+10, True)
    paste(d_label, D, y+10, True)
job(3, 180, 280, 417, start=True)
job(3, 560, 660, 514, start=True)
for lo, hi, y, color, text in [(280, E, 420, BLUE, early_label), (D, 660, 517, RED, tardy_label)]:
    box((lo, y, hi, y+8), "white", color, .8)
    for x in range(lo-8, hi, 5):
        a, b = max(x, lo), min(x+8, hi)
        if b > a:
            line([(a, y+8-(a-x)), (b, y+8-(b-x))], color, .7)
    paste(text, (lo+hi)/2, y+12, True)

# work 部分直接描出加工条上边缘的窗口外区间，不再使用悬空尺寸箭头。
box((879, 346, 1426, 552), "white")
for y in [425, 518]:
    box((ER+1, y-62, DR-1, y), BAND)
    dashed(ER, y-78, y)
    dashed(DR, y-78, y)
    window(ER, DR, y-43, windows[2])
    axis(880, 1418, y)
    paste(e_label, ER, y+10, True)
    paste(d_label, DR, y+10, True)
job(3, 990, 1090, 425, start=True, completion_above=False)
job(3, 1190, 1290, 518, start=True, completion_above=False)
line([(990, 393), (ER, 393)], BLUE, 4)
line([(DR, 486), (1290, 486)], RED, 4)
paste(ew_label, 1015, 353, True)
paste(tw_label, 1265, 458, True)
for y in [435, 528]:
    paste(time_label, 1389, y)

# 修正原图多余折点，保留从模型图提取的轴名、刻度字形和斜率文字。
cost_c = c_label
conv_ticks = [ink(b) for b in [(141, 779, 201, 804), (395, 779, 425, 804),
                              (594, 779, 625, 804), (684, 779, 753, 804)]]
# 只擦除原图越过清理区上缘的红色注记，保留相邻黑色公式。
red_bounds = (715*SCALE, 656*SCALE, 842*SCALE, 672*SCALE)
red_patch = np.asarray(canvas.crop(red_bounds)).copy()
red_mask = (red_patch[:, :, 0] > 150) & (red_patch[:, :, 0]-red_patch[:, :, 1].astype(int) > 2) & (red_patch[:, :, 0]-red_patch[:, :, 2].astype(int) > 2)
red_patch[red_mask] = 255
canvas.paste(Image.fromarray(red_patch), red_bounds[:2])
box((70, 668, 842, 806), "white")
box((E+1, 676, D-1, 774), BAND)
for x in [E, D]:
    dashed(x, 676, 774)
axis(78, 840, 774, 669)
convex = [(80, 682), (167, 736), (E, 774), (D, 774), (718, 741), (839, 669)]
line(convex[:3], BLUE, 1.8)
line(convex[2:4], BLACK, 2)
line(convex[3:], RED, 1.8)
for x, tick in zip([167, E, D, 718], conv_ticks):
    line([(x, 774), (x, 780)])
    paste(tick, x, 783, True)
paste(cost_c, 813, 747)
slope_zh_font = ImageFont.truetype("C:/Windows/Fonts/simsun.ttc", 18*SCALE)
slope_math_font = ImageFont.truetype("C:/Windows/Fonts/timesi.ttf", 22*SCALE)
for x, y, text, color in [(101, 669, "−(α+γ)", BLUE), (244, 715, "−α", BLUE),
                           (633, 715, "β", RED), (700, 666, "β+δ", RED)]:
    draw.text((x*SCALE, y*SCALE), "斜率", font=slope_zh_font, fill=color, anchor="lt")
    draw.text(((x+40)*SCALE, (y-1)*SCALE), text, font=slope_math_font, fill=color, anchor="lt")

step_ticks = [ink(b) for b in [(201, 983, 272, 1008), (295, 983, 366, 1008),
                              (394, 983, 424, 1008), (593, 983, 624, 1008),
                              (628, 983, 692, 1008), (700, 983, 759, 1008)]]
step_c = ink((807, 983, 839, 1008))
box((70, 932, 842, 1008), "white")
box((70, 900, 110, 932), "white")
box((705, 916, 842, 932), "white")
box((E+1, 932, D-1, 977), BAND)
for x in [E, D]:
    dashed(x, 932, 977)
axis(78, 839, 977, 924)
line([(80, 938), (238, 938), (238, 951), (329, 951), (329, 964), (E, 964), (E, 977)], BLUE, 1.8)
line([(E, 977), (D, 977)], BLACK, 2)
line([(D, 977), (D, 964), (665, 964), (665, 948), (745, 948), (745, 929), (823, 929)], RED, 1.8)
for x, tick in zip([238, 329, E, D, 665, 745], step_ticks):
    line([(x, 977), (x, 983)])
    paste(tick, x, 986, True)
paste(step_c, 808, 983)

# 右侧费用图斜段在横轴上的长度严格等于同一个加工条长度 P。
work_ticks = [ink(b) for b in [(1015, 907, 1047, 936), (1080, 907, 1158, 937),
                              (1222, 907, 1257, 936), (1298, 907, 1380, 937)]]
work_c = c_label
alpha_p = ink((864, 789, 899, 817), BLUE, True)
beta_p = ink((1365, 740, 1421, 771), RED, True)
work_loss = ink((870, 729, 912, 757))
box((897, 741, 1420, 938), "white")
box((865, 785, 904, 824), "white")
box((869, 728, 914, 758), "white")
box((ER+1, 746, DR-1, 900), BAND)
dashed(ER, 746, 900)
dashed(DR, 746, 900)
axis(902, 1418, 900, 759)
line([(904, 805), (ER, 805), (ER+P, 900)], BLUE, 1.8)
line([(ER+P, 900), (DR, 900)], BLACK, 2)
line([(DR, 900), (DR+P, 772), (1415, 772)], RED, 1.8)
for x, tick in zip([ER, ER+P, DR, DR+P], work_ticks):
    line([(x, 900), (x, 906)])
    paste(tick, x, 912, True)
paste(work_c, 1386, 910)
paste(alpha_p, 866, 791)
paste(beta_p, 1367, 742)
paste(work_loss, 873, 731)

# 按用户最新要求去掉推广说明，三幅纵轴统一写“成本”。
box((335, 608, 625, 636), "white")
box((310, 855, 652, 880), "white")
box((268, 907, 705, 932), "white")
cost_font = ImageFont.truetype("C:/Windows/Fonts/simsun.ttc", 18*SCALE)
for bounds, x, y in [((24, 707, 68, 736), 28, 711), ((24, 916, 68, 956), 28, 933),
                     ((869, 728, 914, 758), 873, 735)]:
    box(bounds, "white")
    draw.text((x*SCALE, y*SCALE), "成本", font=cost_font, fill=BLACK, anchor="lt")

for bounds, x, text in [((724, 1031, 804, 1059), 728, "提前成本"),
                         ((848, 1031, 925, 1059), 850, "拖期成本")]:
    box(bounds, "white")
    draw.text((x*SCALE, 1037*SCALE), text, font=cost_font, fill=BLACK, anchor="lt")

for j, bounds in enumerate([(17, 1032, 45, 1058), (81, 1032, 110, 1058),
                             (146, 1032, 175, 1058), (210, 1032, 239, 1058),
                             (273, 1032, 302, 1058), (334, 1032, 363, 1058)]):
    box(bounds, colors[j], BLACK, 1)

# 断言验证的是具体输出坐标，不把提示词中的意图当作已经完成。
j3 = [(447, 547), (1115, 1215), (180, 280), (560, 660), (990, 1090), (1190, 1290)]
assert all(hi-lo == P for lo, hi in j3)
assert D-E == DR-ER == 2*P
assert ER+P < DR and all(e <= c <= d for _, _, c, _, e, d in schedule)
for y in [145, 253]:
    rows = sorted((s, c) for _, s, c, row, _, _ in schedule if row == y)
    assert all(rows[k][1] <= rows[k+1][0] for k in range(len(rows)-1))
slopes_check = -np.diff([p[1] for p in convex])/np.diff([p[0] for p in convex])
assert np.all(np.diff(slopes_check) >= -1e-12)
# 从实际栅格测量六处粉色加工条，并检查标题和公式的保留区域。
pixels = np.asarray(canvas)
j3_boxes = [(447, 113, 547, 145), (1115, 218, 1215, 250), (180, 385, 280, 417),
            (560, 482, 660, 514), (990, 393, 1090, 425), (1190, 486, 1290, 518)]
raster_widths = []
for x0, y0, x1, y1 in j3_boxes:
    region = pixels[y0*SCALE:(y1+1)*SCALE, x0*SCALE:x1*SCALE+1]
    cols = np.flatnonzero(np.any(np.all(region == colors[2], axis=2), axis=0))
    raster_widths.append(int(cols[-1]-cols[0]+1))
    clean = pixels[(y0+7)*SCALE:(y0+12)*SCALE, (x0+3)*SCALE:(x0+7)*SCALE]
    assert np.all(clean == colors[2])
assert len(set(raster_widths)) == 1
reference = src.resize(canvas.size, Image.Resampling.LANCZOS)
protected = [(20, 8, 922, 49), (941, 8, 1426, 49), (22, 307, 846, 345),
             (870, 307, 1424, 345), (22, 570, 846, 605), (870, 570, 1424, 605),
             (22, 823, 846, 852), (250, 637, 811, 661), (246, 881, 807, 905),
             (1020, 640, 1411, 732)]
for index, rect in enumerate(protected):
    rect = tuple(v*SCALE for v in rect)
    actual, expected = np.asarray(canvas.crop(rect)), np.asarray(reference.crop(rect))
    if index < 7:
        assert np.array_equal(actual, expected), rect
    else:
        # 公式框底缘混有原红色斜率注记的淡色残边，只保护公式黑色墨迹。
        black_ink = expected.max(axis=2) < 200
        assert np.array_equal(actual[black_ink], expected[black_ink]), rect
for rect in [(335, 608, 625, 636), (310, 855, 652, 880)]:
    rect = tuple(v*SCALE for v in rect)
    assert np.all(np.asarray(canvas.crop(rect)) == 255)
canvas.save(OUT/"work1-corrected-4k.png", dpi=(300, 300))
canvas.resize(src.size, Image.Resampling.LANCZOS).save(OUT/"work1-corrected.png")
(OUT/"geometry-check.json").write_text(json.dumps({
    "source_native_size": list(src.size), "export_size": list(canvas.size),
    "export_note": "3x raster export; original model text is resampled, not native 4K generation",
    "job3_intervals": j3, "job3_width": P, "left_window": [E, D], "right_window": [ER, DR],
    "all_window_widths": D-E, "main_schedule": schedule,
    "convex_slopes": slopes_check.tolist(), "work_breakpoints": [ER, ER+P, DR, DR+P],
    "raster_pink_widths_4k": raster_widths, "flat_pink_fill_verified": True,
    "header_regions_pixel_identical": 7, "formula_regions_black_ink_identical": 3,
    "axis_label": "成本", "extension_subtitles_removed": True,
    "pass": True}, ensure_ascii=False, indent=2), encoding="utf-8")
print("PNG correction exported; common widths, projections, main feasibility and convexity assertions passed.")
